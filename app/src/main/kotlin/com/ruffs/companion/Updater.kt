package com.ruffs.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Actualizar la app desde la propia app (como RUFFS en el PC): mira la última
 * versión publicada en GitHub (kgalvezgonzalezz/ruffs-android), y si hay una
 * nueva la descarga, comprueba que es íntegra y que está firmada con la misma
 * clave que la instalada, y abre el instalador de Android.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/kgalvezgonzalezz/ruffs-android/releases/latest"
    private const val ASSET = "RUFFS.apk"
    private const val EVERY_MS = 12 * 3600 * 1000L

    data class Release(val version: String, val notes: String, val url: String, val size: Long, val sha256: String?)

    /** «v1.2.3» → [1,2,3]; compara número a número. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "RUFFS-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode != 200) return@withContext null
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = j.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == ASSET) {
                    return@withContext Release(
                        version = j.optString("tag_name").removePrefix("v"),
                        notes = j.optString("body").take(1500),
                        url = a.optString("browser_download_url"),
                        size = a.optLong("size"),
                        sha256 = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                    )
                }
            }
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Al abrir la app, como mucho cada 12 h. */
    suspend fun checkInBackground(activity: Activity) {
        if (!AppOptions.updateCheck(activity)) return
        val now = System.currentTimeMillis()
        if (now - AppOptions.lastUpdateCheck(activity) < EVERY_MS) return
        AppOptions.setLastUpdateCheck(activity, now)
        val r = runCatching { latest() }.getOrNull() ?: return
        if (!isNewer(r.version, BuildConfig.VERSION_NAME) || AppOptions.skippedVersion(activity) == r.version) return
        withContext(Dispatchers.Main) { if (!activity.isFinishing) offer(activity, r) }
    }

    /** Botón «Buscar actualizaciones» de Opciones. */
    suspend fun checkNow(activity: Activity, onNone: (String) -> Unit) {
        val r = runCatching { latest() }.getOrNull()
        withContext(Dispatchers.Main) {
            when {
                r == null -> onNone("No se pudo consultar GitHub. Revisa la conexión.")
                !isNewer(r.version, BuildConfig.VERSION_NAME) -> onNone("Tienes la última versión (${BuildConfig.VERSION_NAME}).")
                else -> offer(activity, r)
            }
        }
    }

    fun offer(activity: Activity, r: Release) {
        AlertDialog.Builder(activity)
            .setTitle("RUFFS ${r.version} disponible")
            .setMessage(
                "Tienes la ${BuildConfig.VERSION_NAME}. La nueva pesa ${r.size / 1_000_000} MB.\n\n" +
                    r.notes.replace(Regex("[#*`]"), "").trim().take(700),
            )
            .setPositiveButton("Actualizar") { _, _ -> (activity as? MainActivity)?.startUpdate(r) ?: startStandalone(activity, r) }
            .setNeutralButton("Ahora no", null)
            .setNegativeButton("Saltar esta versión") { _, _ -> AppOptions.setSkippedVersion(activity, r.version) }
            .show()
    }

    private fun startStandalone(activity: Activity, r: Release) {
        activity.startActivity(Intent(activity, MainActivity::class.java).setAction(MainActivity.ACTION_UPDATE))
    }

    /** Descarga a la caché con progreso 0..100 y comprueba integridad y firma. */
    suspend fun download(ctx: Context, r: Release, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "RUFFS-${r.version}.apk")
        var url = URL(r.url)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {   // GitHub redirige a su CDN
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "RUFFS-Android/${BuildConfig.VERSION_NAME}")
            }
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue
            }
            if (code != 200) { conn.disconnect(); throw IllegalStateException("HTTP $code") }
            break
        }
        val md = MessageDigest.getInstance("SHA-256")
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.size
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L; var last = -1
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); md.update(buf, 0, n); done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (r.sha256 != null && !hex.equals(r.sha256, ignoreCase = true)) {
            out.delete(); throw SecurityException("La descarga no coincide con la publicada")
        }
        if (!sameSigner(ctx, out)) {
            out.delete(); throw SecurityException("La versión descargada no está firmada con la misma clave que esta app")
        }
        out
    }

    @Suppress("DEPRECATION")
    private fun sameSigner(ctx: Context, apk: File): Boolean {
        val pm = ctx.packageManager
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flag) ?: return false
        if (archive.packageName != ctx.packageName) return false
        val installed = pm.getPackageInfo(ctx.packageName, flag)
        fun sigs(p: android.content.pm.PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners?.toList() else p.signatures?.toList())
                .orEmpty().map { it.toCharsString() }.toSet()
        val a = sigs(archive); val b = sigs(installed)
        return a.isNotEmpty() && a == b
    }

    /** Abre el instalador de Android (pide «instalar apps desconocidas» la primera vez). */
    fun install(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
