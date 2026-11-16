package com.ruffs.companion

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONArray
import org.json.JSONObject

/**
 * SpeechRecognition de la web → SpeechRecognizer de Android.
 *
 * El reconocedor de Android escucha una sola frase; para el modo `continuous`
 * (dictado largo y palabra de activación "RUFFS") se vuelve a lanzar solo,
 * acumulando resultados en la misma sesión, como hace Chrome.
 * Todo se ejecuta en el hilo principal (lo exige SpeechRecognizer).
 */
class SpeechManager(
    private val context: Context,
    private val emit: (id: String, data: JSONObject) -> Unit,
    private val beforeListen: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private var currentId: String? = null
    private var lang = ""
    private var continuous = false
    private var interim = false
    private var maxAlternatives = 1
    private var stopping = false
    private var stopTimeout: Runnable? = null

    fun start(id: String, lang: String, continuous: Boolean, interim: Boolean, maxAlternatives: Int) = main.post {
        currentId?.let { finish(it) }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            emit(id, ev("start"))
            emit(id, ev("error").put("error", "service-not-allowed").put("message", "Este teléfono no tiene servicio de reconocimiento de voz"))
            emit(id, ev("end"))
            return@post
        }
        currentId = id
        this.lang = lang
        this.continuous = continuous
        this.interim = interim
        this.maxAlternatives = maxAlternatives.coerceIn(1, 5)
        stopping = false
        emit(id, ev("start"))
        listen()
    }

    fun stop(id: String) = main.post {
        if (id != currentId) return@post
        stopping = true
        val r = recognizer
        if (r == null) { finish(id); return@post }
        r.stopListening()
        // Si el servicio no devuelve onResults, cerramos igual.
        stopTimeout = Runnable { if (currentId == id) finish(id) }.also { main.postDelayed(it, 1500) }
    }

    fun abort(id: String) = main.post {
        if (id != currentId) return@post
        recognizer?.cancel()
        emit(id, ev("error").put("error", "aborted"))
        finish(id)
    }

    fun destroy() = main.post {
        currentId?.let { finish(it) }
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        beforeListen()
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxAlternatives)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (lang.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            if (continuous) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            }
        }
        r.startListening(intent)
    }

    private fun restartLater(delay: Long = 120) {
        val id = currentId ?: return
        main.postDelayed({ if (currentId == id && !stopping) listen() }, delay)
    }

    private fun finish(id: String) {
        stopTimeout?.let { main.removeCallbacks(it) }
        stopTimeout = null
        if (currentId == id) currentId = null
        recognizer?.destroy()
        recognizer = null
        emit(id, ev("end"))
    }

    private fun ev(name: String) = JSONObject().put("ev", name)

    private fun results(b: Bundle?, final: Boolean): JSONObject? {
        val texts = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return null
        if (texts.isEmpty() || texts[0].isNullOrBlank()) return null
        val conf = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        val alts = JSONArray()
        texts.take(maxAlternatives).forEachIndexed { i, t ->
            val c = conf?.getOrNull(i)?.takeIf { it >= 0 } ?: if (final) 0.9f else 0.5f
            alts.put(JSONObject().put("t", t).put("c", c.toDouble()))
        }
        return ev("result").put("final", final).put("alts", alts)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { currentId?.let { emit(it, ev("speechstart")) } }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { currentId?.let { emit(it, ev("speechend")) } }

        override fun onPartialResults(partialResults: Bundle?) {
            val id = currentId ?: return
            if (!interim) return
            results(partialResults, final = false)?.let { emit(id, it) }
        }

        override fun onResults(res: Bundle?) {
            val id = currentId ?: return
            results(res, final = true)?.let { emit(id, it) }
            if (continuous && !stopping) restartLater() else finish(id)
        }

        override fun onError(error: Int) {
            val id = currentId ?: return
            val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            when {
                stopping -> finish(id)
                continuous && silence -> restartLater()
                continuous && error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restartLater(400)
                else -> {
                    val code = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                        SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_SERVER -> "network"
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language-not-supported"
                        else -> "aborted"
                    }
                    if (error == SpeechRecognizer.ERROR_NO_MATCH) emit(id, ev("nomatch"))
                    emit(id, ev("error").put("error", code))
                    finish(id)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
