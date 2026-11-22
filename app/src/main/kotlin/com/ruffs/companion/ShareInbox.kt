package com.ruffs.companion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * «Compartir → RUFFS» desde cualquier app: texto, enlaces, fotos, PDF…
 *
 * Los archivos se copian a la caché de la app y la página los pide en
 * `/__ruffs_share/<token>/<n>` (misma dirección que RUFFS, la app responde sin
 * ir a la red). La página abre un chat nuevo con el texto y los adjuntos listos
 * para que la persona escriba qué quiere y lo envíe.
 */
object ShareInbox {
    const val PATH = "/__ruffs_share/"
    private const val MAX_FILES = 10
    private const val MAX_BYTES = 200L * 1024 * 1024

    data class Item(val file: File, val name: String, val mime: String)

    private val served = ConcurrentHashMap<String, List<Item>>()

    fun isShare(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE

    /** Copia lo compartido (en un hilo de fondo) y devuelve el JSON para la página. */
    fun take(ctx: Context, intent: Intent): JSONObject? {
        val uris = ArrayList<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            streamList(intent)?.let { uris.addAll(it) }
        } else {
            stream(intent)?.let { uris.add(it) }
        }
        intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { if (it !in uris) uris.add(it) } }

        val text = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() },
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() },
        ).distinct().joinToString("\n").trim()

        cleanOld(ctx)
        val token = UUID.randomUUID().toString().replace("-", "")
        val dir = File(ctx.cacheDir, "share/$token").apply { mkdirs() }
        val items = ArrayList<Item>()
        var skipped = 0
        for (uri in uris.take(MAX_FILES)) {
            try {
                val name = displayName(ctx, uri) ?: "archivo_${items.size + 1}"
                val mime = ctx.contentResolver.getType(uri)
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                val out = File(dir, "${items.size}_" + name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
                var total = 0L
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IllegalStateException("demasiado grande")
                            o.write(buf, 0, n)
                        }
                    }
                } ?: throw IllegalStateException("no se pudo abrir")
                items.add(Item(out, name, mime))
            } catch (e: Exception) {
                android.util.Log.w("RUFFS-share", "no se pudo leer $uri: ${e.message}")
                skipped++
            }
        }
        skipped += (uris.size - MAX_FILES).coerceAtLeast(0)
        if (text.isEmpty() && items.isEmpty()) return null
        served[token] = items
        return JSONObject()
            .put("text", text)
            .put("skipped", skipped)
            .put("files", JSONArray().apply {
                items.forEachIndexed { i, it ->
                    put(JSONObject().put("url", "$PATH$token/$i").put("name", it.name).put("mime", it.mime).put("size", it.file.length()))
                }
            })
    }

    /** Responde a la página cuando pide un archivo compartido. */
    fun serve(path: String): WebResourceResponse? {
        if (!path.startsWith(PATH)) return null
        val parts = path.removePrefix(PATH).split('/')
        val item = parts.getOrNull(1)?.toIntOrNull()?.let { served[parts[0]]?.getOrNull(it) }
            ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())
        return WebResourceResponse(item.mime, null, 200, "OK",
            mapOf("Cache-Control" to "no-store", "Content-Length" to item.file.length().toString()), item.file.inputStream())
    }

    private fun cleanOld(ctx: Context) {
        val root = File(ctx.cacheDir, "share")
        val limit = System.currentTimeMillis() - 24 * 3600 * 1000L
        root.listFiles()?.forEach { if (it.lastModified() < limit) { served.remove(it.name); it.deleteRecursively() } }
    }

    private fun displayName(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamList(intent: Intent): List<Uri>? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
}
