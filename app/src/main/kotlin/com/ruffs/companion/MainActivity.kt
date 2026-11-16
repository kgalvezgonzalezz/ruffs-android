package com.ruffs.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private enum class State { CONNECTING, WEB, ERROR }

    // Vistas
    private lateinit var root: FrameLayout
    private lateinit var swipe: PullToRefreshLayout
    private lateinit var web: WebView
    private lateinit var statusOverlay: SwipeRefreshLayout
    private lateinit var statusProgress: ProgressBar
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusButtons: View
    private lateinit var popupContainer: View
    private lateinit var popupHolder: FrameLayout
    private lateinit var popupTitle: TextView
    private lateinit var fullscreen: FrameLayout

    // Servicios nativos que usa el puente
    lateinit var tts: TtsManager
    lateinit var speech: SpeechManager
    lateinit var recorder: NativeRecorder
    lateinit var notifier: WebNotifier
    lateinit var saver: FileSaver

    /** Quién pidió cada operación asíncrona (TTS, voz), para devolverle los eventos. */
    val owners = ConcurrentHashMap<String, NativeBridge>()
    private val bridges = ConcurrentHashMap<WebView, NativeBridge>()

    private var state = State.CONNECTING
    @Volatile private var serverUrl: String? = null
    @Volatile private var knownOrigins: Set<String> = emptySet()
    private var connectJob: Job? = null
    private var setupShown = false
    private var mainFrameError = false
    private var popupWeb: WebView? = null
    private var popupLoadedSomething = false
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var themeColor = Color.parseColor("#06071A")
    private var imeVisible = false
    private var refreshStartedAt = 0L
    private var refreshTimeout: Runnable? = null

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutput: Uri? = null
    private var cameraFile: File? = null
    private var pendingSaveAs: Triple<File, String, String>? = null

    private val main = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val shimJs by lazy { assets.open("ruffs_bridge.js").bufferedReader().use { it.readText() } }
    private var docStartSupported = false

    fun io(block: suspend () -> Unit) {
        ioScope.launch { block() }
    }

    fun toast(msg: String, long: Boolean = false) = runOnUiThread {
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun currentServer(): String? = serverUrl

    // ── Permisos (en cola, uno a la vez) ──────────────────────────────────────
    private val permQueue = ArrayDeque<Pair<String, (Boolean) -> Unit>>()
    private var permInFlight: Pair<String, (Boolean) -> Unit>? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val cur = permInFlight
        permInFlight = null
        if (cur?.first == Manifest.permission.POST_NOTIFICATIONS) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notif_asked", true).apply()
        }
        cur?.second?.invoke(granted)
        nextPermission()
    }

    fun withPermission(permission: String, callback: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            callback(true); return
        }
        permQueue.addLast(permission to callback)
        if (permInFlight == null) nextPermission()
    }

    private fun nextPermission() {
        val next = permQueue.removeFirstOrNull() ?: return
        if (ContextCompat.checkSelfPermission(this, next.first) == PackageManager.PERMISSION_GRANTED) {
            next.second(true); nextPermission(); return
        }
        permInFlight = next
        permLauncher.launch(next.first)
    }

    fun notificationPermissionState(): String = when {
        notifier.permissionGranted() -> "granted"
        Build.VERSION.SDK_INT < 33 -> "denied" // desactivadas en los ajustes del sistema
        getPreferences(MODE_PRIVATE).getBoolean("notif_asked", false) -> "denied"
        else -> "default"
    }

    fun requestNotificationPermission(callback: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 33 || notifier.permissionGranted()) {
            callback(notificationPermissionState()); return
        }
        withPermission(Manifest.permission.POST_NOTIFICATIONS) { granted -> callback(if (granted) "granted" else "denied") }
    }

    // ── Lanzadores de otras pantallas ─────────────────────────────────────────
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = filePathCallback
        filePathCallback = null
        var uris: Array<Uri>? = null
        if (res.resultCode == Activity.RESULT_OK) {
            val data = res.data
            val clip = data?.clipData
            uris = when {
                clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                data?.data != null -> arrayOf(data.data!!)
                cameraFile?.let { it.exists() && it.length() > 0 } == true -> arrayOf(cameraOutput!!)
                else -> null
            }
        }
        cameraFile = null
        cameraOutput = null
        cb?.onReceiveValue(uris)
    }

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) connect(currentPath())
    }

    /** Android 8–9 sin permiso de almacenamiento: diálogo "Guardar como". */
    private val saveAsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val pending = pendingSaveAs ?: return@registerForActivityResult
        pendingSaveAs = null
        val uri = res.data?.data
        if (res.resultCode != Activity.RESULT_OK || uri == null) { pending.first.delete(); return@registerForActivityResult }
        io {
            try {
                contentResolver.openOutputStream(uri)?.use { out -> pending.first.inputStream().use { it.copyTo(out) } }
                toast("Guardado: ${pending.second}")
            } catch (e: Exception) {
                toast("Error al guardar: ${e.message}", true)
            } finally {
                pending.first.delete()
            }
        }
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        root = findViewById(R.id.root)
        swipe = findViewById(R.id.swipe_refresh)
        web = findViewById(R.id.webview)
        statusOverlay = findViewById(R.id.status_overlay)
        statusProgress = findViewById(R.id.status_progress)
        statusTitle = findViewById(R.id.status_title)
        statusDetail = findViewById(R.id.status_detail)
        statusButtons = findViewById(R.id.status_buttons)
        popupContainer = findViewById(R.id.popup_container)
        popupHolder = findViewById(R.id.popup_web_holder)
        popupTitle = findViewById(R.id.popup_title)
        fullscreen = findViewById(R.id.fullscreen_container)

        // Barras del sistema + teclado: el contenido se encoge para que el
        // teclado no tape la caja de mensaje (la app de Odysseus no lo hacía).
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        WebView.setWebContentsDebuggingEnabled((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)

        notifier = WebNotifier(this)
        saver = FileSaver(this)
        recorder = NativeRecorder(this)
        tts = TtsManager(
            this,
            onEvent = { id, ev, err ->
                owners[id]?.let { b ->
                    val o = JSONObject().put("id", id).put("ev", ev)
                    if (err != null) o.put("error", err)
                    b.emit("tts", o.toString())
                    if (ev != "start") owners.remove(id)
                }
            },
            onVoicesChanged = { bridges.values.forEach { it.emit("voiceschanged", "null") } },
        )
        speech = SpeechManager(
            this,
            emit = { id, data ->
                owners[id]?.let { b ->
                    data.put("id", id)
                    b.emit("sr", data.toString())
                    if (data.optString("ev") == "end") owners.remove(id)
                }
            },
            beforeListen = { recorder.yieldMic() },
        )
        io { saver.cleanOld() }

        configureWebView(web, popup = false)

        // Tirar para recargar
        val density = resources.displayMetrics.density
        val accent = ContextCompat.getColor(this, R.color.ruffs_accent)
        val accent2 = ContextCompat.getColor(this, R.color.ruffs_accent2)
        val panel = ContextCompat.getColor(this, R.color.ruffs_panel)
        for (s in listOf<SwipeRefreshLayout>(swipe, statusOverlay)) {
            s.setColorSchemeColors(accent, accent2)
            s.setProgressBackgroundColorSchemeColor(panel)
            s.setDistanceToTriggerSync((80 * density).toInt())
            s.setSlingshotDistance((112 * density).toInt())
        }
        swipe.canRefresh = { state == State.WEB && popupWeb == null && customView == null && !imeVisible }
        swipe.onThresholdChanged = { reached ->
            swipe.tick(if (reached) PullToRefreshLayout.Haptic.THRESHOLD_ON else PullToRefreshLayout.Haptic.THRESHOLD_OFF)
        }
        swipe.setOnRefreshListener { onPullRefresh() }
        // En la pantalla de error: tirar hacia abajo = reintentar
        statusOverlay.isEnabled = false
        statusOverlay.setOnRefreshListener {
            if (state == State.ERROR) {
                statusOverlay.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                connect(currentPath())
            } else {
                statusOverlay.isRefreshing = false
            }
        }

        findViewById<Button>(R.id.btn_retry).setOnClickListener { connect(currentPath()) }
        findViewById<Button>(R.id.btn_settings).setOnClickListener { openSettings() }
        findViewById<ImageButton>(R.id.popup_close).setOnClickListener { closePopup() }
        findViewById<ImageButton>(R.id.popup_external).setOnClickListener {
            popupWeb?.url?.takeIf { it.startsWith("http") }?.let { openExternal(Uri.parse(it)) }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(netCallback)

        connect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(WebNotifier.EXTRA_NOTIFICATION_ID)?.let { id ->
            bridges[web]?.emit("notificationclick", JSONObject.quote(id))
        }
    }

    override fun onResume() {
        super.onResume()
        if (state == State.ERROR) connect(currentPath())
    }

    override fun onDestroy() {
        runCatching { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(netCallback) }
        main.removeCallbacksAndMessages(null)
        tts.shutdown()
        speech.destroy()
        recorder.release()
        closePopup()
        bridges.values.forEach { it.destroyed = true }
        bridges.clear()
        ioScope.cancel()
        CookieManager.getInstance().flush()
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    // ── Conexión con el servidor ──────────────────────────────────────────────
    private fun connect(path: String? = null) {
        connectJob?.cancel()
        val servers = ServerConfig.servers(this)
        if (servers.isEmpty()) {
            // Primer inicio: no hay ninguna dirección. Cada persona pone la de SU RUFFS.
            state = State.ERROR
            showStatus(getString(R.string.setup_needed_title), getString(R.string.setup_needed_detail), error = true)
            if (!setupShown) { setupShown = true; openSettings() }
            return
        }
        state = State.CONNECTING
        showStatus(getString(R.string.status_connecting), getString(R.string.status_searching), error = false)
        knownOrigins = servers.mapNotNull { ServerConfig.originOf(it) }.toSet()
        connectJob = lifecycleScope.launch {
            val (best, results) = ServerProbe.pickBest(servers, ServerConfig.lastGood(this@MainActivity))
            if (best != null) loadServer(best, path) else showError(results, null)
        }
    }

    private fun loadServer(url: String, path: String?) {
        serverUrl = url
        ServerConfig.setLastGood(this, url)
        ServerConfig.originOf(url)?.let { knownOrigins = knownOrigins + it }
        statusDetail.text = url.removePrefix("http://").removePrefix("https://")
        mainFrameError = false
        web.loadUrl(url + (path?.takeIf { it.startsWith("/") } ?: "/"))
    }

    private fun currentPath(): String? {
        val u = web.url?.let { Uri.parse(it) } ?: return null
        if (u.scheme != "http" && u.scheme != "https") return null
        return (u.encodedPath ?: "/") + (u.encodedQuery?.let { "?$it" } ?: "") + (u.encodedFragment?.let { "#$it" } ?: "")
    }

    fun isTrusted(url: String?): Boolean {
        val o = ServerConfig.originOf(url) ?: return false
        return o in knownOrigins || o == ServerConfig.originOf(serverUrl)
    }

    private fun showStatus(title: String, detail: String, error: Boolean) {
        statusOverlay.isRefreshing = false
        statusOverlay.isEnabled = error
        statusOverlay.visibility = View.VISIBLE
        statusProgress.visibility = if (error) View.GONE else View.VISIBLE
        statusButtons.visibility = if (error) View.VISIBLE else View.GONE
        statusTitle.text = title
        statusDetail.text = detail
    }

    private fun hideStatus() {
        if (statusOverlay.visibility == View.VISIBLE) {
            statusOverlay.animate().alpha(0f).setDuration(180).withEndAction {
                statusOverlay.visibility = View.GONE
                statusOverlay.alpha = 1f
            }.start()
        }
    }

    private fun showError(results: List<ServerProbe.Result>, extra: String?) {
        state = State.ERROR
        swipe.isRefreshing = false
        val detail = buildString {
            for (r in results) {
                append(if (r.ok) "✓ " else "✗ ")
                append(r.url.removePrefix("http://").removePrefix("https://"))
                append(" — ").append(r.detail).append('\n')
            }
            if (!extra.isNullOrBlank()) append('\n').append(extra).append('\n')
            append('\n').append(getString(R.string.status_error_help))
        }
        showStatus(getString(R.string.status_error_title), detail, error = true)
    }

    /** La página principal no cargó: probar otra dirección antes de mostrar el error. */
    private fun onMainFrameFailed(description: String) {
        if (state == State.ERROR) return
        val path = currentPath()
        val failed = serverUrl
        connectJob?.cancel()
        state = State.CONNECTING
        showStatus(getString(R.string.status_connecting), "Buscando otra ruta hacia RUFFS…", error = false)
        connectJob = lifecycleScope.launch {
            val servers = ServerConfig.servers(this@MainActivity)
            val (best, results) = ServerProbe.pickBest(servers, null)
            if (best != null && best != failed) loadServer(best, path) else showError(results, "Error al cargar: $description")
        }
    }

    // ── Cambios de red (Wi-Fi ↔ datos/Tailscale) ──────────────────────────────
    private val netCheck = Runnable { onNetworkChanged() }
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetCheck()
        override fun onLost(network: Network) = scheduleNetCheck()
    }

    private fun scheduleNetCheck() {
        main.removeCallbacks(netCheck)
        main.postDelayed(netCheck, 1500)
    }

    private fun onNetworkChanged() {
        when (state) {
            State.ERROR -> connect(currentPath())
            State.WEB -> {
                val cur = serverUrl ?: return
                lifecycleScope.launch {
                    if (ServerProbe.probe(cur).ok) return@launch
                    val (best, _) = ServerProbe.pickBest(ServerConfig.servers(this@MainActivity), null)
                    if (best != null && best != cur) {
                        toast("Red cambiada: conectando por ${best.removePrefix("http://")}")
                        loadServer(best, currentPath())
                    }
                }
            }
            State.CONNECTING -> Unit
        }
    }

    // ── WebView ───────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(w: WebView, popup: Boolean): NativeBridge {
        w.setBackgroundColor(themeColor)
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "$userAgentString RUFFSApp/${BuildConfig.VERSION_NAME}"
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, false) // RUFFS tiene sus propios temas
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, false)
        }
        val bridge = NativeBridge(this, w)
        bridges[w] = bridge
        w.addJavascriptInterface(bridge, "RuffsNative")
        w.addJavascriptInterface(bridge, "Android")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, shimJs, setOf("*"))
            docStartSupported = true
        }
        w.webViewClient = Client(popup)
        w.webChromeClient = Chrome(popup)
        w.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            onDownload(w, url, userAgent, contentDisposition, mimeType)
        }
        return bridge
    }

    private inner class Client(private val popup: Boolean) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    if (isTrusted(uri.toString())) {
                        false
                    } else {
                        openExternal(uri)
                        if (popup && !popupLoadedSomething) closePopup()
                        true
                    }
                }
                "blob", "data", "about", "javascript" -> false
                else -> { openExternal(uri); true }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            bridges[view]?.pageUrl = url
            if (!docStartSupported) view.evaluateJavascript(shimJs, null)
            if (popup) popupLoadedSomething = true else mainFrameError = false
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            bridges[view]?.pageUrl = url
        }

        /** El contenido nuevo ya se ve: el círculo de recarga puede irse (antes que onPageFinished). */
        override fun onPageCommitVisible(view: WebView, url: String?) {
            if (!popup && swipe.isRefreshing) endRefresh()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!docStartSupported) view.evaluateJavascript(shimJs, null)
            if (popup) return
            if (swipe.isRefreshing) endRefresh()
            if (!mainFrameError && state == State.CONNECTING && url?.startsWith("http") == true) {
                state = State.WEB
                hideStatus()
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (popup || !request.isForMainFrame) return
            mainFrameError = true
            onMainFrameFailed(error.description?.toString() ?: "error de red")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (popup) { closePopup(); return true }
            // El motor del WebView se cerró (falta de memoria): reiniciar la pantalla.
            bridges.remove(view)?.destroyed = true
            recreate()
            return true
        }
    }

    private inner class Chrome(private val popup: Boolean) : WebChromeClient() {

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (popup) popupTitle.text = title.orEmpty()
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            val content = params.createIntent().apply {
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            val accepts = params.acceptTypes.orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
            val wantsImage = accepts.isEmpty() || accepts.any { it.startsWith("image") || it == "*/*" }
            val camera = if (wantsImage) cameraIntent() else null
            val chooser = if (params.isCaptureEnabled && camera != null) {
                camera
            } else {
                Intent.createChooser(content, params.title ?: "Elegir archivo").apply {
                    if (camera != null) putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
                }
            }
            return try {
                fileChooserLauncher.launch(chooser)
                true
            } catch (e: ActivityNotFoundException) {
                filePathCallback = null
                callback.onReceiveValue(null)
                true
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                if (!isTrusted(request.origin?.toString())) { request.deny(); return@runOnUiThread }
                if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    withPermission(Manifest.permission.RECORD_AUDIO) { granted ->
                        if (granted) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else request.deny()
                    }
                } else {
                    request.deny()
                }
            }
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = openPopup()
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            if (window == popupWeb) closePopup()
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customViewCallback = callback
            fullscreen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fullscreen.visibility = View.VISIBLE
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        override fun onHideCustomView() = hideCustomView()

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                Log.d("RUFFS-web", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
            }
            return true
        }
    }

    private fun hideCustomView() {
        val v = customView ?: return
        fullscreen.removeView(v)
        fullscreen.visibility = View.GONE
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
    }

    private fun cameraIntent(): Intent? {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) return null
        val f = saver.tempFile("foto_${System.currentTimeMillis()}.jpg")
        val uri = saver.shareableUri(f)
        cameraFile = f
        cameraOutput = uri
        return intent.apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ── Ventanas emergentes (window.open / target=_blank de RUFFS) ────────────
    private fun openPopup(): WebView {
        closePopup()
        val w = WebView(this)
        configureWebView(w, popup = true)
        popupHolder.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        popupTitle.text = ""
        popupLoadedSomething = false
        popupContainer.visibility = View.VISIBLE
        popupWeb = w
        return w
    }

    private fun closePopup() {
        val w = popupWeb ?: return
        popupWeb = null
        bridges.remove(w)?.destroyed = true
        popupHolder.removeAllViews()
        w.stopLoading()
        w.destroy()
        popupContainer.visibility = View.GONE
    }

    private fun openExternal(uri: Uri) {
        try {
            val intent = if (uri.scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            }
            startActivity(intent)
        } catch (e: Exception) {
            toast("No hay ninguna app para abrir este enlace")
        }
    }

    // ── Botón Atrás ───────────────────────────────────────────────────────────
    private fun onBack() {
        if (customView != null) { hideCustomView(); return }
        popupWeb?.let { if (it.canGoBack()) it.goBack() else closePopup(); return }
        if (state != State.WEB) { moveTaskToBack(true); return }
        web.evaluateJavascript("(window.__ruffs&&window.__ruffs.back)?window.__ruffs.back():false") { r ->
            if (r == "true") return@evaluateJavascript
            if (web.canGoBack()) web.goBack() else moveTaskToBack(true) // no cerrar: RUFFS sigue en memoria
        }
    }

    // ── Descargas ─────────────────────────────────────────────────────────────
    private fun onDownload(view: WebView, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        when {
            url.startsWith("blob:") -> {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                view.evaluateJavascript(
                    "window.__ruffs&&window.__ruffs.saveBlobUrl(${JSONObject.quote(url)},${JSONObject.quote(name)})",
                ) { r -> if (r != "true") toast("No se pudo descargar este archivo") }
            }
            url.startsWith("data:") -> io {
                try {
                    val inc = saver.fromBase64(url, mimeType ?: "application/octet-stream", "descarga")
                    deliverFile(inc.file, inc.name, inc.mime, "save")
                } catch (e: Exception) {
                    toast("No se pudo guardar: ${e.message}")
                }
            }
            else -> downloadUrl(url, null, contentDisposition, mimeType, userAgent)
        }
    }

    fun downloadUrl(url: String, name: String?, contentDisposition: String?, mimeType: String?, userAgent: String? = null) {
        val ua = userAgent ?: web.settings.userAgentString
        toast("Descargando…")
        io {
            try {
                val d = saver.download(url, name, ua, contentDisposition, mimeType)
                deliverFile(d.file, d.name, d.mime, "save")
            } catch (e: Exception) {
                toast("No se pudo descargar: ${e.message}", true)
            }
        }
    }

    /** Llamado desde un hilo de fondo con el archivo ya completo en caché. */
    fun deliverFile(file: File, name: String, mime: String, mode: String) {
        if (mode == "open") {
            runOnUiThread {
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(saver.shareableUri(file), mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(view)
                } catch (e: ActivityNotFoundException) {
                    io { deliverFile(file, name, mime, "save") }
                }
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            publish(file, name, mime)
        } else {
            runOnUiThread {
                withPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) { granted ->
                    if (granted) {
                        io { publish(file, name, mime) }
                    } else {
                        pendingSaveAs = Triple(file, name, mime)
                        saveAsLauncher.launch(
                            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = mime
                                putExtra(Intent.EXTRA_TITLE, name)
                            },
                        )
                    }
                }
            }
        }
    }

    private fun publish(file: File, name: String, mime: String) {
        try {
            val uri = saver.publish(file, name, mime)
            toast("Guardado en Descargas/RUFFS: $name")
            notifier.showDownload(name, uri, mime)
        } catch (e: Exception) {
            toast("Error al guardar: ${e.message}", true)
        }
    }

    // ── Tirar para recargar ───────────────────────────────────────────────────
    /**
     * Recarga "inteligente":
     *  1. Si RUFFS está escribiendo una respuesta, no recarga (la cortaría).
     *  2. Guarda lo que hubiera en la caja de mensaje (se restaura al volver).
     *  3. Comprueba el servidor ANTES de recargar: si no responde, prueba las
     *     otras direcciones; si ninguna responde, deja la página como estaba en
     *     vez de cambiarla por una pantalla de error.
     */
    private fun onPullRefresh() {
        swipe.tick(PullToRefreshLayout.Haptic.CONFIRM)
        refreshStartedAt = SystemClock.elapsedRealtime()
        armRefreshTimeout()
        web.evaluateJavascript("(window.__ruffs&&window.__ruffs.beforeRefresh)?window.__ruffs.beforeRefresh():null") { raw ->
            val info = runCatching { JSONObject(raw) }.getOrNull()
            if (info?.optBoolean("busy") == true) {
                endRefresh(immediate = true)
                swipe.tick(PullToRefreshLayout.Haptic.REJECT)
                toast("RUFFS está respondiendo; recarga cuando termine")
                return@evaluateJavascript
            }
            val current = serverUrl
            if (current == null) { endRefresh(immediate = true); return@evaluateJavascript }
            lifecycleScope.launch {
                if (ServerProbe.probe(current).ok) {
                    web.reload()
                    return@launch
                }
                val (best, _) = ServerProbe.pickBest(ServerConfig.servers(this@MainActivity), null)
                when {
                    best == null -> {
                        endRefresh(immediate = true)
                        swipe.tick(PullToRefreshLayout.Haptic.REJECT)
                        toast("No hay conexión con RUFFS. La página sigue aquí; inténtalo de nuevo.", long = true)
                    }
                    else -> {
                        if (best != current) toast("Conectando por ${best.removePrefix("http://")}")
                        loadServer(best, currentPath())
                    }
                }
            }
        }
    }

    private fun armRefreshTimeout() {
        refreshTimeout?.let { main.removeCallbacks(it) }
        refreshTimeout = Runnable { endRefresh(immediate = true) }.also { main.postDelayed(it, 20_000) }
    }

    /** Oculta el círculo; lo deja al menos ~½ s para que no parpadee. */
    private fun endRefresh(immediate: Boolean = false) {
        refreshTimeout?.let { main.removeCallbacks(it) }
        refreshTimeout = null
        val shown = SystemClock.elapsedRealtime() - refreshStartedAt
        val wait = if (immediate) 0L else (500L - shown).coerceAtLeast(0L)
        main.postDelayed({ swipe.isRefreshing = false }, wait)
    }

    // ── Llamadas del puente ───────────────────────────────────────────────────
    fun onThemeColor(view: WebView, color: Int) {
        if (view != web) return
        themeColor = color
        root.setBackgroundColor(color)
        statusOverlay.setBackgroundColor(color)
        val light = ColorUtils.calculateLuminance(color) > 0.5
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        // Círculo de recarga: un poco más claro (tema oscuro) u oscuro (tema claro) que el fondo
        val disc = if (light) ColorUtils.blendARGB(color, Color.BLACK, 0.06f) else ColorUtils.blendARGB(color, Color.WHITE, 0.10f)
        swipe.setProgressBackgroundColorSchemeColor(disc)
        statusOverlay.setProgressBackgroundColorSchemeColor(disc)
    }

    fun onThemeAccents(view: WebView, accent1: Int, accent2: Int) {
        if (view != web) return
        swipe.setColorSchemeColors(accent1, accent2)
        statusOverlay.setColorSchemeColors(accent1, accent2)
    }

    fun setPagePullAllowed(view: WebView, allowed: Boolean) {
        if (view == web) swipe.pageAllowsPull = allowed
    }

    fun setPageDragging(view: WebView, dragging: Boolean) {
        if (view == web) swipe.pageDragging = dragging
    }

    fun openSettings() {
        settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
    }

    fun vibrate(pattern: LongArray) {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (!v.hasVibrator()) return
        when {
            pattern.isEmpty() || pattern.all { it == 0L } -> v.cancel()
            pattern.size == 1 -> v.vibrate(VibrationEffect.createOneShot(pattern[0], VibrationEffect.DEFAULT_AMPLITUDE))
            // Web: [vibrar, pausa, vibrar…]; Android: [espera, vibrar, pausa…]
            else -> v.vibrate(VibrationEffect.createWaveform(longArrayOf(0) + pattern, -1))
        }
    }
}
