package com.ruffs.companion

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Bloqueo opcional con huella, rostro o el PIN/patrón del teléfono.
 * Se pide al abrir la app y al volver tras [AppOptions.lockAfterMinutes] en
 * segundo plano. Mientras está bloqueada no se ve nada de RUFFS.
 */
object AppLock {
    private const val AUTH = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    @Volatile private var unlockedUntilBackground = false
    private var backgroundAt = 0L

    /** ¿Este teléfono tiene huella/rostro o un bloqueo de pantalla configurado? */
    fun available(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    fun onBackground() {
        backgroundAt = SystemClock.elapsedRealtime()
    }

    fun needsUnlock(ctx: Context): Boolean {
        if (!AppOptions.lockEnabled(ctx) || !available(ctx)) return false
        if (!unlockedUntilBackground) return true
        if (backgroundAt == 0L) return false
        val away = SystemClock.elapsedRealtime() - backgroundAt
        return away >= AppOptions.lockAfterMinutes(ctx) * 60_000L
    }

    fun prompt(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Desbloquear RUFFS")
            .setSubtitle("Usa tu huella, tu rostro o el bloqueo del teléfono")
            .setAllowedAuthenticators(AUTH)
            .build()
        val p = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlockedUntilBackground = true
                backgroundAt = 0L
                onResult(true)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        })
        p.authenticate(info)
    }

    /** Al activar la opción: confirmar que la persona puede desbloquear. */
    fun markUnlocked() {
        unlockedUntilBackground = true
        backgroundAt = 0L
    }
}
