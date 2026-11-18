package app.flux.downloader.browser

import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.webkit.CookieManager
import android.view.View
import app.flux.downloader.settings.AppSettings
import android.webkit.ConsoleMessage
import android.os.Message
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import app.flux.downloader.engine.MediaProbe
import app.flux.downloader.engine.ProbeResult
import app.flux.downloader.engine.Quality
import app.flux.downloader.engine.MediaKind
import app.flux.downloader.ui.PlayerSource
import app.flux.downloader.engine.MediaInfo
import android.webkit.JavascriptInterface
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.flux.downloader.adblock.FilterManager
import app.flux.downloader.adblock.ResourceType
import app.flux.downloader.engine.FileDownload
import app.flux.downloader.engine.FileDownloads
import app.flux.downloader.engine.SiteGuard
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Una pestaña: su estado se guarda como Bundle y se restaura al volver a ella. */
class BrowserTab {
    var title by mutableStateOf("Nueva pestaña")
    var url by mutableStateOf<String?>(null)
    var state: Bundle? = null
}

/** Estado del navegador que sobrevive al cambio de pestaña de la app. */
class BrowserSession {
    val tabs = mutableStateListOf(BrowserTab())
    var activeIndex by mutableIntStateOf(0)
}

/**
 * Navegador endurecido sobre WebView.
 * La política de seguridad vive aquí: HTTPS obligatorio, esquemas peligrosos, popups,
 * redirecciones automáticas, permisos y bloqueo de anuncios/rastreadores con listas completas.
 */
