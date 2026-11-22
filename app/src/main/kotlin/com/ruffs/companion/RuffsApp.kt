package com.ruffs.companion

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle

/**
 * Sabe cuándo la app entera pasa a segundo plano (no una pantalla suelta: ir de
 * RUFFS a Opciones no cuenta como salir). Lo usa el bloqueo con huella.
 */
class RuffsApp : Application(), Application.ActivityLifecycleCallbacks {
    private var started = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        started++
        // Con bloqueo, la vista previa de «Recientes» no enseña los chats.
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(!AppOptions.lockEnabled(this))
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && !activity.isChangingConfigurations) AppLock.onBackground()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
