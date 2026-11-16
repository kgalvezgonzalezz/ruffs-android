package com.ruffs.companion

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Graba audio con el micrófono para las notas de voz y el dictado de RUFFS
 * (voiceRecorder.js espera audio/webm y lo envía a /api/stt/transcribe).
 * Se prueba WebM/Opus, luego Ogg/Opus y por último MP4/AAC.
 */
class NativeRecorder(private val context: Context) {

    class Recording(val mime: String, val bytes: ByteArray)

    private data class Format(val output: Int, val encoder: Int, val mime: String, val ext: String, val rate: Int)

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var mime = "audio/webm"
    private var currentId: String? = null
    /** Si el reconocimiento de voz necesitó el micrófono, guardamos lo grabado. */
    private var yielded: Recording? = null

    private fun formats(): List<Format> {
        val list = ArrayList<Format>()
        if (Build.VERSION.SDK_INT >= 29) {
            list += Format(MediaRecorder.OutputFormat.WEBM, MediaRecorder.AudioEncoder.OPUS, "audio/webm", "webm", 48000)
            list += Format(MediaRecorder.OutputFormat.OGG, MediaRecorder.AudioEncoder.OPUS, "audio/ogg", "ogg", 48000)
        }
        list += Format(MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.AAC, "audio/mp4", "m4a", 44100)
        return list
    }

    @Synchronized
    fun start(id: String) {
        release()
        yielded = null
        var lastError: Exception? = null
        for (f in formats()) {
            val out = File(context.cacheDir, "voz_$id.${f.ext}")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            try {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(f.output)
                r.setAudioEncoder(f.encoder)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(f.rate)
                r.setAudioEncodingBitRate(64000)
                r.setOutputFile(out.absolutePath)
                r.prepare()
                r.start()
                recorder = r; file = out; mime = f.mime; currentId = id
                return
            } catch (e: Exception) {
                lastError = e
                runCatching { r.release() }
                out.delete()
            }
        }
        throw IllegalStateException("No se pudo abrir el micrófono", lastError)
    }

    @Synchronized
    fun pause(id: String) { if (id == currentId) runCatching { recorder?.pause() } }

    @Synchronized
    fun resume(id: String) { if (id == currentId) runCatching { recorder?.resume() } }

    /** Detiene y devuelve el audio (vacío si fue demasiado corto). */
    @Synchronized
    fun stop(id: String): Recording {
        if (id == currentId && recorder != null) return finishCurrent()
        val saved = yielded
        yielded = null
        if (id == currentId) currentId = null
        return saved ?: Recording(mime, ByteArray(0))
    }

    /** El reconocedor de voz de Android pide el micrófono: soltarlo sin perder lo grabado. */
    @Synchronized
    fun yieldMic() {
        if (recorder == null) return
        val id = currentId
        yielded = finishCurrent()
        currentId = id
    }

    private fun finishCurrent(): Recording {
        val r = recorder
        val f = file
        recorder = null; file = null; currentId = null
        if (r != null) {
            runCatching { r.stop() } // lanza si no llegó a grabar nada
            runCatching { r.release() }
        }
        val bytes = f?.takeIf { it.exists() }?.readBytes() ?: ByteArray(0)
        f?.delete()
        return Recording(mime, bytes)
    }

    @Synchronized
    fun release() {
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        recorder = null
        file?.delete()
        file = null
        currentId = null
    }
}
