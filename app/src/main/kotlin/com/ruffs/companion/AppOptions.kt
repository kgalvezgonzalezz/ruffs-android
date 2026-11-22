package com.ruffs.companion

import android.content.Context
import android.content.SharedPreferences

/**
 * Opciones de la app (pantalla «Opciones»). Todo es opcional:
 *  - avisos con la app cerrada (activado por defecto),
 *  - escuchar la palabra de activación con la app cerrada (desactivado),
 *  - bloqueo con huella / rostro / PIN del teléfono (desactivado),
 *  - buscar versiones nuevas de la app (activado).
 */
object AppOptions {
    private fun p(ctx: Context): SharedPreferences = ctx.getSharedPreferences("ruffs_options", Context.MODE_PRIVATE)

    fun notifyClosed(ctx: Context) = p(ctx).getBoolean("notify_closed", true)
    fun setNotifyClosed(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("notify_closed", on).apply()

    fun wakeBackground(ctx: Context) = p(ctx).getBoolean("wake_background", false)
    fun setWakeBackground(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("wake_background", on).apply()

    /** Escuchar también con la pantalla apagada (gasta bastante más batería). */
    fun wakeScreenOff(ctx: Context) = p(ctx).getBoolean("wake_screen_off", false)
    fun setWakeScreenOff(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("wake_screen_off", on).apply()

    /** Palabra de activación de RUFFS (Ajustes → Voz en RUFFS); la trae la sincronización. */
    fun wakeWord(ctx: Context): String = p(ctx).getString("wake_word", null)?.takeIf { it.isNotBlank() } ?: "RUFFS"
    fun setWakeWord(ctx: Context, word: String) = p(ctx).edit().putString("wake_word", word.trim()).apply()

    /** Idioma del modelo de voz sin conexión (es, en, pt). */
    fun wakeLang(ctx: Context): String = p(ctx).getString("wake_lang", null) ?: VoskModel.defaultLang()
    fun setWakeLang(ctx: Context, lang: String) = p(ctx).edit().putString("wake_lang", lang).apply()

    fun lockEnabled(ctx: Context) = p(ctx).getBoolean("lock_enabled", false)
    fun setLockEnabled(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("lock_enabled", on).apply()

    /** Minutos en segundo plano antes de volver a pedir la huella (0 = cada vez). */
    fun lockAfterMinutes(ctx: Context) = p(ctx).getInt("lock_after_min", 1)
    fun setLockAfterMinutes(ctx: Context, min: Int) = p(ctx).edit().putInt("lock_after_min", min).apply()

    fun updateCheck(ctx: Context) = p(ctx).getBoolean("update_check", true)
    fun setUpdateCheck(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("update_check", on).apply()

    fun lastUpdateCheck(ctx: Context) = p(ctx).getLong("last_update_check", 0L)
    fun setLastUpdateCheck(ctx: Context, at: Long) = p(ctx).edit().putLong("last_update_check", at).apply()

    fun skippedVersion(ctx: Context): String? = p(ctx).getString("skipped_version", null)
    fun setSkippedVersion(ctx: Context, v: String?) = p(ctx).edit().putString("skipped_version", v).apply()

    /** Último aviso del servidor ya mostrado (número creciente). */
    fun notifySeq(ctx: Context) = p(ctx).getLong("notify_seq", -1L)
    fun setNotifySeq(ctx: Context, seq: Long) = p(ctx).edit().putLong("notify_seq", seq).apply()

    /** Para enseñar una sola vez las novedades de esta versión. */
    fun seenWhatsNew(ctx: Context) = p(ctx).getString("whats_new_seen", "") ?: ""
    fun setSeenWhatsNew(ctx: Context, v: String) = p(ctx).edit().putString("whats_new_seen", v).apply()
}
