package com.ruffs.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.text.Normalizer
import kotlin.concurrent.thread

/**
 * Palabra de activación con la app cerrada (opcional, desactivada por defecto).
 *
 * Escucha en el propio teléfono, sin internet (Vosk con una gramática que solo
 * conoce la palabra clave y lo que suena parecido). Al oírla abre RUFFS ya
 * escuchando. Mientras la app está abierta suelta el micrófono (ahí escucha la
 * propia página) y lo vuelve a tomar al salir.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "RUFFS-wake"
        private const val CHANNEL = "ruffs_listen"
        private const val CHANNEL_WAKE = "ruffs_wake_alert"
        private const val NOTIF_ID = 4101
        private const val NOTIF_WAKE_ID = 4102
        private const val ACTION_START = "com.ruffs.companion.wake.START"
        private const val ACTION_PAUSE = "com.ruffs.companion.wake.PAUSE"
        private const val ACTION_RESUME = "com.ruffs.companion.wake.RESUME"
        private const val ACTION_REFRESH = "com.ruffs.companion.wake.REFRESH"
        private const val ACTION_DISABLE = "com.ruffs.companion.wake.DISABLE"
        private const val CONF = 0.75

        @Volatile var running = false
            private set

        /** Palabras del vocabulario que suenan como la palabra clave (como en RUFFS del PC). */
        private val ALIASES = mapOf(
            "ruffs" to listOf("raf", "rufus", "ruff", "rough", "roughs", "ruffs"),
            "odysseus" to listOf("odiseo", "ulises", "odysseus"),
        )

        fun fold(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

        fun grammar(keyword: String): List<String> {
            val kw = fold(keyword).trim()
            val words = kw.split(Regex("\\s+")).filter { it.isNotBlank() }
            return (ALIASES[kw.replace(" ", "")].orEmpty() + words).distinct() + "[unk]"
        }

        /** ¿Algún resultado de la gramática es la palabra clave con confianza suficiente? */
        fun isWake(resultJson: String?): Boolean {
            val arr = runCatching { JSONObject(resultJson ?: "{}").optJSONArray("result") }.getOrNull() ?: return false
            for (i in 0 until arr.length()) {
                val w = arr.getJSONObject(i)
                if (w.optString("word") != "[unk]" && w.has("conf") && w.optDouble("conf") >= CONF) return true
            }
            return false
        }

        fun canRun(ctx: Context) = AppOptions.wakeBackground(ctx) &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            VoskModel.isReady(ctx, AppOptions.wakeLang(ctx))

        /** Desde la app (en primer plano): arrancar el servicio si la opción está activada. */
        fun ensure(ctx: Context) {
            if (!canRun(ctx)) { stop(ctx); return }
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, WakeWordService::class.java).setAction(ACTION_START)) }
                .onFailure { Log.w(TAG, "no se pudo iniciar: ${it.message}") }
        }

        fun stop(ctx: Context) {
            if (running) ctx.stopService(Intent(ctx, WakeWordService::class.java))
        }

        /** La app pasó a primer plano: soltar el micrófono. */
        fun appForeground(ctx: Context) = send(ctx, ACTION_PAUSE)

        /** La app pasó a segundo plano: volver a escuchar. */
        fun appBackground(ctx: Context) = send(ctx, ACTION_RESUME)

        fun refresh(ctx: Context) = send(ctx, ACTION_REFRESH)

        private fun send(ctx: Context, action: String) {
            if (!running) return
            runCatching { ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(action)) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var modelLang: String? = null
    private var speech: SpeechService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var paused = true
    private var loading = false

    // ── Batería ──
    // Por defecto solo escucha con la pantalla encendida (con ella apagada habría
    // que mantener el procesador despierto toda la noche). Además se detiene en
    // modo ahorro de batería y con poca batería.
    private var screenOn = true
    private var powerSave = false
    private var batteryLow = false

    private fun blockedReason(): String? = when {
        powerSave -> "ahorro de batería activado"
        batteryLow -> "batería baja"
        !screenOn && !AppOptions.wakeScreenOff(this) -> "pantalla apagada"
        else -> null
    }

    private val energy = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_BATTERY_LOW -> batteryLow = true
                Intent.ACTION_BATTERY_OKAY -> batteryLow = false
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> powerSave = getSystemService(PowerManager::class.java).isPowerSaveMode
            }
            reevaluate()
        }
    }

    /** Arranca o para la escucha según el estado (app abierta, pantalla, batería). */
    private fun reevaluate() {
        if (paused || blockedReason() != null) stopListening() else startListening()
        updateNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val pm = getSystemService(PowerManager::class.java)
        screenOn = pm.isInteractive
        powerSave = pm.isPowerSaveMode
        batteryLow = runCatching {
            val bm = getSystemService(android.os.BatteryManager::class.java)
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) in 1..15 && !bm.isCharging
        }.getOrDefault(false)
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_BATTERY_LOW); addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(this, energy, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Escucha de la palabra de activación", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_WAKE, "RUFFS te escucha", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE -> {
                AppOptions.setWakeBackground(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            // Solo en compilaciones de prueba: simular que se oyó la palabra.
            "com.ruffs.companion.wake.TEST" -> if (BuildConfig.DEBUG && speech != null) onWake()
            ACTION_PAUSE -> { pauseListening(); updateNotification() }
            ACTION_RESUME -> { paused = false; reevaluate() }
            ACTION_REFRESH -> { stopListening(); reevaluate() }
            else -> {
                // ACTION_START (la app está abierta: empieza en pausa y escucha al salir)
                if (!canRun(this)) { stopSelf(); return START_NOT_STICKY }
                try {
                    ServiceCompat.startForeground(
                        this, NOTIF_ID, buildNotification(),
                        if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "startForeground: ${e.message}")
                    stopSelf()
                    return START_NOT_STICKY
                }
                paused = NotifySync.appInForeground
                reevaluate()
            }
        }
        // Si Android lo cierra no se reinicia solo (sin la app delante no podría
        // usar el micrófono): vuelve a arrancar al abrir RUFFS.
        return START_NOT_STICKY
    }

    private fun startListening() {
        if (paused || speech != null || loading || blockedReason() != null) return
        val lang = AppOptions.wakeLang(this)
        val cached = model
        if (cached != null && modelLang == lang) { listenWith(cached); return }
        loading = true
        thread(name = "vosk-load") {
            val m = runCatching { Model(VoskModel.dir(this, lang).absolutePath) }.getOrNull()
            main.post {
                loading = false
                if (m == null) { Log.w(TAG, "no se pudo cargar el modelo $lang"); stopSelf(); return@post }
                model?.close()
                model = m
                modelLang = lang
                if (!paused) listenWith(m)
            }
        }
    }

    private fun listenWith(m: Model) {
        try {
            val words = grammar(AppOptions.wakeWord(this))
            val rec = Recognizer(m, 16000f, JSONArray(words).toString()).apply { setWords(true) }
            val s = SpeechService(rec, 16000f)
            s.startListening(listener)
            speech = s
            // Solo hace falta mantener el procesador despierto si escucha con la
            // pantalla apagada (opción aparte).
            if (AppOptions.wakeScreenOff(this)) acquireWakeLock()
            Log.d(TAG, "escuchando: $words")
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo escuchar: ${e.message}")
        }
    }

    private fun pauseListening() {
        paused = true
        stopListening()
    }

    private fun stopListening() {
        speech?.let { runCatching { it.stop(); it.shutdown() } }
        speech = null
        releaseWakeLock()
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) { if (isWake(hypothesis)) main.post { onWake() } }
        override fun onFinalResult(hypothesis: String?) { if (isWake(hypothesis)) main.post { onWake() } }
        override fun onError(exception: Exception?) {
            Log.w(TAG, "error: ${exception?.message}")
            main.post { stopListening(); main.postDelayed({ if (!paused) startListening() }, 2000) }
        }
        override fun onTimeout() { main.post { stopListening(); reevaluate() } }
    }

    /** Se oyó la palabra: soltar el micrófono y abrir RUFFS escuchando. */
    private fun onWake() {
        if (paused) return
        Log.d(TAG, "palabra de activación")
        pauseListening()
        vibrate()
        val open = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_LISTEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, 7, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Con «Mostrar sobre otras apps» Android deja abrir la app directamente.
        val direct = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)
        if (direct) {
            runCatching { startActivity(open) }
        }
        // Siempre también un aviso con pantalla completa: abre RUFFS sobre la
        // pantalla de bloqueo o, si no se pudo abrir solo, queda para tocarlo.
        val n = NotificationCompat.Builder(this, CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_stat_ruffs)
            .setColor(ContextCompat.getColor(this, R.color.ruffs_accent))
            .setContentTitle("Te escucho")
            .setContentText("Toca para hablar con RUFFS")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setTimeoutAfter(20_000)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_WAKE_ID, n) }
        if (direct) main.postDelayed({ runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_WAKE_ID) } }, 4000)
        // Si al final no se abrió la app, volver a escuchar en unos segundos.
        main.postDelayed({ if (!NotifySync.appInForeground && paused) { paused = false; reevaluate() } }, 20_000)
    }

    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(android.os.VibratorManager::class.java).defaultVibrator
                else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        runCatching { v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 8, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disable = PendingIntent.getService(
            this, 9, Intent(this, WakeWordService::class.java).setAction(ACTION_DISABLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val word = AppOptions.wakeWord(this).lowercase().replaceFirstChar { it.titlecase() }
        val blocked = blockedReason()
        val (title, text) = when {
            paused -> "RUFFS está abierto" to "La escucha sigue al salir de la app"
            blocked != null -> "Escucha en pausa" to "Por $blocked. Vuelve sola cuando cambie."
            else -> "Di «$word» para hablar con RUFFS" to "Escuchando en el teléfono, sin internet"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ruffs)
            .setColor(ContextCompat.getColor(this, R.color.ruffs_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Desactivar", disable)
            .build()
    }

    private fun updateNotification() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ruffs:wakeword").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(energy) }
        stopListening()
        model?.close()
        model = null
        running = false
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
