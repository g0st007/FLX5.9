package app.flux.downloader.browser

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.sync.withPermit
import androidx.compose.material3.FilledIconButton
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.ui.graphics.toArgb
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.layout.ContentScale
import app.flux.downloader.settings.AppSettings
import coil.compose.AsyncImage
import coil.request.ImageRequest
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import app.flux.downloader.engine.Quality
import app.flux.downloader.engine.MediaKind
import app.flux.downloader.ui.formatDuration
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.produceState
import app.flux.downloader.engine.Engine
import app.flux.downloader.engine.MediaInfo
import app.flux.downloader.engine.friendlyError
import app.flux.downloader.ui.formatSize
import app.flux.downloader.ui.PlayerSource
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Surface
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flux.downloader.adblock.FilterManager
import app.flux.downloader.engine.FileDownloads
import app.flux.downloader.engine.SiteGuard
import app.flux.downloader.ui.FluxOrange
import coil.compose.SubcomposeAsyncImage

private val SecureGreen = Color(0xFF4CAF50)

private data class Shortcut(val name: String, val url: String)

private val shortcuts = listOf(
    Shortcut("TikTok", "https://www.tiktok.com"),
    Shortcut("X", "https://x.com"),
    Shortcut("Reddit", "https://www.reddit.com"),
    Shortcut("Instagram", "https://www.instagram.com"),
    Shortcut("Facebook", "https://m.facebook.com"),
    Shortcut("Vimeo", "https://vimeo.com"),
    Shortcut("Dailymotion", "https://www.dailymotion.com"),
    Shortcut("Twitch", "https://www.twitch.tv"),
)

