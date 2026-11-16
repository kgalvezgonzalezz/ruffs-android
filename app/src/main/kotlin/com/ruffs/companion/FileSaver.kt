package com.ruffs.companion

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Archivos que salen de RUFFS:
 *  - Blobs que la página envía por trozos (exportar documento, galería, zip…).
 *  - URLs del servidor, descargadas aquí con la cookie de sesión. El
 *    DownloadManager del sistema no lleva la cookie ni pasa por la VPN de
 *    Tailscale, que era la causa de los fallos en la app de Odysseus.
 * Se guardan en Descargas/RUFFS sin preguntar cada vez.
 */
class FileSaver(private val context: Context) {

    class Incoming(val name: String, val mime: String, val mode: String, val file: File, val out: OutputStream)

    class Downloaded(val file: File, val name: String, val mime: String)

    private val incoming = ConcurrentHashMap<String, Incoming>()

    private object NullOutput : OutputStream() {
        override fun write(b: Int) {}
    }

    private fun workDir(): File = File(context.cacheDir, "archivos").apply { mkdirs() }

    fun tempFile(name: String): File {
        val dir = File(workDir(), UUID.randomUUID().toString()).apply { mkdirs() }
        return File(dir, sanitize(name))
    }

    // ── Blobs por trozos ──────────────────────────────────────────────────────
    fun begin(id: String, name: String, mime: String, mode: String) {
        val fixedMime = betterMime(name, mime)
        val fixedName = withExtension(sanitize(name), fixedMime)
        val f = tempFile(fixedName)
        incoming[id] = Incoming(fixedName, fixedMime, mode, f, BufferedOutputStream(FileOutputStream(f)))
    }

    fun chunk(id: String, b64: String) {
        val inc = incoming[id] ?: return
        try {
            inc.out.write(Base64.decode(b64, Base64.DEFAULT))
        } catch (e: Exception) {
            abort(id)
            throw e
        }
    }

    fun end(id: String): Incoming? {
        val inc = incoming.remove(id) ?: return null
        inc.out.close()
        return inc
    }

    fun abort(id: String) {
        incoming.remove(id)?.let { runCatching { it.out.close() }; it.file.delete() }
    }

    /** Android.processBlob(base64, mime, nombre) que usa document.js de RUFFS. */
    fun fromBase64(b64: String, mime: String, nameOrDisposition: String): Incoming {
        val name = when {
            nameOrDisposition.contains("filename", ignoreCase = true) ->
                URLUtil.guessFileName(null, nameOrDisposition, mime)
            nameOrDisposition.isNotBlank() -> nameOrDisposition
            else -> "descarga"
        }
        val fixedMime = betterMime(name, mime)
        val fixedName = withExtension(sanitize(name), fixedMime)
        val clean = b64.substringAfter("base64,", b64)
        val f = tempFile(fixedName)
        f.writeBytes(Base64.decode(clean, Base64.DEFAULT))
        return Incoming(fixedName, fixedMime, "save", f, NullOutput)
    }

    // ── Descarga HTTP con la sesión de RUFFS ──────────────────────────────────
    @Throws(IOException::class)
    fun download(url: String, suggestedName: String?, userAgent: String?, contentDisposition: String?, mimeHint: String?): Downloaded {
        var conn = open(url, userAgent)
        var redirects = 0
        while (conn.responseCode in 300..399 && redirects < 5) {
            val next = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = open(URL(URL(url), next).toString(), userAgent)
            redirects++
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("El servidor respondió $code")
        }
        val cd = conn.getHeaderField("Content-Disposition") ?: contentDisposition
        val mime = (conn.contentType ?: mimeHint ?: "application/octet-stream").substringBefore(';').trim()
        val name = when {
            !suggestedName.isNullOrBlank() -> suggestedName
            else -> URLUtil.guessFileName(url, cd, mime)
        }
        val fixedMime = betterMime(name, mime)
        val fixedName = withExtension(sanitize(name), fixedMime)
        val f = tempFile(fixedName)
        conn.inputStream.use { input -> FileOutputStream(f).use { input.copyTo(it, 64 * 1024) } }
        conn.disconnect()
        return Downloaded(f, fixedName, fixedMime)
    }

    private fun open(url: String, userAgent: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "*/*")
            userAgent?.let { setRequestProperty("User-Agent", it) }
            CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
        }

    // ── Publicar en Descargas/RUFFS ───────────────────────────────────────────
    /** En Android 8–9 el llamador debe tener WRITE_EXTERNAL_STORAGE. */
    @Throws(IOException::class)
    fun publish(src: File, name: String, mime: String): Uri {
        try {
            return if (Build.VERSION.SDK_INT >= 29) publishMediaStore(src, name, mime) else publishLegacy(src, name, mime)
        } finally {
            src.delete()
            src.parentFile?.delete()
        }
    }

    private fun publishMediaStore(src: File, name: String, mime: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RUFFS")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("No se pudo crear el archivo en Descargas")
        try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out, 64 * 1024) } }
                ?: throw IOException("No se pudo escribir en Descargas")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw if (e is IOException) e else IOException(e)
        }
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(src: File, name: String, mime: String): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RUFFS").apply { mkdirs() }
        var target = File(dir, name)
        var n = 1
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isNotEmpty()) ".$it" else "" }
        while (target.exists()) target = File(dir, "$base ($n)$ext").also { n++ }
        src.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it, 64 * 1024) } }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", target)
    }

    /** Para abrir sin guardar (window.open de un blob, vista previa). */
    fun shareableUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun cleanOld() {
        val limit = System.currentTimeMillis() - 24 * 3600_000L
        workDir().listFiles()?.forEach { if (it.lastModified() < limit) it.deleteRecursively() }
    }

    // ── Nombres y tipos ───────────────────────────────────────────────────────
    private fun sanitize(name: String): String {
        val n = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim().trimStart('.')
        return n.take(120).ifBlank { "descarga" }
    }

    private fun betterMime(name: String, mime: String?): String {
        val m = mime?.substringBefore(';')?.trim()?.lowercase()
        if (!m.isNullOrBlank() && m != "application/octet-stream" && m != "binary/octet-stream") return m
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun withExtension(name: String, mime: String): String {
        if (name.contains('.') && name.substringAfterLast('.').length in 1..8) return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: return name
        return "$name.$ext"
    }
}
