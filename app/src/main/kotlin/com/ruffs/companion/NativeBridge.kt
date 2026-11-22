package com.ruffs.companion

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Objeto que ve JavaScript como `window.RuffsNative` (y `window.Android`, el
 * nombre que ya usa document.js de RUFFS para guardar exportaciones).
 *
 * Los métodos se llaman desde un hilo del WebView; todo lo que toca la interfaz
 * se pasa al hilo principal. Cada llamada comprueba que la página sea de tu
 * servidor RUFFS antes de hacer nada.
 */
class NativeBridge(private val host: MainActivity, private val web: WebView) {

    @Volatile var pageUrl: String? = null
    @Volatile var destroyed = false

    private fun trusted(): Boolean = !destroyed && host.isTrusted(pageUrl)

    // ── Android → JS ──────────────────────────────────────────────────────────
    fun emit(channel: String, json: String) {
        host.runOnUiThread {
            if (destroyed) return@runOnUiThread
            web.evaluateJavascript("window.__ruffs&&window.__ruffs.emit(${JSONObject.quote(channel)},$json)", null)
        }
    }

    fun resolve(id: String, ok: Boolean, json: String) {
        host.runOnUiThread {
            if (destroyed) return@runOnUiThread
            web.evaluateJavascript("window.__ruffs&&window.__ruffs.resolve(${JSONObject.quote(id)},$ok,$json)", null)
        }
    }

    private fun emitRec(id: String, ev: String, extra: JSONObject.() -> Unit = {}) =
        emit("rec", JSONObject().put("id", id).put("ev", ev).apply(extra).toString())

    private fun emitSr(id: String, ev: String, extra: JSONObject.() -> Unit = {}) =
        emit("sr", JSONObject().put("id", id).put("ev", ev).apply(extra).toString())

