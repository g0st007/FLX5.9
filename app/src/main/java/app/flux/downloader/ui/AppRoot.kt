package app.flux.downloader.ui

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flux.downloader.browser.BrowserScreen
import app.flux.downloader.engine.Choice
import app.flux.downloader.settings.AppSettings
import app.flux.downloader.vault.Vault

@Composable
fun AppRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var player by remember { mutableStateOf<PlayerSource?>(null) }
    var showVault by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val allowCaptures by AppSettings.allowVaultCaptures.collectAsStateWithLifecycle()

    // La carpeta privada se bloquea sola cuando la app deja de verse
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) Vault.lock()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Sin capturas ni grabación de pantalla dentro de lo privado (si el ajuste lo pide)
    val secure = (showVault || player?.isPrivate == true) && !allowCaptures
    DisposableEffect(secure) {
        val window = ctx.findActivity()?.window
        if (secure) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    val current = player
    // El navegador se crea la primera vez que se entra y desde entonces sigue vivo (oculto) para no recargar la página
    var browserLive by remember { mutableStateOf(tab == 1) }
    if (tab == 1 && !browserLive) browserLive = true
    val background = MaterialTheme.colorScheme.background
    val blockTouches = Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
    Box(Modifier.fillMaxSize().background(background)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Box(Modifier.weight(1f)) {
                if (browserLive) {
                    BrowserScreen(
                        visible = tab == 1 && current == null && !showVault,
                        session = vm.browserSession,
                        pendingUrl = vm.browserRequest,
                        onPendingConsumed = { vm.browserRequest = null },
                        onPlayStream = { player = it },
                        onDownloadRequest = { url ->
                            vm.onSharedText(url)
                            tab = 0
                        },
                        onDownloadQuality = { info, height ->
                            vm.startDownload(info, Choice(false, height, AppSettings.audioFormat.value))
                        },
                    )
                }
                if (tab != 1) {
                    Box(Modifier.fillMaxSize().background(background).then(blockTouches)) {
                        when (tab) {
                            0 -> MainScreen(
                                vm = vm,
                                onPlay = { player = it },
                                onOpenVault = { showVault = true },
                                onOpenInBrowser = { url -> vm.openInBrowser(url); tab = 1 },
                            )
                            2 -> FilesScreen(onOpenVault = { showVault = true }, onPlay = { player = it })
                            else -> SettingsScreen(onOpenVault = { showVault = true })
                        }
                    }
                }
            }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.Download, contentDescription = null) },
                        label = { Text("Descargas") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Default.Public, contentDescription = null) },
                        label = { Text("Navegador") },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                        label = { Text("Archivos") },
                    )
                    NavigationBarItem(
                        selected = tab == 3,
                        onClick = { tab = 3 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Ajustes") },
                    )
                }
        }
        if (current != null) {
            Box(Modifier.fillMaxSize().background(background).then(blockTouches)) {
                PlayerScreen(
                    source = current,
                    onDownload = { info, height ->
                        vm.startDownload(info, Choice(false, height, AppSettings.audioFormat.value))
                    },
                    onClose = { player = null },
                )
            }
        } else if (showVault) {
            Box(Modifier.fillMaxSize().background(background).then(blockTouches)) {
                VaultScreen(
                    onClose = { Vault.lock(); showVault = false },
                    onPlay = { player = it },
                )
            }
        }
    }
}
