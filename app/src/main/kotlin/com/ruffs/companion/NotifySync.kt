package com.ruffs.companion

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Avisos con la app cerrada.
 *
 * Cada ~15 min (y al salir de la app) se consulta /api/companion/notifications:
 *  - avisos de tareas terminadas → se muestran al momento;
 *  - recordatorios de las próximas 48 h → alarma local a su hora exacta, así
 *    suenan a tiempo aunque el teléfono no tenga red en ese momento.
 * Mientras la app está abierta los muestra la propia página de RUFFS; para no
 * duplicar, todo lo mostrado queda anotado en [Ledger].
 */
object NotifySync {
    private const val TAG = "RUFFS-notify"
    private const val WORK_PERIODIC = "ruffs_notify_periodic"
    private const val WORK_NOW = "ruffs_notify_now"

    @Volatile var appInForeground = false

    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!AppOptions.notifyClosed(ctx)) {
            wm.cancelUniqueWork(WORK_PERIODIC)
            Alarms.cancelAll(ctx)
            return
        }
        // Batería: Android agrupa estas consultas con las de otras apps (y en
        // reposo las espacia); con batería baja no se hacen. Los recordatorios
        // ya programados suenan igual: son alarmas locales, sin red.
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    @Volatile private var lastSoon = 0L

    fun syncSoon(ctx: Context) {
        if (!AppOptions.notifyClosed(ctx)) return
        // Entrar y salir de la app varias veces seguidas no repite la consulta.
        val now = System.currentTimeMillis()
        if (now - lastSoon < 2 * 60_000) return
        lastSoon = now
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, req)
    }

    suspend fun run(ctx: Context) {
        if (!AppOptions.notifyClosed(ctx) || !ServerConfig.isConfigured(ctx)) return
        val after = AppOptions.notifySeq(ctx)
        val data = ServerApi.getJson(ctx, "/api/companion/notifications?after=${after.coerceAtLeast(0)}")
        data.optJSONObject("wake_word")?.optString("word")?.takeIf { it.isNotBlank() }?.let { word ->
            if (word != AppOptions.wakeWord(ctx)) {
                AppOptions.setWakeWord(ctx, word)
                WakeWordService.refresh(ctx)
            }
        }
        val seq = data.optLong("seq", 0)
        val notifier = WebNotifier(ctx)
        // Primera vez (o RUFFS se reinició y el contador volvió a empezar): no
        // mostrar avisos antiguos, solo tomar el número.
        if (after >= 0 && seq >= after) {
            val list = data.optJSONArray("notifications")
            for (i in 0 until (list?.length() ?: 0)) {
                val n = list!!.getJSONObject(i)
                val taskId = n.optString("task_id")
                val key = if (taskId.startsWith("reminder-")) "rem:" + taskId.removePrefix("reminder-")
                          else "tag:task-" + taskId.ifBlank { n.optString("title") }
                if (appInForeground || Ledger.seenRecently(ctx, key)) continue
                Ledger.mark(ctx, key)
                val body = n.optString("body").ifBlank {
                    if (n.optString("status") == "success") "Terminó" else "Terminó con errores"
                }
                notifier.showWeb("srv-" + n.optLong("seq"), n.optString("title"), body, "task-" + taskId.ifBlank { n.optString("title") })
            }
        }
        AppOptions.setNotifySeq(ctx, seq)
        val reminders = data.optJSONArray("reminders")
        val items = (0 until (reminders?.length() ?: 0)).map { reminders!!.getJSONObject(it) }.map {
            Alarms.Reminder(it.optString("id"), it.optString("title"), it.optString("body"), it.optLong("due"))
        }
        Alarms.replaceAll(ctx, items)
        Log.d(TAG, "sync ok: seq=$seq, ${items.size} recordatorio(s)")
    }

    class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result = try {
            run(applicationContext)
            Result.success()
        } catch (e: ServerApi.NotSignedIn) {
            Result.success()          // sin sesión no hay nada que reintentar
        } catch (e: Exception) {
            Log.d(TAG, "sync falló: ${e.message}")
            Result.retry()
        }
    }
}

/** Alarmas locales para los recordatorios que vienen. */
object Alarms {
    data class Reminder(val id: String, val title: String, val body: String, val due: Long)

    private const val PREFS = "ruffs_alarms"

    fun replaceAll(ctx: Context, items: List<Reminder>) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val keep = items.filter { it.due > now - 60_000 }.associateBy { "${it.id}@${it.due}" }
        // Quitar las que ya no existen (nota borrada, hora cambiada)
        for (old in prefs.getStringSet("keys", emptySet()).orEmpty()) {
            if (old !in keep) am.cancel(pending(ctx, old, null))
        }
        for ((key, r) in keep) {
            val pi = pending(ctx, key, r)
            val at = r.due.coerceAtLeast(now + 1000)
            try {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                }
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
        prefs.edit().putStringSet("keys", keep.keys).apply()
    }

    fun cancelAll(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val am = ctx.getSystemService(AlarmManager::class.java)
        for (k in prefs.getStringSet("keys", emptySet()).orEmpty()) am.cancel(pending(ctx, k, null))
        prefs.edit().remove("keys").apply()
    }

    private fun pending(ctx: Context, key: String, r: Reminder?): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction("com.ruffs.companion.REMINDER.$key")
        if (r != null) i.putExtra("id", r.id).putExtra("title", r.title).putExtra("body", r.body).putExtra("due", r.due)
        return PendingIntent.getBroadcast(ctx, key.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        if (!AppOptions.notifyClosed(ctx)) return
        // Con la app abierta lo muestra la página; tampoco repetir uno ya mostrado.
        if (NotifySync.appInForeground || Ledger.seenRecently(ctx, "rem:$id")) return
        Ledger.mark(ctx, "rem:$id")
        val title = intent.getStringExtra("title").orEmpty().ifBlank { "Recordatorio" }
        WebNotifier(ctx).showWeb("rem-$id", title, intent.getStringExtra("body").orEmpty(), "cal-remind-$id")
        // Si se repite (diario, semanal…), la próxima la trae la siguiente sincronización.
        NotifySync.syncSoon(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // Tras reiniciar el teléfono las alarmas se pierden: volver a pedirlas.
        NotifySync.schedule(ctx)
        NotifySync.syncSoon(ctx)
    }
}

/** Qué avisos ya se mostraron (por la página o por la app), para no repetirlos. */
object Ledger {
    private const val PREFS = "ruffs_notif_ledger"
    private const val WINDOW_MS = 30 * 60 * 1000L

    fun mark(ctx: Context, key: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val e = p.edit().putLong(key, now)
        for ((k, v) in p.all) if (v is Long && now - v > 3 * 24 * 3600 * 1000L) e.remove(k)
        e.apply()
    }

    fun seenRecently(ctx: Context, key: String): Boolean {
        val at = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key, 0L)
        return at > 0 && System.currentTimeMillis() - at < WINDOW_MS
    }

    /** Lo que muestra la página: «cal-remind-ID», «note-ID», «task-reminder-ID», «task-…». */
    fun markWebTag(ctx: Context, tag: String) {
        if (tag.isBlank()) return
        mark(ctx, "tag:$tag")
        Regex("^(?:cal-remind-|note-|task-reminder-)(.+)$").find(tag)?.let { mark(ctx, "rem:" + it.groupValues[1]) }
    }
}