@Composable
fun BrowserScreen(
    visible: Boolean = true,
    session: BrowserSession,
    pendingUrl: String?,
    onPendingConsumed: () -> Unit,
    onPlayStream: (PlayerSource) -> Unit,
    onDownloadRequest: (String) -> Unit,
    onDownloadQuality: (MediaInfo, Int?) -> Unit,
) {
    val ctx = LocalContext.current
    val controller = remember {
        val app = ctx.applicationContext
        BrowserController(app, BrowserPrefs(app), BrowserStore(app), session)
    }
    SideEffect {
        controller.onDownloadRequest = onDownloadRequest
        controller.onPlayStream = onPlayStream
    }
    // Otra pestaña pidió abrir un enlace aquí (p. ej. "Abrir en el navegador de Flux")
    LaunchedEffect(pendingUrl) {
        pendingUrl?.let { controller.go(it); onPendingConsumed() }
    }

    val filterVersion by FilterManager.version.collectAsStateWithLifecycle()
    LaunchedEffect(filterVersion, controller.blockAds) { controller.applyGenericCosmetics() }

    val snackbar = remember { SnackbarHostState() }
    var showProtection by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showDownload by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    val pageBackground = MaterialTheme.colorScheme.background.toArgb()
    var showDiag by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var dismissedFor by remember { mutableStateOf<String?>(null) }

    // Al salir de la pestaña el navegador sigue vivo pero en pausa; al volver se reanuda donde estaba
    LaunchedEffect(visible) { if (visible) controller.onShown() else controller.onHidden() }

    BackHandler(enabled = visible && (showTabs || !controller.showStart)) {
        if (showTabs) showTabs = false else controller.back()
    }

    LaunchedEffect(controller.message) {
        controller.message?.let {
            snackbar.showSnackbar(it)
            controller.message = null
        }
    }
    LaunchedEffect(controller.notice) {
        controller.notice?.let { n ->
            controller.notice = null
            val r = snackbar.showSnackbar(
                message = n.text,
                actionLabel = n.actionLabel,
                duration = if (n.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (r == SnackbarResult.ActionPerformed) n.action?.invoke()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AddressBar(
            c = controller,
            onShield = { showProtection = true },
            onLibrary = { showLibrary = true },
            onDiag = { showDiag = true },
        )

        if (controller.loading) {
            LinearProgressIndicator(
                progress = { controller.progress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        } else {
            Spacer(Modifier.height(4.dp))
        }

        VideoDetectedBanner(
            visible = controller.detected.isNotEmpty() && !controller.showStart && !showTabs &&
                dismissedFor != controller.currentUrl,
            subtitle = bannerSubtitle(controller.detected.toList(), controller.pageTitle),
            onDownload = { showDownload = true },
            onFullscreen = { controller.openInFluxPlayer() },
            onDismiss = { dismissedFor = controller.currentUrl },
        )

        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { c -> controller.createWebView(c, pageBackground) },
                update = { wv -> wv.setBackgroundColor(pageBackground) },
                modifier = Modifier.fillMaxSize(),
                onRelease = { wv ->
                    controller.saveActive()
                    controller.webView = null
                    wv.destroy()
                },
            )
            if (controller.showStart) StartPage(controller, onOpen = controller::go)
            if (showTabs) TabSwitcher(controller, onClose = { showTabs = false })
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }

        BottomBar(controller, onTabs = { showTabs = !showTabs }, onDownload = { showDownload = true })
    }

    if (showProtection) {
        ProtectionDialog(
            c = controller,
            onFilters = { showProtection = false; showFilters = true },
            onDismiss = { showProtection = false },
        )
    }
    if (showFilters) FiltersDialog { showFilters = false }
    if (showDownload) {
        DownloadDialog(
            c = controller,
            onQuality = onDownloadQuality,
            onPlay = { m -> onPlayStream(controller.playerSourceFor(m)) },
            onStarted = { controller.message = it },
            onDismiss = { showDownload = false },
        )
    }
    if (showDiag) DiagDialog(controller) { showDiag = false }
    controller.linkMenu?.let { menu -> LinkMenuDialog(menu, controller) { controller.linkMenu = null } }
    if (showLibrary) {
        LibraryDialog(controller, onOpen = { controller.go(it); showLibrary = false }) { showLibrary = false }
    }

    controller.pendingFile?.let { f ->
        AlertDialog(
            onDismissRequest = { controller.pendingFile = null },
            title = { Text("Archivo potencialmente peligroso") },
            text = {
                Text(
                    "“${FileDownloads.fileName(f)}” es un archivo ejecutable y podría contener software malicioso. " +
                        "Descárgalo solo si confías en el sitio.",
                )
            },
            confirmButton = { TextButton(onClick = controller::confirmPendingFile) { Text("Descargar") } },
            dismissButton = { TextButton(onClick = { controller.pendingFile = null }) { Text("Cancelar") } },
        )
    }
}

// ----------------------------------------------------------------------
// Aviso flotante "video detectado"
// ----------------------------------------------------------------------
@Composable
private fun VideoDetectedBanner(
    visible: Boolean,
    subtitle: String,
    onDownload: () -> Unit,
    onFullscreen: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = FluxOrange, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Video detectado", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledIconButton(
                onClick = onDownload,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.Download, contentDescription = "Descargar")
            }
            IconButton(onClick = onFullscreen, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Fullscreen, contentDescription = "Pantalla completa con el reproductor de Flux")
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Ocultar aviso", modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ----------------------------------------------------------------------
// Barra de direcciones + menú
// ----------------------------------------------------------------------
@Composable
private fun AddressBar(c: BrowserController, onShield: () -> Unit, onLibrary: () -> Unit, onDiag: () -> Unit) {
    val focus = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val secure = c.currentUrl?.startsWith("https://") == true

    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = when {
                editing -> text
                c.showStart -> ""
                else -> c.currentUrl.orEmpty().removePrefix("https://")
            },
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { st ->
                    if (st.isFocused && !editing) {
                        editing = true
                        text = if (c.showStart) "" else c.currentUrl.orEmpty()
                    } else if (!st.isFocused) {
                        editing = false
                    }
                },
            singleLine = true,
            placeholder = { Text("Busca o escribe una dirección") },
            leadingIcon = {
                when {
                    c.showStart -> Icon(Icons.Default.Search, contentDescription = null)
                    secure -> Icon(Icons.Default.Lock, contentDescription = "Conexión segura", tint = SecureGreen)
                    else -> Icon(Icons.Default.Warning, contentDescription = "Conexión no segura", tint = MaterialTheme.colorScheme.error)
                }
            },
            trailingIcon = {
                if (!c.showStart) {
                    IconButton(onClick = { if (c.loading) c.stop() else c.reload() }) {
                        Icon(
                            if (c.loading) Icons.Default.Close else Icons.Default.Refresh,
                            contentDescription = if (c.loading) "Detener" else "Recargar",
                        )
                    }
                }
            },
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
            keyboardActions = KeyboardActions(onGo = {
                c.go(text)
                focus.clearFocus()
            }),
        )
        IconButton(onClick = onShield) {
            BadgedBox(badge = { if (c.blockedCount > 0) Badge { Text("${c.blockedCount}") } }) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = "Protección",
                    tint = if (c.blockAds && c.siteProtected) FluxOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Menú")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Nueva pestaña") },
                    onClick = { menu = false; c.newTab() },
                )
                DropdownMenuItem(
                    text = { Text(if (c.isBookmarked()) "Quitar marcador" else "Agregar marcador") },
                    enabled = !c.showStart,
                    onClick = { menu = false; c.toggleBookmark() },
                )
                DropdownMenuItem(
                    text = { Text("Marcadores e historial") },
                    onClick = { menu = false; onLibrary() },
                )
                DropdownMenuItem(
                    text = { Text(if (c.desktopMode) "Ver sitio móvil" else "Ver sitio de escritorio") },
                    onClick = { menu = false; c.toggleDesktop() },
                )
                DropdownMenuItem(
                    text = { Text("Diagnóstico del sitio") },
                    onClick = { menu = false; onDiag() },
                )
                DropdownMenuItem(
                    text = { Text("Quitar bloqueos de la página") },
                    onClick = { menu = false; c.removeOverlays() },
                )
                DropdownMenuItem(
                    text = { Text("Borrar todo y cerrar pestañas", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; c.panic() },
                )
            }
        }
    }
}

