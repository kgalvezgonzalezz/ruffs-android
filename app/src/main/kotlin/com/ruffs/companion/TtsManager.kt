package com.ruffs.companion

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.Locale

/**
 * speechSynthesis de la web → TextToSpeech de Android.
 * Los textos largos se trocean (Android limita la longitud por enunciado) y se
 * informa "start" con el primer trozo y "end" con el último.
 */
class TtsManager(
    context: Context,
    private val onEvent: (id: String, ev: String, error: String?) -> Unit,
    private val onVoicesChanged: () -> Unit,
) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false
    private val queuedBeforeReady = ArrayList<() -> Unit>()
    private val active = Collections.synchronizedSet(LinkedHashSet<String>())

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            synchronized(queuedBeforeReady) { queuedBeforeReady.clear() }
            return
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                val (id, index, _) = parse(utteranceId) ?: return
                if (index == 0) onEvent(id, "start", null)
            }

            override fun onDone(utteranceId: String) {
                val (id, index, total) = parse(utteranceId) ?: return
                if (index == total - 1 && active.remove(id)) onEvent(id, "end", null)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                val (id, _, _) = parse(utteranceId) ?: return
                if (active.remove(id)) onEvent(id, "error", "synthesis-failed")
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                val (id, _, _) = parse(utteranceId) ?: return
                if (active.remove(id)) onEvent(id, "error", if (errorCode == TextToSpeech.ERROR_NETWORK) "network" else "synthesis-failed")
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                val (id, _, _) = parse(utteranceId) ?: return
                if (active.remove(id)) onEvent(id, "end", null)
            }
        })
        ready = true
        val pending = synchronized(queuedBeforeReady) { queuedBeforeReady.toList().also { queuedBeforeReady.clear() } }
        pending.forEach { it() }
        onVoicesChanged()
    }

    private fun parse(utteranceId: String): Triple<String, Int, Int>? {
        val p = utteranceId.split('|')
        if (p.size != 3) return null
        return Triple(p[0], p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null)
    }

    private fun whenReady(block: () -> Unit) {
        if (ready) block() else synchronized(queuedBeforeReady) { queuedBeforeReady.add(block) }
    }

    fun voicesJson(): String {
        if (!ready) return "[]"
        val arr = JSONArray()
        val def = runCatching { tts.defaultVoice?.name }.getOrNull()
        val voices = runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
        for (v in voices) {
            arr.put(
                JSONObject()
                    .put("id", v.name)
                    .put("name", label(v))
                    .put("lang", v.locale.toLanguageTag())
                    .put("default", v.name == def)
                    .put("local", !v.isNetworkConnectionRequired),
            )
        }
        return arr.toString()
    }

    private fun label(v: Voice): String {
        val loc = v.locale
        val lang = loc.getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        val country = loc.getDisplayCountry(Locale.getDefault())
        val where = if (country.isNotBlank()) "$lang ($country)" else lang
        return "$where – ${v.name}"
    }

    fun speak(id: String, text: String, lang: String, voiceId: String, rate: Double, pitch: Double, volume: Double) {
        whenReady {
            val chunks = split(text)
            if (chunks.isEmpty()) {
                onEvent(id, "start", null); onEvent(id, "end", null); return@whenReady
            }
            runCatching {
                val voice = if (voiceId.isNotBlank()) tts.voices?.firstOrNull { it.name == voiceId } else null
                when {
                    voice != null -> tts.voice = voice
                    lang.isNotBlank() -> tts.language = Locale.forLanguageTag(lang)
                }
            }
            tts.setSpeechRate(rate.toFloat().coerceIn(0.1f, 4f))
            tts.setPitch(pitch.toFloat().coerceIn(0.1f, 4f))
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.toFloat().coerceIn(0f, 1f))
            }
            active.add(id)
            chunks.forEachIndexed { i, c ->
                val r = tts.speak(c, TextToSpeech.QUEUE_ADD, params, "$id|$i|${chunks.size}")
                if (r == TextToSpeech.ERROR && active.remove(id)) {
                    onEvent(id, "error", "synthesis-failed")
                    return@whenReady
                }
            }
        }
    }

    /** Corta en frases sin pasar del máximo que acepta el motor. */
    private fun split(text: String): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        val max = (TextToSpeech.getMaxSpeechInputLength() - 50).coerceAtLeast(500)
        if (clean.length <= max) return listOf(clean)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (sentence in clean.split(Regex("(?<=[.!?¡¿;:\\n])\\s+"))) {
            var s = sentence
            while (s.length > max) {
                if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
                out.add(s.substring(0, max)); s = s.substring(max)
            }
            if (sb.length + s.length + 1 > max) { out.add(sb.toString()); sb.clear() }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    fun cancel() {
        if (!ready) return
        val ids = synchronized(active) { active.toList() }
        tts.stop()
        // Algunos motores no avisan onStop de lo que estaba en cola.
        for (id in ids) if (active.remove(id)) onEvent(id, "end", null)
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
    }
}
