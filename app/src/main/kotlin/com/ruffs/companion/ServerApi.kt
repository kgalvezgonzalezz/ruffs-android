package com.ruffs.companion

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Llamadas a RUFFS desde segundo plano (sin la página abierta), con la misma
 * sesión que la página (sus cookies). Prueba primero la última dirección que
 * respondió y luego las demás.
 */
object ServerApi {

    class NotSignedIn : Exception("Inicia sesión en la app")

    suspend fun getJson(ctx: Context, path: String, timeoutMs: Int = 8000): JSONObject = withContext(Dispatchers.IO) {
        val servers = ServerConfig.servers(ctx)
        val ordered = listOfNotNull(ServerConfig.lastGood(ctx)?.takeIf { it in servers }) + servers.filter { it != ServerConfig.lastGood(ctx) }
        var last: Exception = IllegalStateException("Sin servidor configurado")
        for (base in ordered) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                    cookie(base)?.let { setRequestProperty("Cookie", it) }
                }
                when (val code = conn.responseCode) {
                    200 -> {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        if (base != ServerConfig.lastGood(ctx)) ServerConfig.setLastGood(ctx, base)
                        return@withContext JSONObject(text)
                    }
                    401, 403 -> throw NotSignedIn()
                    else -> last = IllegalStateException("HTTP $code")
                }
            } catch (e: NotSignedIn) {
                throw e
            } catch (e: Exception) {
                last = e
            } finally {
                conn?.disconnect()
            }
        }
        throw last
    }

    private fun cookie(base: String): String? = runCatching { CookieManager.getInstance().getCookie(base) }.getOrNull()
}
