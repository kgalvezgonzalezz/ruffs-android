package com.ruffs.companion

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Notificaciones de Android para:
 *  - `new Notification(...)` de la web (recordatorios, tareas, investigaciones,
 *    "respuesta completa"…). Al tocarlas se abre la app y se avisa a la página.
 *  - descargas terminadas (al tocarlas se abre el archivo).
 */
class WebNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_WEB = "ruffs_web"
        const val CHANNEL_DOWNLOADS = "ruffs_downloads"
        const val EXTRA_NOTIFICATION_ID = "ruffs_notification_id"
    }

    private val nm = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val sys = context.getSystemService(NotificationManager::class.java)
            sys.createNotificationChannel(
                NotificationChannel(CHANNEL_WEB, context.getString(R.string.channel_web), NotificationManager.IMPORTANCE_HIGH),
            )
            sys.createNotificationChannel(
                NotificationChannel(CHANNEL_DOWNLOADS, context.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    fun permissionGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            nm.areNotificationsEnabled()
        }

    private fun notificationId(webId: String, tag: String) = (if (tag.isNotBlank()) "tag:$tag" else webId).hashCode()

    fun showWeb(webId: String, title: String, body: String, tag: String) {
        if (!permissionGranted()) return
        val id = notificationId(webId, tag)
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_ID, webId)
        }
        val pi = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL_WEB)
            .setSmallIcon(R.drawable.ic_stat_ruffs)
            .setColor(ContextCompat.getColor(context, R.color.ruffs_accent))
            .setContentTitle(title.ifBlank { "RUFFS" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { nm.notify(id, n) } catch (_: SecurityException) {}
    }

    fun cancelWeb(webId: String, tag: String) = nm.cancel(notificationId(webId, tag))

    fun showDownload(name: String, uri: Uri, mime: String) {
        if (!permissionGranted()) return
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(context, uri.hashCode(), view, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setColor(ContextCompat.getColor(context, R.color.ruffs_accent))
            .setContentTitle(name)
            .setContentText("Guardado en Descargas/RUFFS · toca para abrir")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { nm.notify(uri.hashCode(), n) } catch (_: SecurityException) {}
    }
}
