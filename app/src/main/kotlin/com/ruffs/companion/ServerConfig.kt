package com.ruffs.companion

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONObject

/**
 * Direcciones del servidor RUFFS que la app intenta, en orden de preferencia.
 *
 * La app de Odysseus tenía las IPs del PC fijas en el código. Aquí no hay
 * ninguna: en el primer inicio cada persona escribe la IP de Tailscale de su PC
 * con RUFFS (100.x.y.z o nombre MagicDNS), o escanea el QR de emparejamiento.
 */
object ServerConfig {
    const val DEFAULT_PORT = 7100

    /** En el emulador, 10.0.2.2 es el 127.0.0.1 del PC que lo ejecuta. */
    const val EMULATOR_SERVER = "http://10.0.2.2:7100"

    private const val PREFS = "ruffs_server"
    private const val KEY_SERVERS = "servers"
    private const val KEY_LAST_GOOD = "last_good"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.HARDWARE.contains("ranchu") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.PRODUCT.contains("sdk")

    /** Sin configurar no hay ninguna dirección: se pide en el primer inicio. */
    fun defaults(): List<String> = emptyList()

    /** Sugerencia para rellenar el campo la primera vez (solo en el emulador). */
    fun suggestion(): String? = if (isEmulator()) EMULATOR_SERVER else null

    fun servers(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY_SERVERS, null) ?: return defaults()
        return raw.lines().mapNotNull { normalize(it) }.distinct()
    }

    /** ¿La persona ya configuró su servidor (primer inicio completado)? */
    fun isConfigured(ctx: Context): Boolean = servers(ctx).isNotEmpty() && prefs(ctx).contains(KEY_SERVERS)

    /** IP de Tailscale (100.64.0.0/10) o nombre MagicDNS (*.ts.net). */
    fun isTailscale(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase() ?: return false
        if (host.endsWith(".ts.net")) return true
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        return p.size == 4 && p[0] == 100 && p[1] in 64..127
    }

    fun saveServers(ctx: Context, list: List<String>) {
        prefs(ctx).edit().putString(KEY_SERVERS, list.joinToString("\n")).apply()
    }

    fun reset(ctx: Context) {
        prefs(ctx).edit().remove(KEY_SERVERS).remove(KEY_LAST_GOOD).apply()
    }

    fun lastGood(ctx: Context): String? = prefs(ctx).getString(KEY_LAST_GOOD, null)

    fun setLastGood(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_LAST_GOOD, url).apply()
    }

    /**
     * Convierte lo que escriba el usuario en "esquema://host:puerto".
     * "192.168.1.5" → "http://192.168.1.5:7100"; "https://x.ts.net" se respeta.
     */
    fun normalize(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty() || s.startsWith("#")) return null
        val hadScheme = s.contains("://")
        if (!hadScheme) s = "http://$s"
        val uri = Uri.parse(s)
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = when {
            uri.port > 0 -> uri.port
            !hadScheme -> DEFAULT_PORT
            else -> -1
        }
        return buildString {
            append(scheme).append("://")
            append(if (host.contains(':')) "[$host]" else host)
            if (port > 0) append(':').append(port)
        }
    }

    /**
     * Acepta el payload del emparejamiento de RUFFS (/api/companion/pair):
     * {"v":1,"host":"192.168.1.20","port":7100,"token":"ody_..."}
     * y, si viene, una lista "hosts" con todas las IPs del PC (Tailscale y LAN).
     * También acepta una dirección suelta. Las de Tailscale van primero porque
     * funcionan dentro y fuera de casa. El token no se usa: la interfaz web
     * inicia sesión con su propia pantalla de acceso.
     */
    fun parsePairing(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        if (!text.startsWith("{")) return listOfNotNull(normalize(text))
        return try {
            val o = JSONObject(text)
            val port = o.optInt("port", DEFAULT_PORT)
            val hosts = ArrayList<String>()
            o.optJSONArray("hosts")?.let { arr -> for (i in 0 until arr.length()) hosts += arr.optString(i) }
            o.optString("host").takeIf { it.isNotBlank() }?.let { hosts += it }
            hosts.filter { it.isNotBlank() }
                .mapNotNull { normalize(if (it.contains("://")) it else "http://$it:$port") }
                .distinct()
                .sortedByDescending { isTailscale(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** "esquema://host:puerto" de cualquier URL (puerto por defecto incluido). */
    fun originOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        return "$scheme://$host:$port"
    }

    fun sameOrigin(a: String?, b: String?): Boolean {
        val oa = originOf(a) ?: return false
        return oa == originOf(b)
    }
}