    // ── Portapapeles, vibración, compartir ────────────────────────────────────
    @JavascriptInterface
    fun copyText(text: String) {
        if (!trusted()) return
        host.runOnUiThread {
            val cm = host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("RUFFS", text))
        }
    }

    @JavascriptInterface
    fun readClipboard(id: String) {
        if (!trusted()) { resolve(id, false, JSONObject.quote("no permitido")); return }
        host.runOnUiThread {
            val cm = host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(host)?.toString().orEmpty()
            resolve(id, true, JSONObject.quote(text))
        }
    }

    @JavascriptInterface
    fun vibrate(patternJson: String) {
        if (!trusted()) return
        val arr = runCatching { JSONArray(patternJson) }.getOrNull() ?: return
        host.vibrate(LongArray(arr.length()) { arr.optLong(it).coerceIn(0, 10_000) })
    }

    @JavascriptInterface
    fun share(title: String, text: String, url: String) {
        if (!trusted()) return
        host.runOnUiThread {
            val body = listOf(text, url).filter { it.isNotBlank() }.joinToString("\n")
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, body)
                if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
            }
            runCatching { host.startActivity(Intent.createChooser(send, title.ifBlank { null })) }
        }
    }

    // ── Notificaciones ────────────────────────────────────────────────────────
    @JavascriptInterface
    fun notificationPermission(): String = host.notificationPermissionState()

    @JavascriptInterface
    fun requestNotificationPermission(id: String) {
        if (!trusted()) { resolve(id, true, JSONObject.quote("denied")); return }
        host.runOnUiThread { host.requestNotificationPermission { state -> resolve(id, true, JSONObject.quote(state)) } }
    }

    @JavascriptInterface
    fun notify(id: String, title: String, body: String, tag: String) {
        if (!trusted()) return
        host.notifier.showWeb(id, title, body, tag)
    }

    @JavascriptInterface
    fun cancelNotification(id: String, tag: String) {
        host.notifier.cancelWeb(id, tag)
    }

    // ── Síntesis de voz ───────────────────────────────────────────────────────
    @JavascriptInterface
    fun ttsVoices(): String = host.tts.voicesJson()

    @JavascriptInterface
    fun ttsSpeak(id: String, text: String, lang: String, voiceId: String, rate: Double, pitch: Double, volume: Double) {
        if (!trusted()) return
        host.owners[id] = this
        host.tts.speak(id, text, lang, voiceId, rate, pitch, volume)
    }

    @JavascriptInterface
    fun ttsCancel() {
        host.tts.cancel()
    }

    // ── Reconocimiento de voz ─────────────────────────────────────────────────
    @JavascriptInterface
    fun srStart(id: String, lang: String, continuous: Boolean, interim: Boolean, maxAlternatives: Int) {
        if (!trusted()) {
            emitSr(id, "start"); emitSr(id, "error") { put("error", "not-allowed") }; emitSr(id, "end")
            return
        }
        host.owners[id] = this
        host.runOnUiThread {
            host.withPermission(Manifest.permission.RECORD_AUDIO) { granted ->
                if (granted) {
                    host.speech.start(id, lang, continuous, interim, maxAlternatives)
                } else {
                    emitSr(id, "start"); emitSr(id, "error") { put("error", "not-allowed") }; emitSr(id, "end")
                    host.owners.remove(id)
                }
            }
        }
    }

    @JavascriptInterface
    fun srStop(id: String) { host.speech.stop(id) }

    @JavascriptInterface
    fun srAbort(id: String) { host.speech.abort(id) }

    // ── Micrófono / grabación ─────────────────────────────────────────────────
    @JavascriptInterface
    fun requestMic(id: String) {
        if (!trusted()) { resolve(id, false, JSONObject.quote("no permitido")); return }
        host.runOnUiThread {
            host.withPermission(Manifest.permission.RECORD_AUDIO) { granted ->
                resolve(id, granted, if (granted) "true" else JSONObject.quote("denegado"))
            }
        }
    }

    @JavascriptInterface
    fun recStart(id: String) {
        if (!trusted()) return
        host.runOnUiThread {
            host.withPermission(Manifest.permission.RECORD_AUDIO) { granted ->
                if (!granted) {
                    emitRec(id, "error") { put("message", "Permiso de micrófono denegado") }
                    return@withPermission
                }
                try {
                    host.recorder.start(id)
                    emitRec(id, "start")
                } catch (e: Exception) {
                    emitRec(id, "error") { put("message", e.message ?: "No se pudo grabar") }
                }
            }
        }
    }

    @JavascriptInterface
    fun recStop(id: String) {
        host.io {
            val rec = host.recorder.stop(id)
            if (rec.bytes.isNotEmpty()) {
                emitRec(id, "data") {
                    put("mime", rec.mime)
                    put("b64", Base64.encodeToString(rec.bytes, Base64.NO_WRAP))
                }
            }
            emitRec(id, "stop")
        }
    }

    @JavascriptInterface
    fun recPause(id: String) { host.recorder.pause(id) }

    @JavascriptInterface
    fun recResume(id: String) { host.recorder.resume(id) }

    // ── Archivos ──────────────────────────────────────────────────────────────
    @JavascriptInterface
    fun fileBegin(id: String, name: String, mime: String, size: Double, mode: String) {
        if (!trusted()) return
        try {
            host.saver.begin(id, name, mime, mode)
        } catch (e: Exception) {
            host.toast("No se pudo preparar el archivo: ${e.message}")
        }
    }

    @JavascriptInterface
    fun fileChunk(id: String, b64: String) {
        try {
            host.saver.chunk(id, b64)
        } catch (e: Exception) {
            host.toast("Error al guardar: ${e.message}")
        }
    }

    @JavascriptInterface
    fun fileEnd(id: String) {
        val inc = host.saver.end(id) ?: return
        host.io { host.deliverFile(inc.file, inc.name, inc.mime, inc.mode) }
    }

    @JavascriptInterface
    fun fileAbort(id: String, message: String) {
        host.saver.abort(id)
        host.toast("No se pudo leer el archivo: $message")
    }

    /**
     * Compatibilidad con RUFFS: document.js llama Android.processBlob(base64, mime, nombre).
     * La app de Odysseus trataba el tercer argumento como Content-Disposition y
     * los archivos salían con un nombre equivocado.
     */
    @JavascriptInterface
    fun processBlob(base64Data: String, mimeType: String, nameOrDisposition: String) {
        if (!trusted()) return
        host.io {
            try {
                val inc = host.saver.fromBase64(base64Data, mimeType, nameOrDisposition)
                host.deliverFile(inc.file, inc.name, inc.mime, "save")
            } catch (e: Exception) {
                host.toast("No se pudo guardar: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun downloadUrl(url: String, fileName: String) {
        if (!trusted()) return
        host.runOnUiThread { host.downloadUrl(url, fileName.ifBlank { null }, null, null) }
    }

    // ── Interfaz ──────────────────────────────────────────────────────────────
    @JavascriptInterface
    fun themeColor(css: String) {
        if (!trusted()) return
        val c = CssColor.parse(css) ?: return
        host.runOnUiThread { host.onThemeColor(web, c) }
    }

    @JavascriptInterface
    fun themeAccents(accent1: String, accent2: String) {
        if (!trusted()) return
        val a1 = CssColor.parse(accent1) ?: return
        val a2 = CssColor.parse(accent2) ?: a1
        host.runOnUiThread { host.onThemeAccents(web, a1, a2) }
    }

    /** Al tocar: ¿se puede tirar para recargar desde ahí? (se llama antes de que Android decida) */
    @JavascriptInterface
    fun setCanRefresh(allowed: Boolean) {
        host.setPagePullAllowed(web, allowed)
    }

    /** RUFFS empezó (o terminó) un arrastre: la recarga no debe quedarse con el gesto. */
    @JavascriptInterface
    fun setDragging(dragging: Boolean) {
        host.setPageDragging(web, dragging)
    }

    @JavascriptInterface
    fun openSettings() {
        if (!trusted()) return
        host.runOnUiThread { host.openSettings() }
    }

    /** Opciones de la app (avisos, voz con la app cerrada, bloqueo, actualizaciones). */
    @JavascriptInterface
    fun openOptions() {
        if (!trusted()) return
        host.runOnUiThread { host.openOptions() }
    }

    @JavascriptInterface
    fun appInfo(): String = JSONObject()
        .put("app", "RUFFS Android")
        .put("version", BuildConfig.VERSION_NAME)
        .put("server", host.currentServer() ?: JSONObject.NULL)
        .toString()
}

/** Colores CSS que usa RUFFS: #rgb, #rrggbb, #rrggbbaa, rgb()/rgba(). */
object CssColor {
    fun parse(input: String?): Int? {
        val s = input?.trim()?.trim('"')?.lowercase() ?: return null
        try {
            if (s.startsWith("#")) {
                val h = s.substring(1)
                return when (h.length) {
                    3 -> Color.rgb(hex(h[0], h[0]), hex(h[1], h[1]), hex(h[2], h[2]))
                    6 -> Color.rgb(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
                    8 -> Color.rgb(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
                    else -> null
                }
            }
            if (s.startsWith("rgb")) {
                val nums = s.substringAfter('(').substringBefore(')')
                    .split(',', ' ', '/').map { it.trim() }.filter { it.isNotEmpty() }
                if (nums.size < 3) return null
                val alpha = nums.getOrNull(3)?.removeSuffix("%")?.toFloatOrNull()
                if (alpha != null && alpha == 0f) return null // transparente: no sirve para la barra
                return Color.rgb(channel(nums[0]), channel(nums[1]), channel(nums[2]))
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun hex(a: Char, b: Char) = "$a$b".toInt(16)

    private fun channel(v: String): Int =
        if (v.endsWith("%")) (v.removeSuffix("%").toFloat() * 2.55f).toInt().coerceIn(0, 255)
        else v.toFloat().toInt().coerceIn(0, 255)
}
