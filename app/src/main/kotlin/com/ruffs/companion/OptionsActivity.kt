package com.ruffs.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Opciones de la app: todo opcional y se puede cambiar cuando quieras. */
class OptionsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private var pendingPermission: ((Boolean) -> Unit)? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        pendingPermission?.invoke(ok); pendingPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.color.ruffs_bg)); isFillViewport = true }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(28)) }
        scroll.addView(list)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsetsCompat.CONSUMED
        }
    }

    override fun onResume() {
        super.onResume()
        render()   // al volver de los ajustes de Android, reflejar lo que cambió
    }

    private fun render() {
        list.removeAllViews()
        title("Opciones de la app")
        note("Todo es opcional. RUFFS sigue funcionando igual en tu PC.")

        // ── Avisos con la app cerrada ──
        section("Avisos")
        switch("Avisos con la app cerrada",
            "Recordatorios a su hora y avisos de tareas terminadas aunque no tengas RUFFS abierto. Se revisa cada ~15 minutos; los recordatorios suenan a la hora exacta.",
            AppOptions.notifyClosed(this)) { on, sw ->
            if (on && Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                ask(Manifest.permission.POST_NOTIFICATIONS) { ok ->
                    if (!ok) { sw.isChecked = false; toast("Sin permiso de notificaciones no se pueden mostrar avisos") }
                    else { AppOptions.setNotifyClosed(this, true); NotifySync.schedule(this); NotifySync.syncSoon(this) }
                }
                return@switch
            }
            AppOptions.setNotifyClosed(this, on)
            NotifySync.schedule(this)
            if (on) NotifySync.syncSoon(this)
        }

        // ── Palabra de activación con la app cerrada ──
        section("Voz")
        val word = AppOptions.wakeWord(this).lowercase().replaceFirstChar { it.titlecase() }
        val lang = AppOptions.wakeLang(this)
        switch("Escuchar «$word» con la app cerrada",
            "Di «$word» y RUFFS se abre escuchándote. Funciona sin internet (el audio no sale del teléfono). Usa algo más de batería y deja una notificación fija mientras escucha. " +
                "La palabra es la de RUFFS → Ajustes → Voz.",
            AppOptions.wakeBackground(this)) { on, sw ->
            if (!on) { AppOptions.setWakeBackground(this, false); WakeWordService.stop(this); render(); return@switch }
            ask(Manifest.permission.RECORD_AUDIO) { ok ->
                if (!ok) { sw.isChecked = false; toast("Hace falta el permiso de micrófono"); return@ask }
                // Android 13+: sin permiso de notificaciones no se ve ni el aviso
                // «escuchando» ni el «Te escucho» (no impide activar la opción).
                if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    ask(Manifest.permission.POST_NOTIFICATIONS) { }
                }
                withModel(lang) { ready ->
                    if (!ready) { sw.isChecked = false; return@withModel }
                    AppOptions.setWakeBackground(this, true)
                    WakeWordService.ensure(this)
                    render()
                    if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this)) askOverlay()
                }
            }
        }
        if (AppOptions.wakeBackground(this)) {
            if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this)) {
                button("Permitir que se abra sola") { askOverlay() }
                note("Sin este permiso, al oírte aparece un aviso «Te escucho» que tienes que tocar.")
            }
            button("Idioma del reconocimiento: ${langName(lang)}") { chooseLang() }
            switch("También con la pantalla apagada",
                "Gasta bastante más batería: mantiene el procesador despierto. Sin esto escucha solo con la pantalla encendida. " +
                    "En modo ahorro de batería o con menos del 15 % se pausa sola.",
                AppOptions.wakeScreenOff(this)) { on, _ ->
                AppOptions.setWakeScreenOff(this, on)
                WakeWordService.refresh(this)
            }
        }

        // ── Asistente ──
        section("Asistente del teléfono")
        note(if (isAssistant()) "RUFFS es tu asistente: mantén pulsado el botón de inicio (o el de encendido, según el teléfono) para hablarle."
             else "Haz que RUFFS se abra escuchándote al mantener pulsado el botón de inicio o el de encendido, en lugar del asistente de Google.")
        button(if (isAssistant()) "Cambiar asistente" else "Usar RUFFS como asistente") { openAssistantSettings() }

        // ── Bloqueo ──
        section("Privacidad")
        val canLock = AppLock.available(this)
        switch("Bloquear con huella o rostro",
            if (canLock) "Pide tu huella, tu rostro o el PIN del teléfono al abrir RUFFS." else "Configura una huella, un rostro o un bloqueo de pantalla en el teléfono para usar esta opción.",
            AppOptions.lockEnabled(this) && canLock, enabled = canLock) { on, sw ->
            if (!on) { AppOptions.setLockEnabled(this, false); render(); return@switch }
            AppLock.prompt(this) { ok ->
                if (ok) { AppOptions.setLockEnabled(this, true); AppLock.markUnlocked() } else sw.isChecked = false
                render()
            }
        }
        if (AppOptions.lockEnabled(this)) {
            button("Volver a pedirla: ${lockLabel(AppOptions.lockAfterMinutes(this))}") { chooseLockTime() }
        }

        // ── Actualizaciones ──
        section("Actualizaciones")
        switch("Avisarme de versiones nuevas", "Revisa GitHub al abrir la app (como mucho dos veces al día).",
            AppOptions.updateCheck(this)) { on, _ -> AppOptions.setUpdateCheck(this, on) }
        button("Buscar actualizaciones ahora") {
            toast("Buscando…")
            lifecycleScope.launch { Updater.checkNow(this@OptionsActivity) { msg -> toast(msg) } }
        }

        section("Servidor")
        button("Direcciones de tu RUFFS") { startActivity(Intent(this, SettingsActivity::class.java)) }
        note("RUFFS para Android ${BuildConfig.VERSION_NAME}")
    }

    // ── Acciones ──────────────────────────────────────────────────────────────
    private fun withModel(lang: String, done: (Boolean) -> Unit) {
        if (VoskModel.isReady(this, lang)) { done(true); return }
        AlertDialog.Builder(this)
            .setTitle("Descargar el reconocimiento de voz")
            .setMessage("Para escuchar sin internet hace falta un modelo de voz de ~${VoskModel.sizeMb(lang)} MB (${langName(lang)}). Se descarga una sola vez; mejor con Wi-Fi.")
            .setPositiveButton("Descargar") { _, _ ->
                val msg = TextView(this).apply { setPadding(dp(24), dp(16), dp(24), dp(8)); text = "Descargando… 0 %" }
                val dlg = AlertDialog.Builder(this).setTitle("Modelo de voz").setView(msg).setCancelable(false).show()
                lifecycleScope.launch {
                    val ok = runCatching { VoskModel.download(this@OptionsActivity, lang) { p -> runOnUiThread { msg.text = if (p < 95) "Descargando… $p %" else "Preparando…" } } }
                    dlg.dismiss()
                    ok.exceptionOrNull()?.let { toast("No se pudo descargar: ${it.message}") }
                    done(ok.isSuccess)
                }
            }
            .setNegativeButton("Cancelar") { _, _ -> done(false) }
            .setOnCancelListener { done(false) }
            .show()
    }

    private fun chooseLang() {
        val codes = VoskModel.MODELS.keys.toList()
        AlertDialog.Builder(this).setTitle("Idioma")
            .setItems(codes.map { langName(it) }.toTypedArray()) { _, i ->
                val l = codes[i]
                withModel(l) { ok -> if (ok) { AppOptions.setWakeLang(this, l); WakeWordService.refresh(this); render() } }
            }.show()
    }

    private fun askOverlay() {
        AlertDialog.Builder(this)
            .setTitle("Que RUFFS se abra al oírte")
            .setMessage("Android solo deja que una app se abra sola si tiene el permiso «Mostrar sobre otras apps». RUFFS lo usa únicamente para abrirse cuando dices la palabra.")
            .setPositiveButton("Dar permiso") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Ahora no", null).show()
    }

    private fun isAssistant(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(android.app.role.RoleManager::class.java)
            if (rm.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT)) return rm.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
        }
        return Settings.Secure.getString(contentResolver, "assistant")?.contains(packageName) == true
    }

    private fun openAssistantSettings() {
        val tries = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in tries) {
            if (runCatching { startActivity(i) }.isSuccess) {
                toast("Elige «Asistente digital» o «App de asistencia» → RUFFS", long = true)
                return
            }
        }
    }

    private fun chooseLockTime() {
        val opts = listOf(0, 1, 5, 15)
        AlertDialog.Builder(this).setTitle("Volver a pedirla")
            .setItems(opts.map { lockLabel(it) }.toTypedArray()) { _, i -> AppOptions.setLockAfterMinutes(this, opts[i]); render() }
            .show()
    }

    private fun lockLabel(min: Int) = if (min == 0) "cada vez que vuelvas" else "tras $min min fuera"
    private fun langName(l: String) = when (l) { "es" -> "español"; "en" -> "inglés"; "pt" -> "portugués"; else -> l }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun ask(p: String, cb: (Boolean) -> Unit) {
        if (granted(p)) { cb(true); return }
        pendingPermission = cb; permLauncher.launch(p)
    }

    // ── Vistas ────────────────────────────────────────────────────────────────
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun toast(m: String, long: Boolean = false) = Toast.makeText(this, m, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private fun title(t: String) = list.addView(TextView(this).apply {
        text = t; setTextColor(color(R.color.ruffs_fg)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); setTypeface(typeface, android.graphics.Typeface.BOLD)
    })

    private fun section(t: String) = list.addView(TextView(this).apply {
        text = t.uppercase(); setTextColor(color(R.color.ruffs_accent)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        letterSpacing = 0.08f; setPadding(0, dp(24), 0, dp(6))
    })

    private fun note(t: String) = list.addView(TextView(this).apply {
        text = t; setTextColor(color(R.color.ruffs_muted)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(0, dp(4), 0, dp(4))
    })

    private fun button(t: String, onClick: () -> Unit) = list.addView(
        Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        },
    )

    private fun switch(label: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean, SwitchCompat) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)); alpha = if (enabled) 1f else 0.5f }
        val sw = SwitchCompat(this).apply {
            text = label; isChecked = checked; isEnabled = enabled; setTextColor(color(R.color.ruffs_fg))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER_VERTICAL
        }
        sw.setOnCheckedChangeListener { b, on -> if (b.isPressed) onChange(on, sw) }
        row.addView(sw)
        row.addView(TextView(this).apply { text = detail; setTextColor(color(R.color.ruffs_muted)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) })
        list.addView(row)
        list.addView(View(this).apply { setBackgroundColor(color(R.color.ruffs_border)); alpha = 0.4f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(6) } })
    }
}
