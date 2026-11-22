package com.ruffs.companion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Modelos de voz sin conexión (Vosk, los mismos que usa RUFFS en el PC).
 * Se descargan una sola vez (~40 MB) al activar «Escuchar con la app cerrada».
 */
object VoskModel {
    private const val BASE = "https://alphacephei.com/vosk/models/"
    val MODELS = mapOf(
        "es" to ("vosk-model-small-es-0.42" to 39_817_833L),
        "en" to ("vosk-model-small-en-us-0.15" to 41_205_931L),
        "pt" to ("vosk-model-small-pt-0.3" to 32_453_112L),
    )

    fun defaultLang(): String = Locale.getDefault().language.takeIf { it in MODELS } ?: "es"

    fun dir(ctx: Context, lang: String): File = File(ctx.filesDir, "vosk/" + MODELS.getValue(lang).first)

    fun isReady(ctx: Context, lang: String) = File(dir(ctx, lang), "conf/model.conf").isFile

    fun sizeMb(lang: String) = (MODELS.getValue(lang).second / 1_000_000).toInt()

    /** Descarga y descomprime el modelo; [progress] recibe 0..100. */
    suspend fun download(ctx: Context, lang: String, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val (name, size) = MODELS.getValue(lang)
        val root = File(ctx.filesDir, "vosk").apply { mkdirs() }
        val zip = File(ctx.cacheDir, "$name.zip")
        val conn = (URL("$BASE$name.zip").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: size
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = (done * 90 / total).toInt().coerceIn(0, 90)
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val tmp = File(root, "$name.tmp").apply { deleteRecursively(); mkdirs() }
        val canonical = tmp.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(tmp, e.name)
                if (!out.canonicalPath.startsWith(canonical)) throw SecurityException("Ruta inválida en el zip")
                if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
            }
        }
        zip.delete()
        val inner = File(tmp, name).takeIf { it.isDirectory } ?: tmp
        val target = dir(ctx, lang)
        target.deleteRecursively()
        if (!inner.renameTo(target)) throw IllegalStateException("No se pudo guardar el modelo")
        tmp.deleteRecursively()
        progress(100)
        if (!isReady(ctx, lang)) throw IllegalStateException("El modelo descargado está incompleto")
    }
}