class BrowserController(
    private val ctx: Context,
    private val prefs: BrowserPrefs,
    private val store: BrowserStore,
    private val session: BrowserSession,
) {
    private val mediaRegex = Regex(
        """\.(mp4|webm|m3u8|mpd|mp3|m4a|ogg|ts)(?:$|[?#])""",
        RegexOption.IGNORE_CASE
    )

    var webView: WebView? = null

    // ---- Estado observable por la UI ----
    var currentUrl by mutableStateOf<String?>(null)
    var pageTitle by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var showStart by mutableStateOf(true)
    var message by mutableStateOf<String?>(null)
    var blockedCount by mutableIntStateOf(0)
    var totalBlocked by mutableLongStateOf(prefs.totalBlocked)
    var pendingFile by mutableStateOf<FileDownload?>(null)

    var blockAds by mutableStateOf(prefs.blockAds)
        private set
    var blockRedirects by mutableStateOf(prefs.blockRedirects)
        private set
    var desktopMode by mutableStateOf(AppSettings.defaultDesktop.value)
        private set
    var siteProtected by mutableStateOf(true)
        private set

    val detected = mutableStateListOf<DetectedMedia>()
    val bookmarks = mutableStateListOf<Entry>().also { it.addAll(store.loadBookmarks()) }
    val history = mutableStateListOf<Entry>().also { it.addAll(store.loadHistory()) }

    val tabs get() = session.tabs
    val activeIndex get() = session.activeIndex

    var onDownloadRequest: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val active: BrowserTab get() = session.tabs[session.activeIndex.coerceIn(0, session.tabs.lastIndex)]

    @Volatile private var pageHost: String? = null
    @Volatile private var siteAllowed = false
    private var pageFinished = false
    private var upgradeCount = 0
    private var mobileUa = ""
    private var docStartHandler: ScriptHandler? = null
    private var docStartSupported = true

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probeGate = Semaphore(4)

    /** Menú que aparece al mantener pulsado un enlace o una imagen. */
    var linkMenu by mutableStateOf<LinkMenu?>(null)

    /** Resultado del extractor del sitio por página, para no repetirlo cada vez que se abre el selector. */
    val extractorCache = HashMap<String, MediaInfo>()

    @Volatile private var lastBackAt = 0L
    private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    private val hiddenVariants: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    /** Medios sacados de los datos de la página (sin que el reproductor los pida): no se listan hasta comprobar que existen. */
    private class Parked(val r: ProbeResult, val headers: Map<String, String>, val gen: Int)
    private val parked = HashMap<String, Parked>()
    private val verifiedOk: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    private val rejectedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    /** Cabeceras con las que el propio WebView pidió cada medio (sirven para repetir la medida del peso igual que él). */
    private val reqHeaders = ConcurrentHashMap<String, Map<String, String>>()
    @Volatile private var pageGen = 0
    @Volatile private var detectionUrl: String? = null
    @Volatile private var currentUa = ""
    @Volatile private var pageUrlNow: String? = null
    private var hasManifest = false

    /** Portada de la página/video (poster o og:image) para las descargas del detector. */
    @Volatile var pageThumb: String? = null

    /** Aviso con acción opcional ("Permitir", "Abrir"…) que la pantalla muestra como snackbar. */
    var notice by mutableStateOf<Notice?>(null)

    /** Registro de lo que falló o se bloqueó en la página actual (para el "Diagnóstico del sitio"). */
    val diag = mutableStateListOf<String>()

    /** Lo fija la pantalla: abre el reproductor de Flux con un stream del navegador. */
    var onPlayStream: ((PlayerSource) -> Unit)? = null

    /** Lo que necesita el reproductor de Flux para reproducir (y poder descargar) un video detectado. */
    fun playerSourceFor(m: DetectedMedia, startMs: Long = 0L): PlayerSource {
        val host = pageHost.orEmpty().removePrefix("www.")
        val title = m.title.ifBlank { pageTitle.ifBlank { host.ifBlank { "Video" } } }
        val info = MediaInfo(
            url = m.url,
            title = title,
            uploader = host.ifBlank { null },
            thumbnail = m.thumb ?: pageThumb,
            durationSec = m.durationSec,
            qualities = m.qualities,
            headers = m.headers,
            kind = m.kind,
            mime = m.mime,
        )
        return PlayerSource(Uri.parse(m.url), title, false, m.headers, m.kind, startMs, info)
    }

    /** Pausa el video de la página y abre el de Flux justo donde iba. */
    private fun openPlayer(target: DetectedMedia) {
        val playStream = onPlayStream ?: return
        val wv = webView
        if (wv == null) {
            playStream(playerSourceFor(target))
            return
        }
        wv.evaluateJavascript(PAUSE_AND_TIME_JS) { r ->
            val sec = r?.trim('"')?.toDoubleOrNull() ?: 0.0
            playStream(playerSourceFor(target, (sec * 1000).toLong()))
        }
    }

    /**
     * Botón de pantalla completa de la barra "Video detectado": el que se reproduce, o el único video de la
     * página. Si hay varios y ninguno se reproduce, se pide que se reproduzca el que se quiere ver.
     */
    fun openInFluxPlayer() {
        val clean = cleanDetected(detected.toList(), pageTitle)
        val target = clean.firstOrNull { it.playing } ?: clean.singleOrNull()
        if (target == null) {
            say(if (detected.isEmpty()) "Todavía no hay un video detectado" else "Reproduce el video que quieres ver en pantalla completa")
            return
        }
        openPlayer(target)
    }

    private class Meta(val thumb: String?, val title: String, val playing: Boolean, val height: Int = 0)
    private val metaByKey = ConcurrentHashMap<String, Meta>()
    /** Qué medios se hallaron juntos en la misma respuesta de la página (son calidades del mismo video). */
    private val groupByKey = ConcurrentHashMap<String, String>()
    @Volatile private var blobMeta: Meta? = null
    @Volatile private var userNavUntil = 0L
    private var desktopUa =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // ------------------------------------------------------------------
    // Creación del WebView endurecido
    // ------------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun createWebView(context: Context, backgroundColor: Int): WebView {
        val wv = WebView(context)
        webView = wv
        // Sin esto el WebView se pinta blanco (o negro) un instante al entrar a la pestaña: el "parpadeo"
        wv.setBackgroundColor(backgroundColor)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setAllowFileAccessFromFileURLs(false)
            setAllowUniversalAccessFromFileURLs(false)
            // Las ventanas emergentes se interceptan en onCreateWindow (se bloquean las de otros sitios)
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            // Como Chrome: bloquea contenido activo inseguro pero deja pasar imágenes/video http
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSafeBrowsingEnabled(true)
            setGeolocationEnabled(false)
            setSaveFormData(false)
            // Como Chrome: permite el autoplay silenciado (vistas previas, miniaturas animadas); el sonido
            // sin interacción previa lo frena el script de la página.
            mediaPlaybackRequiresUserGesture = false
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+ "), "")
            mobileUa = userAgentString
            // El UA de escritorio se deriva del real (misma versión de Chromium, sin desajustes con las client hints)
            desktopUa = mobileUa
                .replace(Regex("""\(Linux; Android[^)]*\)"""), "(X11; Linux x86_64)")
                .replace(" Mobile Safari", " Safari")
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Muchos reproductores y CDNs necesitan cookies de terceros; el bloqueo de rastreadores ya lo hace la red
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.addJavascriptInterface(FluxBridge(), "__fluxBridge")
        wv.addJavascriptInterface(ShieldBridge(), "__fluxShield")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, SNIFF_JS, setOf("*"))
        }
        wv.setOnLongClickListener { v ->
            val w = v as WebView
            val hit = w.hitTestResult
            when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    val u = hit.extra
                    if (u != null && u.startsWith("http")) { linkMenu = LinkMenu(u, null); true } else false
                }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    // Una imagen que es enlace: el destino y la imagen salen de requestFocusNodeHref
                    val h = Handler(Looper.getMainLooper()) { m ->
                        val href = m.data.getString("url")
                        val img = m.data.getString("src")
                        if (href != null && href.startsWith("http")) linkMenu = LinkMenu(href, img)
                        true
                    }
                    w.requestFocusNodeHref(h.obtainMessage())
                    true
                }
                WebView.HitTestResult.IMAGE_TYPE -> {
                    val u = hit.extra
                    if (u != null && u.startsWith("http")) { linkMenu = LinkMenu(null, u); true } else false
                }
                else -> false
            }
        }
        wv.webViewClient = SecureClient()
        wv.webChromeClient = SecureChrome()
        wv.setDownloadListener { url, ua, cd, mime, _ -> handleDownload(url, ua, cd, mime) }

        applyUa()
        applyGenericCosmetics()
        restoreActive()
        return wv
    }

    // ------------------------------------------------------------------
    // Navegación
    // ------------------------------------------------------------------
    fun go(input: String) {
        val target = toUrl(input) ?: return
        showStart = false
        load(target)
    }

    fun back() {
        lastBackAt = System.currentTimeMillis()
        if (webView?.canGoBack() == true) webView?.goBack() else home()
    }

    /** Pestaña nueva con ese enlace; en segundo plano se agrega sin cambiar a ella. */
    fun openInNewTab(url: String, background: Boolean = false) {
        if (background) {
            val t = BrowserTab()
            t.url = url
            t.title = Uri.parse(url).host.orEmpty().removePrefix("www.").ifBlank { "Pestaña" }
            session.tabs.add(t)
            postNotice("Pestaña abierta en segundo plano", "Ver") { switchTo(session.tabs.lastIndex) }
        } else {
            newTab()
            go(url)
        }
    }

    /** ¿Este destino está en las listas de anuncios? (se usa también para saltos de página y ventanas emergentes) */
    private fun looksLikeAd(url: String): Boolean =
        FilterManager.engine.shouldBlock(url, pageHost, ResourceType.SUBDOC)

    /** Tras una redirección bloqueada justo después de volver atrás: se retrocede otra vez (trampas de historial). */
    private fun backIfTrap(now: Long) {
        if (now - lastBackAt < 2_500) {
            ui {
                val w = webView
                if (w != null && w.canGoBack()) {
                    lastBackAt = System.currentTimeMillis()
                    w.goBack()
                }
            }
        }
    }

    fun forward() { webView?.goForward() }
    fun stop() { webView?.stopLoading(); loading = false }
    fun reload() { currentUrl?.let { load(it) } ?: webView?.reload() }

    fun home() {
        webView?.stopLoading()
        webView?.loadUrl("about:blank")
        showStart = true
        currentUrl = null
        pageTitle = ""
        active.url = null
        active.title = "Nueva pestaña"
        active.state = null
        setPage(null)
    }

    // ------------------------------------------------------------------
    // Pestañas
    // ------------------------------------------------------------------
    fun saveActive() {
        val t = active
        if (showStart) {
            t.url = null
            t.state = null
            return
        }
        webView?.let { wv ->
            val b = Bundle()
            wv.saveState(b)
            t.state = b
        }
        t.url = currentUrl
        t.title = pageTitle.ifBlank { currentUrl.orEmpty() }
    }

    fun restoreActive() {
        val t = active
        showStart = t.url == null
        currentUrl = t.url
        pageTitle = if (showStart) "" else t.title
        setPage(t.url)
        resetDetection(t.url)
        val wv = webView ?: return
        val st = t.state
        val u = t.url
        when {
            st != null -> if (wv.restoreState(st) == null && u != null) load(u)
            u != null -> load(u)
            else -> wv.loadUrl("about:blank")
        }
    }

    fun switchTo(i: Int) {
        if (i == session.activeIndex || i !in session.tabs.indices) return
        saveActive()
        session.activeIndex = i
        restoreActive()
    }

    fun newTab() {
        saveActive()
        session.tabs.add(BrowserTab())
        session.activeIndex = session.tabs.lastIndex
        restoreActive()
    }

    fun closeTab(i: Int) {
        if (i !in session.tabs.indices) return
        if (session.tabs.size == 1) {
            session.tabs[0].apply { url = null; title = "Nueva pestaña"; state = null }
            session.activeIndex = 0
            restoreActive()
            return
        }
        val wasActive = i == session.activeIndex
        session.tabs.removeAt(i)
        if (i < session.activeIndex) {
            session.activeIndex--
        } else if (wasActive) {
            session.activeIndex = session.activeIndex.coerceAtMost(session.tabs.lastIndex)
            restoreActive()
        }
    }

    // ------------------------------------------------------------------
    // Ajustes y privacidad
    // ------------------------------------------------------------------
    fun updateBlockAds(v: Boolean) {
        blockAds = v
        prefs.blockAds = v
        applyGenericCosmetics()
    }

    fun updateBlockRedirects(v: Boolean) {
        blockRedirects = v
        prefs.blockRedirects = v
    }

    fun updateSiteProtected(on: Boolean) {
        val h = pageHost ?: return
        prefs.setSiteProtected(h, on)
        setPage(currentUrl)
        applyGenericCosmetics()
        reload()
    }

    /** Escritorio por defecto, salvo que el usuario haya elegido otra cosa para ese sitio. */
    private fun wantsDesktop(host: String?): Boolean {
        if (host == null) return AppSettings.defaultDesktop.value
        val d = registrable(host)
        return when {
            d in prefs.mobileSites -> false
            d in prefs.desktopSites -> true
            else -> AppSettings.defaultDesktop.value
        }
    }

    /** Cambia a escritorio/móvil y lo recuerda para este sitio (no hay que repetirlo cada vez). */
    fun toggleDesktop() {
        val on = !desktopMode
        pageHost?.let { prefs.setSiteMode(registrable(it), on) }
        desktopMode = on
        applyUa()
        reload()
    }

    fun clearData() {
        webView?.apply { clearCache(true); clearHistory() }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        say("Datos de navegación borrados")
    }

    /** Botón de pánico: borra datos, historial y cierra todas las pestañas. */
    fun panic() {
        clearData()
        clearHistory()
        session.tabs.clear()
        session.tabs.add(BrowserTab())
        session.activeIndex = 0
        restoreActive()
        say("Todo borrado")
    }

    // ------------------------------------------------------------------
    // Marcadores e historial
    // ------------------------------------------------------------------
    fun isBookmarked(): Boolean = currentUrl?.let { u -> bookmarks.any { it.url == u } } == true

    fun toggleBookmark() {
        val u = currentUrl ?: return
        val existing = bookmarks.firstOrNull { it.url == u }
        if (existing != null) bookmarks.remove(existing)
        else bookmarks.add(0, Entry(pageTitle.ifBlank { u }, u, System.currentTimeMillis()))
        store.saveBookmarks(bookmarks)
    }

    fun removeBookmark(e: Entry) {
        bookmarks.remove(e)
        store.saveBookmarks(bookmarks)
    }

    fun clearHistory() {
        history.clear()
        store.saveHistory(history)
    }

    private fun addHistory(url: String, title: String) {
        if (history.firstOrNull()?.url == url) return
        history.add(0, Entry(title.ifBlank { url }, url, System.currentTimeMillis()))
        while (history.size > 500) history.removeAt(history.lastIndex)
        store.saveHistory(history)
    }

    // ------------------------------------------------------------------
    // Descargas de archivos
    // ------------------------------------------------------------------
    fun confirmPendingFile() {
        pendingFile?.let(::startFile)
        pendingFile = null
    }

    private fun startFile(f: FileDownload) {
        say(if (FileDownloads.enqueue(ctx, f)) "Descargando archivo…" else "No se pudo iniciar la descarga")
    }

    private fun handleDownload(url: String, ua: String?, cd: String?, mime: String?) {
        // Única restricción: YouTube
        if (SiteGuard.isBlocked(url)) {
            say("Este sitio no es compatible con Flux")
            return
        }
        val isMedia = mime?.startsWith("video/") == true || mime?.startsWith("audio/") == true ||
            mediaRegex.containsMatchIn(url)
        if (isMedia) {
            ui { onDownloadRequest?.invoke(url) }
            return
        }
        val file = FileDownload(url, ua, cd, mime)
        if (FileDownloads.isRisky(file)) ui { pendingFile = file } else startFile(file)
    }

    // ------------------------------------------------------------------
    // Utilidades internas
    // ------------------------------------------------------------------
    private fun load(url: String) {
        pageFinished = false
        userNavUntil = System.currentTimeMillis() + 8_000
        val want = wantsDesktop(runCatching { Uri.parse(url).host }.getOrNull()?.lowercase())
        if (want != desktopMode) {
            desktopMode = want
            applyUa()
        }
        webView?.loadUrl(url, mapOf("Sec-GPC" to "1", "DNT" to "1"))
    }

    private fun toUrl(input: String): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("https://", true)) return t
        if (t.startsWith("http://", true)) return "https://" + t.substring(7)
        val looksLikeHost = !t.contains(' ') && t.contains('.') && !t.startsWith(".") && !t.contains(':')
        return if (looksLikeHost) "https://$t" else "https://duckduckgo.com/?q=" + Uri.encode(t)
    }

    private fun applyUa() {
        currentUa = if (desktopMode) desktopUa else mobileUa
        webView?.settings?.apply {
            userAgentString = if (desktopMode) desktopUa else mobileUa
            // Como Chrome: se respeta <meta viewport> también en móvil
            useWideViewPort = true
            loadWithOverviewMode = desktopMode
        }
    }

    private fun setPage(url: String?) {
        val h = url?.let { runCatching { Uri.parse(it).host?.lowercase() }.getOrNull() }
        pageHost = h
        siteAllowed = h != null && prefs.allowedSites.any { h == it || h.endsWith(".$it") }
        siteProtected = !siteAllowed
    }

    private fun say(msg: String) { main.post { message = msg } }

    private fun postNotice(text: String, label: String? = null, action: (() -> Unit)? = null) {
        main.post { notice = Notice(text, label, action) }
    }

    private fun diagAdd(msg: String) {
        main.post {
            diag.add(0, msg.take(220))
            while (diag.size > 80) diag.removeAt(diag.size - 1)
        }
    }
    private fun ui(block: () -> Unit) { main.post(block) }

    private fun refreshNav(view: WebView) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }

    private fun showError(view: WebView, title: String, detail: String) {
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="background:#0A0A0A;color:#fff;font-family:sans-serif;padding:32px;text-align:center">
            <h2 style="color:#FF6A00">${TextUtils.htmlEncode(title)}</h2>
            <p style="color:#B5B5B5">${TextUtils.htmlEncode(detail)}</p></body></html>
        """.trimIndent()
        view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    // ------------------------------------------------------------------
    // Detección de medios (instantánea y sin lista de sitios)
    // ------------------------------------------------------------------
    private fun resetDetection(url: String?) {
        pageGen++
        detectionUrl = url
        pageUrlNow = url
        hasManifest = false
        pageThumb = null
        metaByKey.clear()
        groupByKey.clear()
        blobMeta = null
        // Que el script vuelva a informar lo que ya había enviado (navegación interna de apps de una sola página)
        webView?.evaluateJavascript(
            "(function(){var s=window.__fluxSent;if(s)for(var k in s)delete s[k];window.__fluxThumb=null;window.__fluxHarvested=0;window.__fluxScan&&window.__fluxScan();})()",
            null,
        )
        seen.clear()
        hiddenVariants.clear()
        parked.clear()
        verifiedOk.clear()
        rejectedKeys.clear()
        reqHeaders.clear()
        detected.clear()
    }

    private fun headersFor(url: String): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = currentUa.ifBlank { mobileUa }
        pageUrlNow?.let { h["Referer"] = it }
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { h["Cookie"] = it }
        return h
    }

    /**
     * Un posible medio (por extensión en la red o reportado por el reproductor de la página).
     * Se lee en segundo plano (HEAD / lista HLS / MPD DASH) y, si es válido, aparece en [detected].
     */
    private fun mergeMeta(key: String, poster: String?, title: String?, playing: Boolean?, height: Int = 0) {
        metaByKey.compute(key) { _, old ->
            val pl = playing ?: old?.playing ?: false
            Meta(
                thumb = poster?.takeIf { it.startsWith("http") } ?: old?.thumb,
                title = title?.takeIf { it.isNotBlank() } ?: old?.title.orEmpty(),
                playing = pl,
                height = if (!pl) 0 else if (height > 0) height else old?.height ?: 0,
            )
        }
    }

    /** Solo uno se está reproduciendo a la vez. */
    private fun clearPlayingExcept(key: String?) {
        metaByKey.replaceAll { k, v -> if (k != key && v.playing) Meta(v.thumb, v.title, false) else v }
    }

    /** Vuelca portada/título/"reproduciendo" a las detecciones ya listadas (hilo principal). */
    private fun refreshFromMeta() {
        for (i in detected.indices) {
            val m = detected[i]
            val meta = metaByKey[m.id] ?: continue
            if (m.title != meta.title || m.thumb != meta.thumb || m.playing != meta.playing || m.playingHeight != meta.height) {
                detected[i] = m.copy(title = meta.title, thumb = meta.thumb, playing = meta.playing, playingHeight = meta.height)
            }
        }
    }

    /** Un <video> con blob: (streaming por MSE) se reproduce: el medio es la última lista de streaming detectada. */
    private fun onPlayingBlob(poster: String?, title: String?, height: Int = 0) {
        ui {
            val target = detected.lastOrNull { it.kind != MediaKind.DIRECT } ?: detected.lastOrNull()
            if (target != null) {
                clearPlayingExcept(target.id)
                mergeMeta(target.id, poster, title, true, height)
                refreshFromMeta()
            } else {
                blobMeta = Meta(poster?.takeIf { it.startsWith("http") }, title.orEmpty(), true, height)
            }
        }
    }

    private fun onCandidate(
        url: String,
        hintHeight: Int = 0,
        hintDuration: Double = 0.0,
        trusted: Boolean = false,
        fromNet: Boolean = false,
        fromPage: Boolean = false,
    ) {
        if (!MediaProbe.isCandidate(url, trusted) || SiteGuard.isBlocked(url)) return
        // Piezas sueltas que la página pide por fetch/XHR cuando ya hay una lista de streaming: son fragmentos
        if (fromNet && hasManifest) return
        val key = MediaProbe.normalize(url)
        if (key in hiddenVariants || !seen.add(key)) return
        // Un anuncio en video (pre-roll, banner animado) no es un video de la página: las listas de filtros lo conocen
        if (blockAds && !siteAllowed && FilterManager.engine.shouldBlock(url, pageHost, ResourceType.MEDIA)) {
            diagAdd("Anuncio ignorado como video: $url")
            return
        }
        val gen = pageGen
        val headers = headersFor(url)
        probeScope.launch {
            val r = probeGate.withPermit { MediaProbe.probe(url, headers, hintHeight, hintDuration) } ?: return@launch
            ui { completeDetect(r, key, headers, gen, fromPage) }
        }
    }

    private fun completeDetect(r: ProbeResult, key: String, headers: Map<String, String>, gen: Int, fromPage: Boolean) {
        if (gen != pageGen) return
        // Si se descubrió la lista maestra, el medio pasa a ser ella (y la lista original queda oculta)
        val id = MediaProbe.normalize(r.url)
        if (id != key) {
            seen.add(id)
            metaByKey[key]?.let { metaByKey.putIfAbsent(id, it) }
        }
        if (r.kind != MediaKind.DIRECT) hasManifest = true
        r.hidden.forEach { hiddenVariants.add(MediaProbe.normalize(it)) }
        detected.removeAll { MediaProbe.normalize(it.url) in hiddenVariants }
        if (id in hiddenVariants || detected.any { it.id == id } || detected.size >= 100) return
        val bm = blobMeta
        if (bm != null && r.kind != MediaKind.DIRECT) {
            clearPlayingExcept(id)
            mergeMeta(id, bm.thumb, bm.title, true, bm.height)
            blobMeta = null
        }
        val meta = metaByKey[id]
        detected.add(
            DetectedMedia(
                id, r.url, r.kind, r.durationSec, r.qualities.map { if (fromPage) it.copy(declared = true) else it }, r.audioOnly, headers, r.mime,
                title = meta?.title.orEmpty(), thumb = meta?.thumb, playing = meta?.playing ?: false,
                playingHeight = meta?.height ?: 0,
                group = groupByKey[key] ?: groupByKey[id],
            ),
        )
    }

    /** Mientras se cambia la calidad del reproductor de la página por dentro no se permite salir de la página ni abrir ventanas. */
    @Volatile private var autoUntil = 0L
    var harvesting by mutableStateOf(false)
        private set

    /** La pestaña del navegador dejó de verse: se pausa el sonido/video de la página y el WebView. */
    fun onHidden() {
        webView?.evaluateJavascript(
            "(function(){try{document.querySelectorAll('video,audio').forEach(function(v){try{v.pause()}catch(e){}})}catch(e){}})()",
            null,
        )
        webView?.onPause()
    }

    fun onShown() {
        webView?.onResume()
    }

    /** Pide a la página recorrer las calidades de su reproductor para descubrir la URL real de cada una. */
    fun harvestQualities() {
        webView?.evaluateJavascript("window.__fluxHarvest&&window.__fluxHarvest()", null)
    }

    private val noReplayHeaders = setOf("range", "host", "cookie", "content-length", "if-range", "if-none-match", "if-modified-since")

    private fun onVerified(key: String, ok: Boolean, h: Int, d: Double) {
        if (!ok) {
            rejectedKeys.add(key)
            parked.remove(key)
            detected.removeAll { it.id == key && it.qualities.all { q -> q.bytes == null } }
            diagAdd("Calidad que el navegador no pudo abrir (se oculta): $key")
            return
        }
        verifiedOk.add(key)
        val p = parked.remove(key) ?: return
        if (p.gen != pageGen) return
        val q0 = p.r.qualities.firstOrNull()
        val height = q0?.height?.takeIf { it > 0 } ?: h
        val dur = if (p.r.durationSec > 0) p.r.durationSec else d.toLong()
        val seenHeaders = (reqHeaders[key] ?: emptyMap()).filterKeys { it.lowercase() !in noReplayHeaders }
        val hdr = p.headers + seenHeaders
        val gen = p.gen
        probeScope.launch {
            // Se repite la medida con las mismas cabeceras que usó el navegador; si aun así no hay peso, se estima después
            val (len, type) = MediaProbe.headInfo(p.r.url, hdr)
            val mime = type?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() } ?: q0?.mime
            val q = Quality(height, len, false, url = p.r.url, kind = MediaKind.DIRECT, mime = mime)
            val r2 = ProbeResult(p.r.url, MediaKind.DIRECT, dur, listOf(q), audioOnly = p.r.audioOnly, mime = mime)
            ui { completeDetect(r2, key, hdr, gen, false) }
        }
    }

    /** Entrega al script de escudos las reglas avanzadas (scriptlets y cosméticos procedurales) del sitio. */
    private inner class ShieldBridge {
        @JavascriptInterface
        fun rules(host: String?): String {
            if (!blockAds || siteAllowed || host.isNullOrBlank()) return "{}"
            return runCatching { FilterManager.engine.shieldFor(host.lowercase()) }.getOrDefault("{}")
        }
    }

    /** Puente JS → app: lo que el reproductor de la página sabe (src real, resolución, duración). */
    private inner class FluxBridge {
        @JavascriptInterface
        fun media(url: String?, w: Int, h: Int, dur: Double, src: String?) {
            if (url == null) return
            val d = if (dur.isNaN() || dur.isInfinite()) 0.0 else dur
            onCandidate(url, hintHeight = h, hintDuration = d, trusted = true, fromNet = src == "net")
        }

        @JavascriptInterface
        fun media2(url: String?, w: Int, h: Int, dur: Double, src: String?, poster: String?, title: String?, playing: Boolean) {
            if (url == null) return
            val d = if (dur.isNaN() || dur.isInfinite()) 0.0 else dur
            if (MediaProbe.isCandidate(url, true)) {
                val key = MediaProbe.normalize(url)
                if (playing) clearPlayingExcept(key)
                mergeMeta(key, poster, title, if (playing) true else null, if (playing) h else 0)
                ui { refreshFromMeta() }
            }
            onCandidate(url, hintHeight = h, hintDuration = d, trusted = true, fromNet = src == "net", fromPage = src == "page")
        }

        /** El navegador intentó abrir (precargar los metadatos de) una calidad que la página declara pero nadie ha reproducido. */
        @JavascriptInterface
        fun verified(url: String?, ok: Int, h: Int, dur: Double) {
            if (url.isNullOrBlank()) return
            val key = MediaProbe.normalize(url)
            val d = if (dur.isNaN() || dur.isInfinite()) 0.0 else dur
            ui { onVerified(key, ok == 1, h, d) }
        }

        @JavascriptInterface
        fun playing(url: String?, poster: String?, title: String?, h: Int, dur: Double) {
            if (url.isNullOrBlank()) {
                onPlayingBlob(poster, title, h)
            } else {
                val key = MediaProbe.normalize(url)
                clearPlayingExcept(key)
                mergeMeta(key, poster, title, true, h)
                ui { refreshFromMeta() }
            }
        }

        @JavascriptInterface
        fun note(msg: String?) {
            if (!msg.isNullOrBlank()) ui { diagAdd(msg.take(220)) }
        }

        @JavascriptInterface
        fun auto(on: Int) {
            autoUntil = if (on == 1) System.currentTimeMillis() + 40_000L else 0L
            ui { harvesting = on == 1 }
        }

        @JavascriptInterface
        fun grp(url: String?, gid: String?) {
            if (!url.isNullOrBlank() && !gid.isNullOrBlank()) groupByKey[MediaProbe.normalize(url)] = gid
        }

        @JavascriptInterface
        fun paused() {
            metaByKey.replaceAll { _, v -> if (v.playing) Meta(v.thumb, v.title, false) else v }
            blobMeta = null
            ui { refreshFromMeta() }
        }

        @JavascriptInterface
        fun thumb(url: String?) {
            if (url != null && url.startsWith("http")) pageThumb = url
        }
    }

    /** Quita los muros de "usa la app", banners y capas que tapan la página, y devuelve el scroll. */
    fun removeOverlays() {
        val js = """(function(){var n=0,vw=innerWidth,vh=innerHeight;
            document.querySelectorAll('body *').forEach(function(e){try{var s=getComputedStyle(e);
            if((s.position==='fixed'||s.position==='sticky')&&parseInt(s.zIndex||'0',10)>=5){var r=e.getBoundingClientRect();
            if(r.width>=vw*0.5&&r.height>=vh*0.2&&!/^(VIDEO|AUDIO)${'$'}/.test(e.tagName)&&!e.querySelector('video')){e.remove();n++;}}}catch(x){}});
            [document.documentElement,document.body].forEach(function(b){if(b){b.style.setProperty('overflow','auto','important');b.style.setProperty('position','static','important');}});
            return n;})()"""
        webView?.evaluateJavascript(js) { r ->
            val n = r?.trim('"')?.toIntOrNull() ?: 0
            say(if (n > 0) "Se quitaron $n elementos que tapaban la página" else "No se encontró nada que quitar")
        }
    }

    private fun registrable(host: String): String {
        val h = host.lowercase()
        val parts = h.split('.')
        if (parts.size <= 2) return h
        val sld = setOf("co", "com", "org", "net", "gov", "edu", "ac")
        val n = if (parts[parts.size - 2] in sld && parts.last().length == 2) 3 else 2
        return parts.takeLast(n).joinToString(".")
    }

    private fun sameSite(a: String, b: String) = a.isNotEmpty() && b.isNotEmpty() && registrable(a) == registrable(b)

    /** WebView no informa el tipo de recurso: se deduce por extensión y cabecera Accept. */
    private fun inferType(req: WebResourceRequest): Int {
        val path = req.url.path?.lowercase().orEmpty()
        when (path.substringAfterLast('.', "")) {
            "js", "mjs" -> return ResourceType.SCRIPT
            "css" -> return ResourceType.CSS
            "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp" -> return ResourceType.IMAGE
            "woff", "woff2", "ttf", "otf", "eot" -> return ResourceType.FONT
            "mp4", "webm", "m4s", "mp3", "m4a", "ogg", "m3u8", "mpd", "ts" -> return ResourceType.MEDIA
        }
        val accept = req.requestHeaders["Accept"].orEmpty()
        return when {
            accept.contains("text/css") -> ResourceType.CSS
            accept.contains("text/html") -> ResourceType.SUBDOC
            accept.contains("image/") -> ResourceType.IMAGE
            else -> ResourceType.UNKNOWN
        }
    }

    // ------------------------------------------------------------------
    // Filtrado cosmético (ocultar huecos y banners que quedan tras bloquear la red)
    // ------------------------------------------------------------------
    private fun styleScript(id: String, css: String): String =
        "(function(){try{var s=document.getElementById('$id');if(!s){s=document.createElement('style');" +
            "s.id='$id';(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent=${JSONObject.quote(css)};}catch(e){}})();"

    /** CSS genérico: se inyecta antes de que cargue la página, para que no se vea parpadear. */
    fun applyGenericCosmetics() {
        val wv = webView ?: return
        docStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (!docStartSupported) return
        docStartHandler?.remove()
        docStartHandler = null
        if (!blockAds) return
        val css = FilterManager.engine.genericCss
        val allowed = JSONArray(prefs.allowedSites.toList()).toString()
        val cssScript = if (css.isEmpty()) "" else
            "(function(){try{if($allowed.indexOf(location.hostname)>=0)return;" +
                "function f(){var s=document.createElement('style');s.id='__flux_g';" +
                "s.textContent=${JSONObject.quote(css)};(document.head||document.documentElement).appendChild(s);}" +
                "if(document.documentElement)f();else document.addEventListener('DOMContentLoaded',f);}catch(e){}})();"
        // Escudos: scriptlets y filtros avanzados de las listas + limpieza de huecos de anuncio (se omite en sitios permitidos)
        val shield = "(function(){try{if($allowed.indexOf(location.hostname)>=0)return;" + SHIELD_JS + "}catch(e){}})();"
        docStartHandler = WebViewCompat.addDocumentStartJavaScript(wv, cssScript + "\n" + shield, setOf("*"))
    }

    private fun applySiteCosmetics(view: WebView) {
        if (!blockAds || siteAllowed) return
        val host = pageHost ?: return
        val engine = FilterManager.engine
        val css = engine.cssForHost(host) + if (docStartSupported) "" else engine.genericCss
        if (css.isNotEmpty()) view.evaluateJavascript(styleScript("__flux_d", css), null)
    }

    // ------------------------------------------------------------------
    // WebViewClient: política de seguridad
    // ------------------------------------------------------------------
    private inner class SecureClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()

            if (scheme == "about") return false
            if (request.isForMainFrame && System.currentTimeMillis() < autoUntil) {
                diagAdd("Navegación bloqueada mientras se buscaban calidades: $uri")
                return true
            }

            // 1) HTTPS obligatorio: se sube de http a https
            if (scheme == "http") {
                if (!request.isForMainFrame) return false
                if (request.isForMainFrame) {
                    if (++upgradeCount > 5) {
                        upgradeCount = 0
                        showError(view, "No se pudo conectar de forma segura", "Este sitio no ofrece HTTPS.")
                    } else {
                        val https = uri.buildUpon().scheme("https").build().toString()
                        ui { load(https) }
                    }
                }
                return true
            }

            // 2) Otros esquemas (intent://, market://, file://, javascript:…) bloqueados
            if (scheme != "https") {
                if (request.isForMainFrame) say("Se bloqueó un intento de abrir otra app o archivo")
                return true
            }

            // 3) Saltos de sitio que no pidió el usuario: redirecciones del script, anuncios al tocar "play"…
            if (request.isForMainFrame) {
                val from = pageHost
                val toHost = uri.host.orEmpty()
                if (blockRedirects && !siteAllowed && from != null && !sameSite(from, toHost)) {
                    val now = System.currentTimeMillis()
                    val target = uri.toString()
                    val hit = view.hitTestResult
                    // Solo cuenta como "lo pidió el usuario" un toque en un enlace real hacia ese destino
                    val tapped = request.hasGesture() && (
                        (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE &&
                            hit.extra?.substringBefore('#') == target.substringBefore('#')) ||
                            hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                        )
                    // …o un tramo de la redirección de una carga que él inició hace instantes
                    val userDriven = tapped || (request.isRedirect && now < userNavUntil)
                    // Los destinos de las listas de anuncios se bloquean siempre, aunque vengan de un toque
                    if (looksLikeAd(target) || !userDriven) {
                        diagAdd("Redirección bloqueada: $target")
                        postNotice("Redirección bloqueada", "Permitir") { load(target) }
                        backIfTrap(now)
                        return true
                    }
                    userNavUntil = now + 6_000
                }
                // Modo escritorio recordado por sitio
                val want = wantsDesktop(toHost)
                if (want != desktopMode) {
                    desktopMode = want
                    applyUa()
                    val t = uri.toString()
                    ui { load(t) }
                    return true
                }
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url
            if (blockAds && !siteAllowed && !request.isForMainFrame) {
                val type = inferType(request)
                val ph = pageHost
                val firstParty = ph != null && sameSite(uri.host.orEmpty(), ph)
                // Solo se protege lo que rompe reproductores y páginas: medios y llamadas (fetch/XHR) PROPIOS del sitio.
                // Los scripts y marcos propios sí se filtran: ahí viven muchos anuncios invasivos.
                val protectedRequest = firstParty && (type == ResourceType.MEDIA || type == ResourceType.UNKNOWN) &&
                    !adLikePath(uri.toString())
                if (!protectedRequest && FilterManager.engine.shouldBlock(uri.toString(), pageHost, type)) {
                    if (type == ResourceType.IMAGE || type == ResourceType.MEDIA) diagAdd("Bloqueado por filtros: $uri")
                    ui {
                        blockedCount++
                        totalBlocked++
                    }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
            }
            val us = uri.toString()
            if (looksMediaUrl(us) && reqHeaders.size < 80) reqHeaders[MediaProbe.normalize(us)] = request.requestHeaders ?: emptyMap()
            onCandidate(us)
            return null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageFinished = false
            loading = true
            progress = 0f
            diag.clear()
            resetDetection(url)
            if (url != null && url.startsWith("https://")) {
                if (url != currentUrl) blockedCount = 0
                currentUrl = url
                active.url = url
                showStart = false
                setPage(url)
            }
            refreshNav(view)
            applySiteCosmetics(view)
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            applySiteCosmetics(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            loading = false
            pageFinished = true
            upgradeCount = 0
            refreshNav(view)
            prefs.totalBlocked = totalBlocked
            // Respaldo si el script de inicio no está disponible, y nuevo escaneo de los <video> ya cargados
            view.evaluateJavascript("$SNIFF_JS;window.__fluxScan&&window.__fluxScan();", null)
            if (url != null && url.startsWith("https://") && !showStart) addHistory(url, pageTitle)
            applySiteCosmetics(view)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            // Navegación interna de apps de una sola página (X, TikTok…): otro contenido, otra detección
            if (url != null && url.startsWith("https://") && url != detectionUrl) resetDetection(url)
            refreshNav(view)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            showError(view, "Conexión no segura", "El certificado de este sitio no es válido. Se canceló la carga para protegerte.")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                showError(view, "No se pudo cargar la página", error.description?.toString().orEmpty())
            } else {
                diagAdd("Error de red (${error.errorCode} ${error.description}): ${request.url}")
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (!request.isForMainFrame && errorResponse.statusCode >= 400) {
                diagAdd("HTTP ${errorResponse.statusCode}: ${request.url}")
            }
        }

        override fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest,
            threatType: Int,
            callback: SafeBrowsingResponse,
        ) {
            diagAdd("Navegación segura bloqueó (tipo $threatType): ${request.url}")
            callback.backToSafety(true)
            say("Sitio peligroso bloqueado por Navegación segura")
        }
    }

    // ------------------------------------------------------------------
    // WebChromeClient: permisos denegados por defecto
    // ------------------------------------------------------------------
    private inner class SecureChrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress / 100f
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            pageTitle = title.orEmpty()
            if (!showStart && !title.isNullOrBlank()) active.title = title
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        /**
         * Pantalla completa del video de la página: se abre en el reproductor de Flux (con el punto donde iba).
         * Si no hay un medio detectado que reproducir, la pantalla completa se rechaza y se queda en la página.
         */
        override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
            val clean = cleanDetected(detected.toList(), pageTitle)
            val target = clean.firstOrNull { it.playing } ?: clean.singleOrNull()
            callback.onCustomViewHidden()
            if (AppSettings.ownPlayer.value && target != null) openPlayer(target)
        }

        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                diagAdd("JS: ${m.message()} · ${m.sourceId().orEmpty().substringAfterLast('/')}:${m.lineNumber()}")
            }
            return false
        }

        /**
         * Ventanas emergentes: las del mismo sitio abiertas por un toque se cargan aquí; las de otros sitios
         * (anuncios al tocar "play") se bloquean y se avisa, con opción de abrirlas.
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (System.currentTimeMillis() < autoUntil) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val temp = WebView(view.context)
            temp.settings.javaScriptEnabled = false
            val hit = view.hitTestResult
            val tappedHref = if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) hit.extra else null
            val tappedImageLink = hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            var handled = false
            fun decide(url: String?) {
                if (handled || url == null || !url.startsWith("http")) return
                handled = true
                ui {
                    val enforce = blockRedirects && !siteAllowed
                    val tapped = isUserGesture &&
                        (tappedImageLink || tappedHref?.substringBefore('#') == url.substringBefore('#'))
                    when {
                        // Con el bloqueo desactivado (o en un sitio de confianza) se abren siempre, en pestaña nueva
                        !enforce -> openInNewTab(url)
                        // Un enlace que el usuario tocó (target=_blank) es legítimo, salvo que sea un anuncio
                        tapped && !looksLikeAd(url) -> openInNewTab(url)
                        else -> {
                            diagAdd("Ventana emergente bloqueada: $url")
                            postNotice("Ventana emergente bloqueada", "Abrir") { openInNewTab(url) }
                        }
                    }
                    temp.stopLoading()
                    temp.destroy()
                }
            }
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                    decide(req.url.toString())
                    return true
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    decide(url)
                }
            }
            main.postDelayed({
                if (!handled) {
                    handled = true
                    runCatching { temp.destroy() }
                }
            }, 5_000)
            transport.webView = temp
            resultMsg.sendToTarget()
            return true
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }
    }
}

/** Pausa el video de la página y devuelve su posición (segundos) para seguir en el reproductor de Flux. */
private const val PAUSE_AND_TIME_JS =
    "(function(){var vs=[].slice.call(document.querySelectorAll('video'));" +
        "var v=vs.filter(function(x){return !x.paused&&!x.ended})[0]||vs[0];if(!v)return 0;" +
        "var t=v.currentTime||0;try{v.pause()}catch(e){}return t;})()"

/** Lo que se muestra al mantener pulsado: un enlace, una imagen, o una imagen que es enlace. */
class LinkMenu(val link: String?, val image: String?)

/** Aviso del navegador con acción opcional. */
class Notice(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

private val SHIELD_JS = """(function(){try{
var SH=window.__fluxShield;if(!SH||window.__fluxShieldOn)return;window.__fluxShieldOn=1;
var R=null;try{R=JSON.parse(SH.rules(location.hostname))}catch(e){}
function rx(p){if(!p)return null;if(p.charAt(0)==='/'&&p.lastIndexOf('/')>0){var i=p.lastIndexOf('/');try{return new RegExp(p.slice(1,i),p.slice(i+1))}catch(e){return null}}
return {test:function(s){return String(s).indexOf(p)>=0}};}
function isObj(x){return x&&(typeof x==='object'||typeof x==='function');}
function trap(path,mode,needle){var parts=path.split('.'),n=needle?rx(needle):null;
(function go(o,i){var k=parts[i],last=i===parts.length-1;
if(last){var val=o[k];try{Object.defineProperty(o,k,{configurable:true,
get:function(){if(mode==='read'||mode==='acs'){var cs=document.currentScript;if(mode==='read'||(cs&&(!n||n.test(cs.textContent||''))))throw new ReferenceError('flux');}return val;},
set:function(x){if(mode==='write')throw new ReferenceError('flux');val=x;}});}catch(e){}return;}
var cur=o[k];if(isObj(cur)){go(cur,i+1);}else{var hold=cur;try{Object.defineProperty(o,k,{configurable:true,get:function(){return hold},
set:function(x){hold=x;if(isObj(x))go(x,i+1);}});}catch(e){}}})(window,0);}
var CONST={'true':true,'false':false,'null':null,'undefined':undefined,'emptyStr':'','emptyArr':[],'emptyObj':{},'0':0,'1':1,'-1':-1,'noopFunc':function(){},'trueFunc':function(){return true},'falseFunc':function(){return false},'noopPromise':function(){return Promise.resolve()}};
function setConst(path,v){if(!(v in CONST)){if(!isNaN(parseFloat(v)))CONST[v]=parseFloat(v);else return;}var val=CONST[v],parts=path.split('.');
(function go(o,i){var k=parts[i];if(i===parts.length-1){try{Object.defineProperty(o,k,{configurable:true,get:function(){return val},set:function(){}});}catch(e){}return;}
var cur=o[k];if(isObj(cur)){go(cur,i+1);}else{var hold=cur;try{Object.defineProperty(o,k,{configurable:true,get:function(){return hold},set:function(x){hold=x;if(isObj(x))go(x,i+1);}});}catch(e){}}})(window,0);}
function wrapTimer(name,needle,delay){var n=rx(needle),d=delay?parseInt(delay,10):0,orig=window[name];
window[name]=function(f,t){try{var s=typeof f==='function'?f.toString():String(f);if((!needle||n.test(s))&&(!delay||t===d))return 0;}catch(e){}return orig.apply(this,arguments);};}
function noWindowOpen(){window.open=function(){return null};}
function defuse(type,needle){var n=needle?rx(needle):null,orig=EventTarget.prototype.addEventListener;
EventTarget.prototype.addEventListener=function(t,f){try{if((!type||String(t)===type||rx(type).test(String(t)))&&(!n||n.test(typeof f==='function'?f.toString():String(f&&f.handleEvent||''))))return;}catch(e){}return orig.apply(this,arguments);};}
function jsonPrune(props,needle){var list=String(props||'').split(/\s+/).filter(Boolean),orig=JSON.parse;
JSON.parse=function(){var r=orig.apply(this,arguments);try{if(r&&typeof r==='object'){for(var i=0;i<list.length;i++){var p=list[i].split('.'),o=r,j=0;for(;j<p.length-1&&o;j++)o=o[p[j]];if(o&&p[j] in o)delete o[p[j]];}}}catch(e){}return r;};}
function noFetch(needle){var n=rx(needle),orig=window.fetch;if(!orig)return;
window.fetch=function(a){try{var u=typeof a==='string'?a:(a&&a.url)||'';if(!needle||n.test(u))return Promise.resolve(new Response('',{status:200}));}catch(e){}return orig.apply(this,arguments);};}
var DOMJOBS=[];
function attrJob(kind,name,sel){DOMJOBS.push(function(){try{var ns=document.querySelectorAll(sel||'['+name+']');for(var i=0;i<ns.length;i++){if(kind==='attr')ns[i].removeAttribute(name);else ns[i].classList.remove(name);}}catch(e){}});}
var ALIAS={'aopr':'abort-on-property-read','abort-on-property-read':'abort-on-property-read','aopw':'abort-on-property-write','abort-on-property-write':'abort-on-property-write',
'acs':'abort-current-script','abort-current-script':'abort-current-script','abort-current-inline-script':'abort-current-script','acis':'abort-current-script',
'set':'set-constant','set-constant':'set-constant','nostif':'no-setTimeout-if','no-setTimeout-if':'no-setTimeout-if','setTimeout-defuser':'no-setTimeout-if',
'nosiif':'no-setInterval-if','no-setInterval-if':'no-setInterval-if','setInterval-defuser':'no-setInterval-if',
'nowoab':'prevent-window-open','no-window-open-if':'prevent-window-open','prevent-window-open':'prevent-window-open','window.open-defuser':'prevent-window-open',
'aeld':'addEventListener-defuser','addEventListener-defuser':'addEventListener-defuser','prevent-addEventListener':'addEventListener-defuser',
'ra':'remove-attr','remove-attr':'remove-attr','rc':'remove-class','remove-class':'remove-class','json-prune':'json-prune','nofetch':'no-fetch-if','no-fetch-if':'no-fetch-if'};
function runScriptlet(a){var nm=ALIAS[a[0]];if(!nm)return;var x=a.slice(1);
try{if(nm==='abort-on-property-read')trap(x[0],'read');else if(nm==='abort-on-property-write')trap(x[0],'write');
else if(nm==='abort-current-script')trap(x[0],'acs',x[1]);else if(nm==='set-constant')setConst(x[0],x[1]);
else if(nm==='no-setTimeout-if')wrapTimer('setTimeout',x[0],x[1]);else if(nm==='no-setInterval-if')wrapTimer('setInterval',x[0],x[1]);
else if(nm==='prevent-window-open')noWindowOpen();else if(nm==='addEventListener-defuser')defuse(x[0],x[1]);
else if(nm==='remove-attr')attrJob('attr',x[0],x[1]);else if(nm==='remove-class')attrJob('class',x[0],x[1]);
else if(nm==='json-prune')jsonPrune(x[0],x[1]);else if(nm==='no-fetch-if')noFetch(x[0]);}catch(e){}}
// ---- cosméticos procedurales
function parseProc(sel){sel=sel.replace(/:-abp-contains\(/g,':has-text(').replace(/:contains\(/g,':has-text(').replace(/:with-text\(/g,':has-text(');
var first=/:(has-text|upward|remove|style|if-not|if)\(/.exec(sel);if(!first)return {base:sel,ops:[]};
var base=sel.slice(0,first.index),rest=sel.slice(first.index),ops=[];
while(rest){var mm=/^:(has-text|upward|remove|style|if-not|if)\(/.exec(rest);if(!mm)return null;
var i=mm[0].length,depth=1;while(i<rest.length&&depth>0){var ch=rest.charAt(i);if(ch==='\\')i++;else if(ch==='(')depth++;else if(ch===')')depth--;i++;}
ops.push([mm[1],rest.slice(mm[0].length,i-1)]);rest=rest.slice(i);if(rest&&rest.charAt(0)!==':')return null;}
return {base:base||'*',ops:ops};}
var PROCS=[];
function runProc(p){var nodes;try{nodes=[].slice.call(document.querySelectorAll(p.base));}catch(e){return;}
var act='hide',st='';
for(var i=0;i<p.ops.length&&nodes.length;i++){var op=p.ops[i][0],arg=p.ops[i][1];
if(op==='has-text'){var r=rx(arg);nodes=nodes.filter(function(n){return r&&r.test(n.textContent||'')});}
else if(op==='upward'){nodes=nodes.map(function(n){if(/^\d+${'$'}/.test(arg)){var k=+arg;while(k-->0&&n)n=n.parentElement;return n;}return n.parentElement&&n.parentElement.closest(arg);}).filter(Boolean);}
else if(op==='if'){nodes=nodes.filter(function(n){try{return n.querySelector(arg)}catch(e){return false}});}
else if(op==='if-not'){nodes=nodes.filter(function(n){try{return !n.querySelector(arg)}catch(e){return true}});}
else if(op==='remove')act='remove';else if(op==='style'){act='style';st=arg;}}
for(var j=0;j<nodes.length;j++){var n=nodes[j];if(act==='remove')n.remove();else if(act==='style')n.style.cssText+=';'+st;else n.style.setProperty('display','none','important');}}
if(R){(R.js||[]).forEach(runScriptlet);(R.proc||[]).forEach(function(s){var p=parseProc(s);if(p)PROCS.push(p);});}
// ---- huecos de anuncio: casillas con medidas estándar de publicidad
var SZ={'300x250':1,'728x90':1,'320x50':1,'300x100':1,'970x90':1,'970x250':1,'336x280':1,'160x600':1,'300x600':1,'468x60':1,'320x100':1,'250x250':1,'120x600':1,'300x50':1};
var ADT=/(^|[-_ .:])(ads?|adv|advert|advertisement|sponsor(ed)?|banner|popunder|outstream|adsbygoogle|exo[-_]?|juicyads|trafficjunky)([-_ .:\d]|$)/i;
function reg(h){var p=String(h).split('.');return p.slice(-2).join('.');}
function sweepAds(){try{var host=reg(location.hostname),ns=document.querySelectorAll('iframe,ins');
for(var i=0;i<ns.length&&i<400;i++){var e=ns[i];if(e.__fx)continue;var r=e.getBoundingClientRect(),w=Math.round(r.width),h=Math.round(r.height);if(!w||!h)continue;
var std=false;for(var k in SZ){var a=k.split('x');if(Math.abs(w-a[0])<=2&&Math.abs(h-a[1])<=2){std=true;break;}}
if(!std)continue;var src=e.getAttribute('src')||'',foreign=false;try{foreign=!!src&&reg(new URL(src,location.href).hostname)!==host;}catch(x){}
var tag=(e.id||'')+' '+(e.className&&e.className.baseVal!==undefined?e.className.baseVal:e.className||'')+' '+(e.parentElement?(e.parentElement.id||'')+' '+(e.parentElement.className||''):'');
if(e.tagName==='INS'||foreign||ADT.test(tag)){e.__fx=1;e.style.setProperty('display','none','important');}}}catch(e){}}
var queued=0;
function tick(){queued=0;for(var i=0;i<DOMJOBS.length;i++)DOMJOBS[i]();for(var j=0;j<PROCS.length;j++)runProc(PROCS[j]);sweepAds();}
function queue(){if(!queued){queued=1;setTimeout(tick,250);}}
function start(){tick();try{new MutationObserver(queue).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
var n=0,t=setInterval(function(){tick();if(++n>8)clearInterval(t);},1500);}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start);else start();
}catch(e){}})();"""

private val SNIFF_JS = """(function(){
if(window.__fluxS)return;window.__fluxS=1;
var B=window.__fluxBridge;if(!B)return;
var sent=window.__fluxSent=window.__fluxSent||{};
function abs(u){try{return new URL(u,location.href).href}catch(e){return null}}
function clean(t){return (t||'').replace(/\s+/g,' ').trim().slice(0,120)}
var curGid='',gidN=0,remoteDone={};
var vq=[],vrun=0,vseen={},vTotal=0;
function verify(u){if(vseen[u]||vTotal>=14)return;vseen[u]=1;vTotal++;vq.push(u);vpump();}
function vpump(){if(vrun>=2||!vq.length)return;var u=vq.shift();vrun++;
var v=document.createElement('video'),done=false,tm;v.preload='metadata';v.muted=true;v.setAttribute('playsinline','');
v.style.cssText='position:fixed;left:-9999px;top:0;width:2px;height:2px;opacity:0;pointer-events:none';
function fin(ok){if(done)return;done=true;clearTimeout(tm);try{B.verified(u,ok?1:0,ok?(v.videoHeight|0):0,ok?(+v.duration||0):0)}catch(e){}
try{v.removeAttribute('src');v.load();v.remove()}catch(e){}vrun--;vpump();}
tm=setTimeout(function(){fin(false)},12000);
v.addEventListener('loadedmetadata',function(){fin(true)});v.addEventListener('error',function(){fin(false)});
try{(document.body||document.documentElement).appendChild(v);v.src=u;}catch(e){fin(false)}}
function send(u,w,h,d,s,p,t,pl){u=abs(u);if(!u||u.indexOf('http')!==0)return;
var k=u+'|'+(h|0)+'|'+(pl?1:0);if(sent[k])return;sent[k]=1;
try{if(curGid)B.grp(u,curGid);B.media2(u,w|0,h|0,+d||0,s||'el',p||'',t||'',!!pl)}catch(e){}}
function okType(ct){ct=(ct||'').toLowerCase();if(/mp2t|iso\.segment|mpeg-tts/.test(ct))return false;
return /^(video|audio)\//.test(ct)||/mpegurl|dash\+xml/.test(ct);}
function titleOf(v){var t=v.getAttribute('title')||v.getAttribute('aria-label')||v.getAttribute('data-title');
if(!t&&v.closest){var a=v.closest('a[title],a[aria-label]');if(a)t=a.getAttribute('title')||a.getAttribute('aria-label');}
if(!t&&v.closest){var f=v.closest('figure,article,li,[class*=video],[class*=item],[class*=card]');
if(f){var h=f.querySelector('figcaption,h1,h2,h3,h4,.title,[class*=title]');if(h)t=h.textContent;}}
return clean(t);}
function posterOf(v){var p=v.poster||v.getAttribute('data-poster')||'';
if(!p){var c=v.closest&&v.closest('figure,article,li,div');var im=(v.parentElement&&v.parentElement.querySelector('img'))||(c&&c.querySelector('img'));
if(im)p=im.currentSrc||im.src||im.getAttribute('data-src')||'';}
return p?(abs(p)||''):'';}
function playingOf(v){return !v.paused&&!v.ended&&v.readyState>2;}
var AD=/(^|[-_])(ads?|advert(isement)?|preroll|vast|vpaid|sponsor(ed)?)([-_]|${'$'})/i,PRE=/preview|hover|teaser/i;
function junkEl(v){try{var d=v.duration,r=v.getBoundingClientRect?v.getBoundingClientRect():null;
if(r&&r.width>0&&r.height>0&&(r.width<170||r.height<95))return true;
if(v.loop&&v.muted&&(!d||d<60))return true;
var n=v,i=0;while(n&&n.nodeType===1&&i<6){var c=n.className&&n.className.baseVal!==undefined?n.className.baseVal:n.className;
var toks=((c||'')+' '+(n.id||'')).split(/\s+/);
for(var k=0;k<toks.length;k++){if(toks[k]&&(AD.test(toks[k])||PRE.test(toks[k])))return true;}
n=n.parentElement;i++;}}catch(e){}return false;}
function scanEl(v){if(junkEl(v))return;var pl=playingOf(v),p=posterOf(v),t=titleOf(v),h=v.videoHeight,w=v.videoWidth,d=v.duration;
var s=v.currentSrc||v.src;
if(s&&s.indexOf('blob:')!==0&&s.indexOf('data:')!==0)send(s,w,h,d,'el',p,t,pl);
var ss=v.querySelectorAll?v.querySelectorAll('source'):[];
for(var i=0;i<ss.length;i++){if(ss[i].src)send(ss[i].src,w,h,d,'el',p,t,pl);}}
function walk(o,d){if(!o||typeof o!=='object'||d>6)return;if(typeof o.contentUrl==='string')send(o.contentUrl,0,0,0);
for(var k in o){walk(o[k],d+1);}}
function thumb(){try{var v=document.querySelector('video[poster]');var u=v&&v.poster;
if(!u){var m=document.querySelector('meta[property="og:image"],meta[property="og:image:secure_url"],meta[name="twitter:image"]');u=m&&m.content;}
if(u){u=abs(u);if(u&&u.indexOf('http')===0&&u!==window.__fluxThumb){window.__fluxThumb=u;B.thumb(u)}}}catch(e){}}
function qHint(s,i){var a0=Math.max(0,i-220),w=s.slice(a0,i+320),re=/(?:quality|height|label|resolution|res|definition)["']?\s*[:=]\s*["']?(\d{3,4})p?/gi,m,best=0,bd=1e9;
while((m=re.exec(w))){var dd=Math.abs(a0+m.index-i);if(dd<bd){bd=dd;best=+m[1];}}
if(best)return best;var m2=/(\d{3,4})p\b/i.exec(w);return m2?+m2[1]:0;}
function fromText(t,depth){depth=depth|0;try{t=t.replace(/\\\//g,'/').replace(/\\u0026/g,'&').replace(/&amp;/g,'&');
curGid='g'+(++gidN);
var re=/https?:\/\/[^\s"'<>\\)]+?\.(?:m3u8|mpd|mp4|webm|m4v|mov)(?:\?[^\s"'<>\\)]*)?/gi,m,n=0;
while((m=re.exec(t))&&n<40){n++;send(m[0],0,qHint(t,m.index),0,'page');}
curGid='';
if(depth<2)follow(t,depth);}catch(e){curGid='';}}
var REMOTE=/https?:\/\/[^\s"'<>\\)]*(?:get_media|get_video|video_info|media_definitions|video\/sources|playlist\.json|source\.json|getvideo|video_sources)[^\s"'<>\\)]*/gi;
function follow(t,depth){var m,n=0;REMOTE.lastIndex=0;
while((m=REMOTE.exec(t))&&n<6){var u=m[0];if(remoteDone[u]||u.indexOf(location.origin)!==0)continue;remoteDone[u]=1;n++;
try{fetch(u,{credentials:'include'}).then(function(r){return r.text()}).then(function(x){if(x&&x.length<1500000&&MEDIA_IN_TEXT.test(x))fromText(x,depth+1)}).catch(function(){});}catch(e){}}}
var kvsFail=0,kvsCombo=-1,kvsSent=0,kvsT0=Date.now(),defN=0;
function note(m){try{B.note(String(m))}catch(e){}}
function licTok(code,mode){code=String(code).replace(/\$/g,'');var lv=[],i;for(i=0;i<code.length;i++){lv.push(+code[i]);if(isNaN(lv[i]))return null;}
var mod=mode?code:code.replace(/0/g,'1'),c=Math.floor(mod.length/2),fh=parseInt(mod.slice(0,c+1),10),bh=parseInt(mod.slice(c),10);
var q=String(4*Math.abs(fh-bh)).slice(0,c+1),out=[],o;
for(i=0;i<q.length;i++){for(o=0;o<4;o++)out.push(((lv[i+o]||0)+(+q[i]))%10);}
return out.length>=32?out:null;}
function swapV(H,tok,v){var idx=[],i,s,d,t,acc=0;for(i=0;i<32;i++)idx.push(i);
if(v===2){for(s=0;s<32;s++){acc+=tok[s];d=(s+acc)%32;t=idx[s];idx[s]=idx[d];idx[d]=t;}}
else if(v===3){for(s=31;s>=0;s--){d=(s+acc)%32;t=idx[s];idx[s]=idx[d];idx[d]=t;acc+=tok[s];}}
else if(v===4){for(s=31;s>=0;s--){acc+=tok[31-s];d=(s+acc)%32;t=idx[s];idx[s]=idx[d];idx[d]=t;}}
else{for(s=31;s>=0;s--){acc+=tok[s];d=(s+acc)%32;t=idx[s];idx[s]=idx[d];idx[d]=t;}}
var out='';if(v===1){var o2=new Array(32);for(i=0;i<32;i++)o2[idx[i]]=H[i];out=o2.join('');}else{for(i=0;i<32;i++)out+=H[idx[i]];}
return out;}
function decWith(u,toks,combo){var a=document.createElement('a');a.href=u;var parts=a.pathname.split('/'),h=parts[3];if(!h||h.length<32)return u;
var tok=toks[Math.floor(combo/5)];if(!tok)return u;parts[3]=swapV(h.slice(0,32),tok,combo%5)+h.slice(32);return a.protocol+'//'+a.host+parts.join('/')+a.search;}
function hashOf(u){var a=document.createElement('a');a.href=u;return a.pathname.split('/')[3]||'';}
function tailOf(u){var a=document.createElement('a');a.href=u;var p=a.pathname.split('/');return a.host+'|'+p.slice(4).join('/');}
function realGetFile(){var out=[];try{var vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++){var s=vs[i].currentSrc||vs[i].src;if(s&&/\/get_file\//.test(s))out.push(s);}
var ps=performance.getEntriesByType('resource');for(i=0;i<ps.length;i++){if(/\/get_file\//.test(ps[i].name))out.push(ps[i].name);}}catch(e){}return out;}
function qOfText(x){x=String(x||'');var m=/(\d{3,4})\s*p/i.exec(x);if(m)return +m[1];if(/4k|2160/i.test(x))return 2160;if(/2k|1440/i.test(x))return 1440;m=/(\d{3,4})/.exec(x);return m?+m[1]:0;}
// Comprueba la decodificación con la URL REAL que ya pidió el reproductor: 1 = ok (kvsCombo fijado), -1 = ningún algoritmo coincide, 0 = aún no hay URL real
function kvsCalibrate(enc,toks){var reals=realGetFile();if(!reals.length)return 0;
for(var i=0;i<enc.length;i++){var t=tailOf(enc[i].u);for(var j=0;j<reals.length;j++){if(tailOf(reals[j])!==t)continue;
var eh=hashOf(enc[i].u).slice(0,32),rh=hashOf(reals[j]).slice(0,32);
if(eh===rh){kvsCombo=-2;note('KVS: las URL ya vienen decodificadas');return 1;}
for(var ti=0;ti<2;ti++){if(!toks[ti])continue;for(var vi=0;vi<5;vi++){if(swapV(eh,toks[ti],vi)===rh){kvsCombo=ti*5+vi;note('KVS: algoritmo '+kvsCombo+' verificado con la URL real');return 1;}}}
note('KVS: ningún algoritmo reproduce la URL real ('+enc[i].k+')');return -1;}}
return 0;}
function kvs(){if(kvsSent||kvsFail)return;try{var f=window.flashvars;if(!f||!f.license_code)return;
var ents=[];Object.keys(f).forEach(function(k){var v=f[k];if(typeof v!=='string'||!/^video(_alt)?_url\d*${'$'}/.test(k))return;
if(v.indexOf('function/0/')!==0&&!/^https?:/.test(v))return;
ents.push({k:k,v:v,u:v.indexOf('function/0/')===0?v.slice(11):v,q:qOfText(f[k+'_text'])});});
if(!ents.length)return;ents.forEach(function(e){if(e.q)declQ[e.q]=1;});
var enc=ents.filter(function(e){return e.v.indexOf('function/0/')===0}),toks=[licTok(f.license_code,0),licTok(f.license_code,1)];
if(!enc.length){kvsCombo=-2;}
else if(kvsCombo===-1){var r=kvsCalibrate(enc,toks);
if(r<0){kvsFail=1;return;}
if(r===0){if(Date.now()-kvsT0<9000)return;kvsCombo=0;note('KVS: sin URL real para verificar; se prueba el algoritmo 0');}}
kvsSent=1;curGid='g'+(++gidN);
ents.forEach(function(e){var u=e.v.indexOf('function/0/')===0?decWith(e.u,toks,kvsCombo):e.u;send(u,0,e.q,0,'page');});curGid='';}catch(x){curGid='';}}
setTimeout(kvs,1200);setTimeout(kvs,3500);setTimeout(kvs,6000);setTimeout(kvs,10000);
function defs(arr,depth){try{curGid='g'+(++gidN);arr.forEach(function(d){if(!d||typeof d.videoUrl!=='string'||!d.videoUrl)return;var q=d.quality;if(Array.isArray(q))q=q[0];q=parseInt(q,10)||0;
if(d.remote===true||/get_media/.test(d.videoUrl)){if(depth<2)fetch(abs(d.videoUrl),{credentials:'include'}).then(function(r){return r.json()}).then(function(j){if(Array.isArray(j))defs(j,depth+1)}).catch(function(){});}
else{send(d.videoUrl,0,q,0,'page');}});curGid='';}catch(e){curGid='';}}
function flashDefs(){if(defN>4)return;try{for(var k in window){if(/^flashvars/.test(k)){var f=window[k];if(f&&f.mediaDefinitions&&f.mediaDefinitions.length){defN++;defs(f.mediaDefinitions,0);}}}}catch(e){}}
var declQ={};
function sleep(ms){return new Promise(function(r){setTimeout(r,ms)})}
function shown(e){try{var r=e.getBoundingClientRect(),c=getComputedStyle(e);return r.width>0&&r.height>0&&c.visibility!=='hidden'&&c.display!=='none';}catch(x){return false}}
function press(e){try{var ev=['pointerdown','mousedown','pointerup','mouseup','click'];for(var i=0;i<ev.length;i++){var t=ev[i],E=(t.indexOf('pointer')===0&&window.PointerEvent)?PointerEvent:MouseEvent;e.dispatchEvent(new E(t,{bubbles:true,cancelable:true,view:window}));}}catch(x){try{e.click()}catch(y){}}}
var QL=/^(\d{3,4})\s*p?(?:\s*[-·|]?\s*(?:4k|2k|8k|uhd|fhd|qhd|hd))?${'$'}/i;
function labelOf(n){return (n.textContent||'').replace(/\s+/g,' ').trim();}
function qItems(root){var out=[],ns=root.querySelectorAll('li,a,button,div,span,label,p');
for(var i=0;i<ns.length;i++){var n=ns[i],t=labelOf(n);if(!t||t.length>14)continue;var m=QL.exec(t);if(!m)continue;var h=+m[1];if(h<144||h>4320)continue;
var dup=false,k=n.children;for(var j=0;j<k.length;j++){if(labelOf(k[j])===t){dup=true;break;}}if(dup)continue;out.push({el:n,h:h,t:t});}
return out;}
function mainVideo(){var best=null,ba=0,vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++){var r=vs[i].getBoundingClientRect(),a=r.width*r.height;if(a>ba&&!junkEl(vs[i])){ba=a;best=vs[i];}}return best;}
function playerRoot(v){var n=v;for(var i=0;i<7&&n;i++){n=n.parentElement;if(n&&qItems(n).length>=2)return n;}return null;}
var GEAR='[class*="setting" i],[class*="gear" i],[class*="cog" i],[class*="quality" i],[class*="resolution" i],[class*="config" i],[aria-label*="setting" i],[aria-label*="quality" i],[aria-label*="calidad" i],[aria-label*="ajuste" i],[title*="setting" i],[title*="quality" i],[title*="calidad" i],[title*="ajuste" i]';
function findGear(v,box){var vr=v.getBoundingClientRect(),best=null,bd=1e9,ns=[];try{ns=box.querySelectorAll(GEAR);}catch(e){}
for(var i=0;i<ns.length;i++){var e=ns[i];if(!shown(e))continue;var r=e.getBoundingClientRect();if(r.width<12||r.height<12||r.width>110||r.height>90)continue;
var d=Math.abs(r.right-vr.right)+Math.abs(r.bottom-vr.bottom);if(d<bd){bd=d;best=e;}}return best;}
function currentOf(its,v){for(var i=0;i<its.length;i++){var e=its[i].el,p=e.parentElement;
var cs=((e.className&&e.className.baseVal===undefined?e.className:'')||'')+' '+((p&&p.className&&p.className.baseVal===undefined?p.className:'')||'');
if(/(^|[\s_-])(active|selected|current|checked|on)([\s_-]|${'$'})/i.test(cs)||e.getAttribute('aria-selected')==='true'||e.getAttribute('aria-checked')==='true')return its[i].h;}
var vh=v.videoHeight|0,best=its[0].h,bd=1e9;for(i=0;i<its.length;i++){var d=Math.abs(its[i].h-vh);if(d<bd){bd=d;best=its[i].h;}}return best;}
function lockedEl(e){var n=e,i=0;while(n&&i<3){var t=((n.className&&n.className.baseVal===undefined?n.className:'')||'')+' '+((n.getAttribute&&n.getAttribute('title'))||'');
if(/lock|premium|vip|paid|upgrade/i.test(t))return true;n=n.parentElement;i++;}return false;}
async function harvest(){
// Solo en sitios que funcionan como PimpBunny (reproductor KVS con calidades en `flashvars`): en los demás todo queda como estaba
if(!(window.flashvars&&window.flashvars.license_code))return;
if(window.__fluxHarvested)return;window.__fluxHarvested=1;
var v=mainVideo();if(!v){window.__fluxHarvested=0;return;}
var box=v;for(var i=0;i<5&&box.parentElement&&box.parentElement!==document.body;i++)box=box.parentElement;
var root=playerRoot(v)||box,m0=v.muted,p0=v.paused,started=false;
try{
var gear=findGear(v,box);
async function openMenu(){var its=qItems(root).filter(function(x){return shown(x.el)});if(its.length>=2)return its;
if(gear){press(gear);await sleep(450);its=qItems(root).filter(function(x){return shown(x.el)});}
if(its.length<2&&root!==box){root=box;its=qItems(root).filter(function(x){return shown(x.el)});}
return its;}
var its=await openMenu();if(its.length<2)throw 0;
try{B.auto(1);started=true;}catch(e){}
v.muted=true;
var cur=currentOf(its,v);
var todo=its.filter(function(x){return x.h!==cur&&!lockedEl(x.el)}).sort(function(a,b){return a.h-b.h});
for(var k=0;k<todo.length;k++){
var live=await openMenu(),t=live.filter(function(x){return x.h===todo[k].h})[0];if(!t)continue;
var prev=v.currentSrc;press(t.el);
for(var w=0;w<18;w++){await sleep(200);if(v.currentSrc&&v.currentSrc!==prev)break;}
await sleep(500);
var s=v.currentSrc;if(s&&s!==prev&&s.indexOf('blob:')!==0){var tk=/[\/_.-](\d{3,4})p(?=[\/_.\-?]|${'$'})/.exec(s);send(abs(s),v.videoWidth|0,tk?+tk[1]:todo[k].h,+v.duration||0,'el');}}
var live2=await openMenu(),back=live2.filter(function(x){return x.h===cur})[0];if(back){press(back.el);await sleep(900);}
}catch(e){}
try{v.muted=m0;if(p0&&!v.paused)v.pause();}catch(e){}
if(started){try{B.auto(0)}catch(e){}}
}
window.__fluxHarvest=harvest;
function autoHarvest(){try{if(window.__fluxHarvested||Object.keys(declQ).length<2)return;var v=mainVideo();if(v&&v.paused&&!v.ended)harvest();}catch(e){}}
setTimeout(autoHarvest,3000);setTimeout(autoHarvest,8000);
var MEDIA_IN_TEXT=/\.(m3u8|mpd|mp4|webm|m4v|mov)/i,JSON_CT=/json|javascript|text\/plain/i;
var textDone=0;
function scanText(){if(textDone>3)return;textDone++;try{var ss=document.scripts;for(var i=0;i<ss.length;i++){var c=ss[i].textContent;
if(c&&c.length>40&&c.length<2500000&&(MEDIA_IN_TEXT.test(c)||/get_media|media_definitions/.test(c)))fromText(c,0);}}catch(e){}}
function scanPerf(){try{var es=performance.getEntriesByType('resource');for(var i=0;i<es.length;i++){var u=es[i].name;
if(/\.(m3u8|mpd)(\?|#|$)/i.test(u))send(u,0,0,0,'page');else if(/\.(mp4|webm|m4v|mov)(\?|#|$)/i.test(u))send(u,0,0,0,'net');}}catch(e){}}
function scan(){thumb();scanPerf();kvs();flashDefs();try{var vs=document.querySelectorAll('video,audio');for(var i=0;i<vs.length;i++)scanEl(vs[i]);
var ms=document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"],meta[name="twitter:player:stream"]');
for(var j=0;j<ms.length;j++){if(ms[j].content)send(ms[j].content,0,0,0);}
var ld=document.querySelectorAll('script[type="application/ld+json"]');
for(var n=0;n<ld.length;n++){try{walk(JSON.parse(ld[n].textContent),0)}catch(e){}}}catch(e){}}
window.__fluxScan=scan;
var isMedia=function(t){return t&&t.tagName&&/^(VIDEO|AUDIO)${'$'}/.test(t.tagName)};
document.addEventListener('loadedmetadata',function(e){if(isMedia(e.target))scanEl(e.target);kvs();},true);
document.addEventListener('playing',function(e){var t=e.target;if(!isMedia(t)||junkEl(t))return;scanEl(t);
var s=t.currentSrc||t.src;try{B.playing(s&&s.indexOf('blob:')!==0?(abs(s)||''):'',posterOf(t),titleOf(t),t.videoHeight|0,+t.duration||0)}catch(x){}},true);
document.addEventListener('resize',function(e){var t=e.target;if(!isMedia(t)||junkEl(t)||t.paused)return;
var s=t.currentSrc||t.src;try{B.playing(s&&s.indexOf('blob:')!==0?(abs(s)||''):'',posterOf(t),titleOf(t),t.videoHeight|0,+t.duration||0)}catch(x){}},true);
var onPause=function(e){if(isMedia(e.target)){try{B.paused()}catch(x){}}};
document.addEventListener('ended',onPause,true);
var activated=false;
['touchstart','pointerdown','click','keydown'].forEach(function(n){document.addEventListener(n,function(){activated=true},true)});
document.addEventListener('play',function(e){var t=e.target;try{if(isMedia(t)&&!activated&&!t.muted&&t.volume>0)t.pause()}catch(x){}},true);
function mask(fn,orig){try{Object.defineProperty(fn,'toString',{value:function(){return Function.prototype.toString.call(orig)},configurable:true})}catch(e){}}
var of=window.fetch;
if(of){var nf=function(){var a=arguments;var p=of.apply(this,a);
p.then(function(r){try{var ct=r.headers.get('content-type')||'';if(okType(ct))send(r.url||(a[0]&&a[0].url)||a[0],0,0,0,'net');
else if(JSON_CT.test(ct)&&(+r.headers.get('content-length')||0)<1500000)r.clone().text().then(function(t){if(t&&t.length<1500000&&MEDIA_IN_TEXT.test(t))fromText(t)}).catch(function(){})}catch(e){}}).catch(function(){});
return p;};mask(nf,of);window.fetch=nf;}
var xo=XMLHttpRequest.prototype.open;
var nx=function(m,u){var x=this;x.__fu=u;
x.addEventListener('readystatechange',function(){try{if(x.readyState===2&&okType(x.getResponseHeader('content-type')))send(x.responseURL||x.__fu,0,0,0,'net')}catch(e){}});
x.addEventListener('readystatechange',function(){try{if(x.readyState===4&&(x.responseType===''||x.responseType==='text')&&JSON_CT.test(x.getResponseHeader('content-type')||'')){var t=x.responseText;if(t&&t.length<1500000&&MEDIA_IN_TEXT.test(t))fromText(t)}}catch(e){}});
return xo.apply(this,arguments);};mask(nx,xo);XMLHttpRequest.prototype.open=nx;
var t0;function later(){clearTimeout(t0);t0=setTimeout(scan,200);}
function start(){scan();scanText();try{new MutationObserver(later).observe(document.documentElement,{childList:true,subtree:true})}catch(e){}
window.addEventListener('load',function(){scanText();scanPerf();setTimeout(function(){scanText();scanPerf()},2500)});}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start);else start();
})();"""


/** Rutas con pinta de publicidad (pre-roll, VAST, banners): aunque sean del propio sitio, las listas sí pueden bloquearlas. */
private val adPathRegex = Regex(
    """(?i)(^|[/_.\-])(ads?|adv|advert(ising|isement)?|vast|vpaid|preroll|pre-roll|midroll|sponsor(ed)?|banner|popunder|popads?)([/_.?=&\-]|$)""",
)

private fun adLikePath(url: String): Boolean = adPathRegex.containsMatchIn(url.substringBefore('?'))

private val mediaFileRegex = Regex("""(?i)\.(mp4|m4v|mov|webm)/?(\?|$)""")

/** ¿La URL pide un archivo de video? (incluye las de KVS, que terminan en .mp4/) */
private fun looksMediaUrl(url: String): Boolean = mediaFileRegex.containsMatchIn(url.substringBefore('#'))