@Composable
private fun BottomBar(c: BrowserController, onTabs: () -> Unit, onDownload: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = c::back, enabled = !c.showStart) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
        }
        IconButton(onClick = c::forward, enabled = c.canGoForward && !c.showStart) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Adelante")
        }
        IconButton(onClick = c::home) {
            Icon(Icons.Default.Home, contentDescription = "Inicio")
        }
        IconButton(onClick = onTabs) {
            Box(
                Modifier
                    .size(24.dp)
                    .border(BorderStroke(2.dp, LocalContentColor.current), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("${c.tabs.size}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Button(
            onClick = onDownload,
            enabled = !c.showStart,
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Descargar", maxLines = 1, softWrap = false)
            val videoCount = cleanDetected(c.detected.toList(), c.pageTitle).size
            if (videoCount > 0) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f), CircleShape)
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                ) {
                    Text("$videoCount", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Página de inicio y selector de pestañas
// ----------------------------------------------------------------------
@Composable
private fun StartPage(c: BrowserController, onOpen: (String) -> Unit) {
    val tiles = shortcuts + c.bookmarks.take(8).map { Shortcut(it.title, it.url) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Icon(Icons.Default.Shield, contentDescription = null, tint = FluxOrange, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(8.dp))
        Text("Navega con protección", style = MaterialTheme.typography.titleLarge)
        Text(
            "Solo HTTPS · bloqueo de anuncios y rastreadores · sin acceso a cámara ni ubicación",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        if (c.totalBlocked > 0) {
            Text("${c.totalBlocked} anuncios y rastreadores bloqueados", color = FluxOrange, fontSize = 13.sp)
        }
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tiles) { s ->
                Column(
                    Modifier.clickable { onOpen(s.url) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        val letter: @Composable () -> Unit = {
                            Text(s.name.take(1).uppercase(), color = FluxOrange, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                        val host = remember(s.url) { Uri.parse(s.url).host.orEmpty().removePrefix("www.").removePrefix("m.") }
                        // Ícono real del sitio (DuckDuckGo, el mismo buscador del navegador); si falla, la inicial.
                        SubcomposeAsyncImage(
                            model = "https://icons.duckduckgo.com/ip3/$host.ico",
                            contentDescription = s.name,
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
                            loading = { letter() },
                            error = { letter() },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        s.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TabSwitcher(c: BrowserController, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (c.tabs.size == 1) "1 pestaña" else "${c.tabs.size} pestañas",
                Modifier.weight(1f),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Button(onClick = { c.newTab(); onClose() }, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Nueva")
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(c.tabs) { i, tab ->
                val isActive = i == c.activeIndex
                Card(
                    modifier = Modifier.clickable { c.switchTo(i); onClose() },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = if (isActive) BorderStroke(2.dp, FluxOrange) else null,
                ) {
                    Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tab.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                tab.url?.let { Uri.parse(it).host } ?: "Página de inicio",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { c.closeTab(i) }) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar pestaña")
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Diálogos
// ----------------------------------------------------------------------
@Composable
private fun ProtectionDialog(c: BrowserController, onFilters: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Protección") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SwitchRow("Bloquear anuncios y rastreadores", c.blockAds) { c.updateBlockAds(it) }
                if (c.blockAds && !c.showStart && c.currentUrl != null) {
                    val host = remember(c.currentUrl) { Uri.parse(c.currentUrl).host.orEmpty() }
                    SwitchRow("Protección en $host", c.siteProtected) { c.updateSiteProtected(it) }
                }
                SwitchRow("Bloquear redirecciones automáticas", c.blockRedirects) { c.updateBlockRedirects(it) }
                Text(
                    "${c.blockedCount} en esta página · ${c.totalBlocked} en total",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onFilters) { Text("Listas de filtros") }
                Text("Siempre activo", fontWeight = FontWeight.SemiBold)
                listOf(
                    "Solo HTTPS y certificados válidos",
                    "Navegación segura contra phishing y malware",
                    "Popups y apps externas bloqueados",
                    "Cámara, micrófono y ubicación denegados",
                    "Cookies de terceros bloqueadas",
                ).forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = { TextButton(onClick = { c.clearData() }) { Text("Borrar datos") } },
    )
}

@Composable
internal fun FiltersDialog(onDismiss: () -> Unit) {
    val states by FilterManager.states.collectAsStateWithLifecycle()
    val busy by FilterManager.busy.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Listas de filtros") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(states) { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontSize = 14.sp)
                            Text(
                                when {
                                    s.error != null -> "Error: ${s.error}"
                                    s.updatedAt == 0L -> "Sin descargar"
                                    else -> "${s.rules} reglas · ${ago(s.updatedAt)}"
                                },
                                fontSize = 12.sp,
                                color = if (s.error != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = s.enabled, onCheckedChange = { FilterManager.setEnabled(s.id, it) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { FilterManager.updateNow() }, enabled = !busy) {
                Text(if (busy) "Actualizando…" else "Actualizar ahora")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

private fun ago(ts: Long): String {
    val m = (System.currentTimeMillis() - ts) / 60_000
    return when {
        m < 1 -> "actualizada ahora"
        m < 60 -> "hace $m min"
        m < 1440 -> "hace ${m / 60} h"
        else -> "hace ${m / 1440} d"
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private sealed interface PageProbe {
    data object Idle : PageProbe
    data object Loading : PageProbe
    data class Ready(val info: MediaInfo) : PageProbe
    data class Failed(val message: String) : PageProbe
}

private fun qualityLabel(q: Quality) = if (q.height == 0) "Original" else "${q.height}p"

private fun sizeLabel(q: Quality): String? =
    q.bytes?.let { (if (q.estimated) "≈ " else "") + formatSize(it) }

/** "720p · ≈ 18 MB" de la mejor calidad. */
private fun topLabel(m: DetectedMedia, maxHeight: Int = 0): String? {
    val h = pickQuality(m, maxHeight)
    val q = m.qualities.firstOrNull { it.height == (h ?: 0) } ?: m.qualities.firstOrNull() ?: return null
    return listOfNotNull(qualityLabel(q), sizeLabel(q)).joinToString(" · ")
}

private fun bannerSubtitle(list: List<DetectedMedia>, pageTitle: String = ""): String {
    val d = cleanDetected(list, pageTitle)
    d.firstOrNull { it.playing }?.let { m ->
        if (m.playingHeight > 0) {
            // La resolución real del reproductor de la página, no la de la lista que se leyó primero
            val q = m.qualities.filter { it.height > 0 }.minByOrNull { kotlin.math.abs(it.height - m.playingHeight) }
            return "Reproduciendo · " + listOfNotNull("${m.playingHeight}p", q?.takeIf { it.height == m.playingHeight }?.let(::sizeLabel))
                .joinToString(" · ")
        }
        topLabel(m)?.let { return "Reproduciendo · $it" }
    }
    if (d.size != 1) return "${d.size} videos en esta página"
    return topLabel(d.first()) ?: "Listo para descargar"
}

private fun kindLabel(m: DetectedMedia): String {
    val ext = when (m.kind) {
        MediaKind.HLS -> "HLS"
        MediaKind.DASH -> "DASH"
        MediaKind.DIRECT -> (m.mime?.substringAfter('/')?.substringAfter('-')?.uppercase()
            ?: Regex("""\.([A-Za-z0-9]{2,4})(?=[/?#]|$)""").findAll(m.url.substringBefore('?').substringAfter("://").substringAfter('/')).lastOrNull()?.groupValues?.get(1)?.uppercase()
            ?: "Archivo")
    }
    return (if (m.audioOnly) "Audio" else "Video") + " · " + ext
}

/** Miniatura con portada remota (con el Referer del sitio, que muchas piden) y un ícono si no carga. */
@Composable
private fun MiniThumb(
    url: String?,
    headers: Map<String, String>,
    size: androidx.compose.ui.unit.Dp,
    audio: Boolean = false,
    mediaUrl: String? = null,
) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (audio) Icons.Default.MusicNote else Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size / 2.4f),
        )
        if (url != null) {
            val ctx = LocalContext.current
            val req = remember(url, headers) {
                ImageRequest.Builder(ctx).data(url).apply {
                    headers["Referer"]?.let { addHeader("Referer", it) }
                    headers["User-Agent"]?.let { addHeader("User-Agent", it) }
                }.crossfade(true).build()
            }
            AsyncImage(req, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (mediaUrl != null && !audio) {
            // Sin portada en la página: se toma un fotograma del propio video
            val frame by produceState<android.graphics.Bitmap?>(null, mediaUrl) { value = frameOf(mediaUrl, headers) }
            val f = frame
            if (f != null) {
                androidx.compose.foundation.Image(
                    bitmap = f.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private val frameCache = android.util.LruCache<String, android.graphics.Bitmap>(32)
private val frameGate = kotlinx.coroutines.sync.Semaphore(2)

/** Un fotograma del video (sin bajarlo entero); null si el formato o el servidor no lo permiten. */
private suspend fun frameOf(url: String, headers: Map<String, String>): android.graphics.Bitmap? {
    frameCache.get(url)?.let { return it }
    return frameGate.withPermit {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(url, headers)
                    val raw = r.getFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: r.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    raw?.let {
                        val k = 320f / maxOf(it.width, it.height).coerceAtLeast(1)
                        val bmp = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(it, (it.width * k).toInt().coerceAtLeast(1), (it.height * k).toInt().coerceAtLeast(1), true) else it
                        frameCache.put(url, bmp)
                        bmp
                    }
                } finally {
                    runCatching { r.release() }
                }
            }.getOrNull()
        }
    }
}

/**
 * Selector de descarga. Lo detectado aparece al instante y, por cada video, con TODAS sus calidades y su peso
 * (las de la lista maestra, las de las URLs que declara la página y las del extractor del sitio, unidas).
 * En galerías: miniatura y título, selección múltiple y "Todos" (uno por video); el que se reproduce, arriba.
 */
@Composable
private fun DownloadDialog(
    c: BrowserController,
    onQuality: (MediaInfo, Int?) -> Unit,
    onPlay: (DetectedMedia) -> Unit,
    onStarted: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val page = c.currentUrl
    val host = page?.let { Uri.parse(it).host.orEmpty().removePrefix("www.") }.orEmpty()
    val maxHeight by AppSettings.maxHeight.collectAsStateWithLifecycle()
    val cached = page?.let { c.extractorCache[it] }
    var probe by remember { mutableStateOf<PageProbe>(if (cached != null) PageProbe.Ready(cached) else PageProbe.Idle) }
    val scope = rememberCoroutineScope()
    val selected = remember { mutableStateListOf<String>() }
    var expanded by remember { mutableStateOf<String?>(null) }

    fun runExtractor() {
        if (page == null || SiteGuard.isBlocked(page)) return
        probe = PageProbe.Loading
        scope.launch {
            probe = try {
                val info = Engine.analyze(page)
                c.extractorCache[page] = info
                PageProbe.Ready(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PageProbe.Failed(friendlyError(e))
            }
        }
    }

    // Siempre se consulta también el extractor del sitio: aporta las calidades que el reproductor no cargó
    LaunchedEffect(page) {
        if (cached == null) runExtractor()
    }
    // Recorre las calidades del reproductor de la página para descubrir la URL real de cada una (sin que el usuario tenga que hacerlo)
    LaunchedEffect(page) { c.harvestQualities() }

    // Cada video con todas sus calidades; las del extractor se unen al video que se reproduce (o al único)
    val merged = cleanDetected(c.detected.toList(), c.pageTitle, c.pageThumb)
    val extractor = (probe as? PageProbe.Ready)?.info
    val extractorTarget = merged.firstOrNull { it.playing } ?: merged.singleOrNull()
    fun withExtractor(m: DetectedMedia): DetectedMedia {
        val info = extractor ?: return m
        if (m !== extractorTarget || page == null) return m
        if (m.durationSec > 0 && info.durationSec > 0 && kotlin.math.abs(m.durationSec - info.durationSec) > 3) return m
        val have = m.qualities.map { it.height }.toSet()
        val extra = info.qualities.filter { it.height !in have }.map { it.copy(url = page, kind = null, mime = null) }
        if (extra.isEmpty()) return m
        return m.copy(qualities = (m.qualities + extra).sortedByDescending { if (it.height == 0) -1 else it.height })
    }
    val shown = merged.map { withExtractor(it) }
    val extractorMerged = extractorTarget != null && extractor != null &&
        !(extractorTarget.durationSec > 0 && extractor.durationSec > 0 &&
            kotlin.math.abs(extractorTarget.durationSec - extractor.durationSec) > 3)
    val playingNow = shown.firstOrNull { it.playing }
    val others = shown.filter { it.id != playingNow?.id }

    fun infoOf(m: DetectedMedia, q: Quality?, n: Int, total: Int): MediaInfo {
        val base = c.pageTitle.ifBlank { host.ifBlank { "Video" } }
        val fromExtractor = q?.url != null && q.kind == null
        return MediaInfo(
            url = q?.url ?: m.url,
            title = m.title.ifBlank { if (total > 1) "$base · $n" else base },
            uploader = host.ifBlank { null },
            thumbnail = m.thumb ?: c.pageThumb,
            durationSec = m.durationSec,
            qualities = m.qualities,
            headers = m.headers,
            kind = if (fromExtractor) null else (q?.kind ?: m.kind),
            mime = if (fromExtractor) null else (q?.mime ?: m.mime),
        )
    }

    /** DIRECT se baja tal cual; HLS/DASH y extractor eligen la calidad por altura. */
    fun heightFor(q: Quality?): Int? = if (q == null || q.kind == MediaKind.DIRECT) null else q.height.takeIf { it > 0 }

    fun startOne(m: DetectedMedia, q: Quality?) {
        onQuality(infoOf(m, q, 1, 1), heightFor(q))
        onStarted(if (q != null && q.height > 0) "Descarga iniciada · ${q.height}p" else "Descarga iniciada")
        onDismiss()
    }

    /** Descarga varios a la vez, cada uno con la mejor calidad que permitan los Ajustes. */
    fun startMany(list: List<DetectedMedia>) {
        if (list.isEmpty()) return
        list.forEachIndexed { i, m ->
            val q = pickQualityObj(m, maxHeight)
            onQuality(infoOf(m, q, i + 1, list.size), heightFor(q))
        }
        onStarted(if (list.size == 1) "Descarga iniciada" else "${list.size} descargas en cola")
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Descargar video") },
        text = {
            CompositionLocalProvider(LocalChecking provides c.harvesting) {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    page == null -> Text("Abre una página primero.")
                    SiteGuard.isBlocked(page) -> Text("Este sitio no es compatible con Flux.")
                    else -> {
                        if (playingNow != null) {
                            NowPlayingCard(
                                m = playingNow,
                                searching = probe is PageProbe.Loading,
                                onPick = { q -> startOne(playingNow, q) },
                                onPlay = { onPlay(playingNow); onDismiss() },
                            )
                        }

                        if (others.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (others.size > 1) {
                                    Checkbox(
                                        checked = selected.size == others.size,
                                        onCheckedChange = { on ->
                                            selected.clear()
                                            if (on) selected.addAll(others.map { it.id })
                                        },
                                    )
                                }
                                Text(
                                    if (playingNow != null) "Otros en la página (${others.size})" else "Detectados en la página (${others.size})",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = if (others.size > 1) 0.dp else 4.dp),
                                )
                            }
                            others.forEachIndexed { idx, m ->
                                DetectedRow(
                                    m = m,
                                    index = idx + 1,
                                    maxHeight = maxHeight,
                                    selected = m.id in selected,
                                    onToggle = { if (m.id in selected) selected.remove(m.id) else selected.add(m.id) },
                                    expanded = expanded == m.id || others.size == 1 && playingNow == null,
                                    onExpand = { expanded = if (expanded == m.id) null else m.id },
                                    onDownload = { startOne(m, pickQualityObj(m, maxHeight)) },
                                    onPick = { q -> startOne(m, q) },
                                )
                            }
                        }

                        if (c.harvesting) {
                            Row(
                                Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Buscando todas las calidades…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        // Si el extractor trajo calidades que no se pudieron unir a ningún video detectado, se listan aparte
                        when (val p = probe) {
                            PageProbe.Idle -> Unit
                            PageProbe.Loading -> if (playingNow == null) Row(
                                Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    if (c.detected.isEmpty()) "Buscando videos…" else "Buscando más calidades…",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            is PageProbe.Failed -> if (c.detected.isEmpty()) {
                                Text(
                                    "Todavía no se detectó ningún video. Reprodúcelo en la página y aparecerá aquí al instante.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            is PageProbe.Ready -> if (!extractorMerged || extractorTarget == null) {
                                val info = p.info
                                Text(
                                    "Extractor del sitio",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(info.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (info.qualities.isEmpty()) {
                                    SourceRow(
                                        title = "Mejor calidad disponible",
                                        subtitle = host,
                                        highlighted = true,
                                        onClick = { onQuality(info, null); onStarted("Descarga iniciada"); onDismiss() },
                                    )
                                } else {
                                    info.qualities.forEachIndexed { i, q ->
                                        SourceRow(
                                            title = qualityLabel(q),
                                            subtitle = if (i == 0) "Mejor calidad" else "Descargar",
                                            highlighted = i == 0,
                                            trailing = sizeLabel(q),
                                            onClick = {
                                                onQuality(info, q.height.takeIf { it > 0 })
                                                onStarted("Descarga iniciada · ${qualityLabel(q)}")
                                                onDismiss()
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        },
        confirmButton = {
            Row {
                if (selected.isNotEmpty()) {
                    TextButton(onClick = { startMany(others.filter { it.id in selected }) }) { Text("Elegidos (${selected.size})") }
                }
                if (shown.size > 1) {
                    TextButton(onClick = { startMany(shown) }) { Text("Todos (${shown.size})") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

/** El video que se reproduce: sus calidades a la vista, como una lista (la mejor resaltada). */
@Composable
private fun NowPlayingCard(
    m: DetectedMedia,
    searching: Boolean,
    onPick: (Quality) -> Unit,
    onPlay: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reproduciendo ahora", style = MaterialTheme.typography.labelLarge, color = FluxOrange, modifier = Modifier.weight(1f))
            TextButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Ver en Flux")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniThumb(m.thumb, m.headers, 56.dp, m.audioOnly, mediaUrl = m.url)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    m.title.ifBlank { kindLabel(m) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(m.durationSec.takeIf { it > 0 }?.let(::formatDuration), kindLabel(m)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val checking = LocalChecking.current
        val firstOk = m.qualities.indexOfFirst { !isUnverified(it) }
        m.qualities.forEachIndexed { i, q ->
            val ok = !isUnverified(q)
            SourceRow(
                title = qualityLabel(q),
                subtitle = when {
                    !ok -> if (checking) "Comprobando…" else "Aún sin comprobar"
                    i == firstOk && m.qualities.size > 1 -> "Mejor calidad"
                    else -> "Descargar"
                },
                highlighted = i == firstOk,
                trailing = if (ok) sizeLabel(q) else null,
                onClick = { onPick(q) },
                available = ok,
            )
        }
        if (searching) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Buscando más calidades…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DetectedRow(
    m: DetectedMedia,
    index: Int,
    maxHeight: Int,
    selected: Boolean,
    onToggle: () -> Unit,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDownload: () -> Unit,
    onPick: (Quality) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            MiniThumb(m.thumb, m.headers, 56.dp, m.audioOnly, mediaUrl = m.url)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    m.title.ifBlank {
                        listOfNotNull(
                            m.qualities.maxByOrNull { it.height }?.takeIf { it.height > 0 }?.let { "${it.height}p" },
                            m.durationSec.takeIf { it > 0 }?.let(::formatDuration),
                        ).joinToString(" · ").ifBlank { "Video $index" }
                    },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        m.durationSec.takeIf { it > 0 }?.let(::formatDuration),
                        if (m.qualities.size > 1) "${m.qualities.size} calidades" else null,
                        topLabel(m, maxHeight),
                    ).joinToString(" · ").ifBlank { kindLabel(m) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDownload) {
                Icon(Icons.Default.Download, contentDescription = "Descargar", tint = FluxOrange)
            }
        }
        if (expanded) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val checking = LocalChecking.current
                val firstOk = m.qualities.indexOfFirst { !isUnverified(it) }
                m.qualities.forEachIndexed { i, q ->
                    val ok = !isUnverified(q)
                    SourceRow(
                        title = qualityLabel(q),
                        subtitle = when {
                            !ok -> if (checking) "Comprobando…" else "Aún sin comprobar"
                            i == firstOk && m.qualities.size > 1 -> "Mejor calidad"
                            else -> kindLabel(m)
                        },
                        highlighted = i == firstOk,
                        trailing = if (ok) sizeLabel(q) else null,
                        onClick = { onPick(q) },
                        available = ok,
                    )
                }
            }
        }
    }
}

/** ¿El selector está recorriendo las calidades del reproductor? (las que aún no se pueden bajar muestran "Comprobando…") */
private val LocalChecking = androidx.compose.runtime.compositionLocalOf { false }

@Composable
private fun SourceRow(
    title: String,
    subtitle: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    trailing: String? = null,
    available: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .then(if (available) Modifier.clickable(onClick = onClick) else Modifier.alpha(0.5f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Download,
            contentDescription = null,
            tint = if (highlighted) FluxOrange else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                trailing,
                style = MaterialTheme.typography.labelLarge,
                color = if (highlighted) FluxOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Al mantener pulsado un enlace o una imagen: abrir en pestaña nueva (o en segundo plano), copiar y compartir. */
@Composable
private fun LinkMenuDialog(menu: LinkMenu, c: BrowserController, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val target = menu.link ?: menu.image.orEmpty()
    fun copy(text: String, what: String) {
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
        Toast.makeText(ctx, what, Toast.LENGTH_SHORT).show()
    }
    @Composable
    fun Item(label: String, onClick: () -> Unit) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onClick(); onDismiss() }
                .padding(horizontal = 8.dp, vertical = 14.dp),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Uri.parse(target).host.orEmpty().removePrefix("www.").ifBlank { "Enlace" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(target, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                if (menu.link != null) {
                    Item("Abrir en pestaña nueva") { c.openInNewTab(menu.link) }
                    Item("Abrir en segundo plano") { c.openInNewTab(menu.link, background = true) }
                    Item("Copiar enlace") { copy(menu.link, "Enlace copiado") }
                    Item("Compartir enlace") {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, menu.link)
                        }
                        ctx.startActivity(android.content.Intent.createChooser(send, "Compartir"))
                    }
                }
                if (menu.image != null) {
                    Item("Abrir imagen en pestaña nueva") { c.openInNewTab(menu.image) }
                    Item("Copiar dirección de la imagen") { copy(menu.image, "Dirección copiada") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

/** Lo que falló o se bloqueó en la página: sirve para ver por qué una miniatura o un video no cargan. */
@Composable
private fun DiagDialog(c: BrowserController, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnóstico del sitio") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Página: ${c.currentUrl ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Modo: ${if (c.desktopMode) "escritorio" else "móvil"} · Videos detectados: ${c.detected.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (c.diag.isEmpty()) {
                    Text("No hay errores ni bloqueos registrados en esta página.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    c.diag.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val text = buildString {
                    appendLine("Página: ${c.currentUrl}")
                    appendLine("Modo: ${if (c.desktopMode) "escritorio" else "móvil"}")
                    c.diag.forEach { appendLine(it) }
                }
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                Toast.makeText(ctx, "Copiado", Toast.LENGTH_SHORT).show()
            }) { Text("Copiar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun LibraryDialog(c: BrowserController, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val list = if (tab == 0) c.bookmarks.toList() else c.history.toList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Biblioteca") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Marcadores") })
                    FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Historial") })
                }
                Spacer(Modifier.height(8.dp))
                if (list.isEmpty()) {
                    Text("Nada por aquí todavía.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(list) { e ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpen(e.url) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        e.url.removePrefix("https://"),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (tab == 0) {
                                    IconButton(onClick = { c.removeBookmark(e) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Quitar marcador")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = {
            if (tab == 1 && c.history.isNotEmpty()) {
                TextButton(onClick = { c.clearHistory() }) { Text("Borrar historial") }
            }
        },
    )
}

private fun shortName(url: String): String {
    val u = Uri.parse(url)
    return "${u.host.orEmpty()}/${u.lastPathSegment.orEmpty()}"
}
