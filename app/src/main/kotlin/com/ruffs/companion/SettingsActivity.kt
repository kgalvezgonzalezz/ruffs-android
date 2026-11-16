package com.ruffs.companion

import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Pantalla "Servidor": direcciones de RUFFS (una por línea, en orden de
 * preferencia), botón para probarlas y emparejamiento por QR o código.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var results: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val root = findViewById<android.view.View>(R.id.settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        input = findViewById(R.id.servers_input)
        results = findViewById(R.id.test_results)
        input.setText(ServerConfig.servers(this).joinToString("\n"))
        if (!ServerConfig.isConfigured(this)) {
            // Primer inicio: cada persona conecta la app con SU RUFFS.
            findViewById<TextView>(R.id.settings_title_text).setText(R.string.setup_title)
            findViewById<TextView>(R.id.settings_intro_text).setText(R.string.setup_intro)
            if (input.text.isNullOrBlank()) ServerConfig.suggestion()?.let { input.setText(it) }
        }
        findViewById<TextView>(R.id.version_text).text =
            "RUFFS para Android ${BuildConfig.VERSION_NAME} · ${packageName}"

        findViewById<Button>(R.id.btn_test).setOnClickListener { test() }
        findViewById<Button>(R.id.btn_scan).setOnClickListener { scan() }
        findViewById<Button>(R.id.btn_paste).setOnClickListener { paste() }
        findViewById<Button>(R.id.btn_reset).setOnClickListener {
            input.setText("")
            results.text = ""
            toast("Lista vacía: escribe la dirección de tu RUFFS")
        }
        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
    }

    private fun parsed(): List<String> = input.text.toString().lines().mapNotNull { ServerConfig.normalize(it) }.distinct()

    private fun test() {
        val list = parsed()
        if (list.isEmpty()) { results.text = "Escribe al menos una dirección."; return }
        results.text = "Probando ${list.size} dirección(es)…"
        lifecycleScope.launch {
            val out = list.map { ServerProbe.probe(it) }
            val text = StringBuilder(out.joinToString("\n") {
                (if (it.ok) "✓ " else "✗ ") + it.url.removePrefix("http://") + "  ${it.millis} ms\n   " + it.detail
            })
            if (list.any { ServerConfig.isTailscale(it) } && !vpnActive()) {
                text.append("\n\n⚠ Tailscale no parece activo en este teléfono: ábrelo y conéctate a tu red.")
            }
            results.text = text
        }
    }

    /** Tailscale en Android funciona como una VPN. */
    private fun vpnActive(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION") // la única forma de ver una VPN que no es la red por defecto
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    private fun addServers(urls: List<String>) {
        val list = parsed().toMutableList()
        list.removeAll(urls)
        list.addAll(0, urls)
        input.setText(list.joinToString("\n"))
        toast("Añadido: " + urls.joinToString(", ") { it.removePrefix("http://") })
        test()
    }

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code ->
                val urls = ServerConfig.parsePairing(code.rawValue)
                if (urls.isNotEmpty()) addServers(urls) else toast("Ese QR no es un código de RUFFS")
            }
            .addOnFailureListener { e -> toast("No se pudo escanear: ${e.message}") }
    }

    private fun paste() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        val urls = ServerConfig.parsePairing(text)
        if (urls.isNotEmpty()) addServers(urls) else toast("El portapapeles no tiene un código ni una dirección de RUFFS")
    }

    private fun save() {
        val list = parsed()
        if (list.isEmpty()) { toast("Escribe al menos una dirección"); return }
        ServerConfig.saveServers(this, list)
        setResult(RESULT_OK)
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
