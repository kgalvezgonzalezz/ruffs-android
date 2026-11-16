package com.ruffs.companion

import android.os.SystemClock
import android.webkit.CookieManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Comprueba qué dirección de RUFFS responde.
 *
 * Usa /api/companion/ping, que existe en RUFFS. Sin sesión responde 401, y eso
 * también cuenta como "el servidor está ahí". La app de Odysseus aceptaba
 * cualquier código 200-499 (incluido un 404 de otro servidor) y solo probaba
 * Tailscale; aquí se prueban todas en paralelo.
 */
object ServerProbe {
    data class Result(val url: String, val ok: Boolean, val millis: Long, val detail: String)

    private const val TIMEOUT_MS = 2500

    suspend fun probe(url: String, timeoutMs: Int = TIMEOUT_MS): Result = withContext(Dispatchers.IO) {
        val start = SystemClock.elapsedRealtime()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$url/api/companion/ping").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json")
                CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
            }
            val code = conn.responseCode
            val ms = SystemClock.elapsedRealtime() - start
            when (code) {
                200 -> Result(url, true, ms, "conectado (sesión iniciada)")
                401, 403 -> Result(url, true, ms, "conectado (falta iniciar sesión)")
                else -> Result(url, false, ms, "responde $code: no parece RUFFS")
            }
        } catch (e: Exception) {
            val ms = SystemClock.elapsedRealtime() - start
            Result(url, false, ms, describe(e))
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Prueba todas a la vez y devuelve la primera que responda según el orden
     * de preferencia, sin esperar a las más lentas si una mejor ya respondió.
     */
    suspend fun pickBest(urls: List<String>, preferred: String?): Pair<String?, List<Result>> {
        val ordered = (listOfNotNull(preferred?.takeIf { it in urls }) + urls).distinct()
        // Ámbito propio: al encontrar uno bueno no esperamos a que terminen los
        // demás (una conexión HTTP bloqueada no se interrumpe al cancelar).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val jobs = ordered.map { u -> scope.async { probe(u) } }
            val done = ArrayList<Result>()
            for (job in jobs) {
                val r = job.await()
                done.add(r)
                if (r.ok) return r.url to done
            }
            return null to done
        } finally {
            scope.cancel()
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "sin respuesta (tiempo agotado)"
        is java.net.ConnectException -> "rechazó la conexión (¿RUFFS cerrado o solo en 127.0.0.1?)"
        is java.net.NoRouteToHostException -> "sin ruta (otra red o Tailscale apagado)"
        is java.net.UnknownHostException -> "nombre desconocido"
        else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
    }
}
