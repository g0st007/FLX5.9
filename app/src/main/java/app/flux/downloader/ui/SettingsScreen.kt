package app.flux.downloader.ui

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flux.downloader.browser.BrowserPrefs
import app.flux.downloader.browser.BrowserStore
import app.flux.downloader.browser.FiltersDialog
import app.flux.downloader.settings.AppSettings
import app.flux.downloader.settings.AppSettings.ThemeMode
import app.flux.downloader.settings.AppSettings.ProgressStyle
import app.flux.downloader.vault.Vault

private enum class Pick { THEME, SPEED, AUDIO, HEIGHT, PROGRESS }

@Composable
fun SettingsScreen(onOpenVault: () -> Unit) {
    val ctx = LocalContext.current

    val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
    val wifiOnly by AppSettings.wifiOnly.collectAsStateWithLifecycle()
    val speed by AppSettings.speedLimit.collectAsStateWithLifecycle()
    val audio by AppSettings.audioFormat.collectAsStateWithLifecycle()
    val maxHeight by AppSettings.maxHeight.collectAsStateWithLifecycle()
    val gallery by AppSettings.saveToGallery.collectAsStateWithLifecycle()
    val vaultDefault by AppSettings.vaultByDefault.collectAsStateWithLifecycle()
    val captures by AppSettings.allowVaultCaptures.collectAsStateWithLifecycle()
    val progressStyle by AppSettings.progressStyle.collectAsStateWithLifecycle()

    val browserPrefs = remember { BrowserPrefs(ctx.applicationContext) }
    var blockAds by remember { mutableStateOf(browserPrefs.blockAds) }
    var blockRedirects by remember { mutableStateOf(browserPrefs.blockRedirects) }
    val defaultDesktop by AppSettings.defaultDesktop.collectAsStateWithLifecycle()
    val ownPlayer by AppSettings.ownPlayer.collectAsStateWithLifecycle()

    var choice by remember { mutableStateOf<Pick?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var needPin by remember { mutableStateOf(false) }

    val themeOptions = listOf("Automático (sistema)" to ThemeMode.SYSTEM, "Claro" to ThemeMode.LIGHT, "Oscuro" to ThemeMode.DARK)
    val speedOptions = listOf("Sin límite" to "", "500 KB/s" to "500K", "1 MB/s" to "1M", "2 MB/s" to "2M", "5 MB/s" to "5M")
    val audioOptions = listOf("MP3" to "mp3", "M4A" to "m4a", "OPUS" to "opus")
    val progressOptions = listOf(
        "MB descargados y peso total" to ProgressStyle.SIZE,
        "Porcentaje y tiempo restante" to ProgressStyle.PERCENT_ETA,
        "Todo junto" to ProgressStyle.BOTH,
    )
    val heightOptions = listOf("La mejor disponible" to 0, "Hasta 1080p" to 1080, "Hasta 720p" to 720, "Hasta 480p" to 480)

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { Text("Ajustes", style = MaterialTheme.typography.headlineSmall) }

        item {
            Section("Apariencia") {
                SettingRow(
                    Icons.Default.Palette, "Tema",
                    subtitle = themeOptions.first { it.second == theme }.first,
                    onClick = { choice = Pick.THEME },
                )
            }
        }

        item {
            Section("Descargas") {
                SwitchRow(
                    Icons.Default.Wifi, "Descargar solo con Wi-Fi",
                    "Las descargas esperan en cola si no hay una red sin límite de datos",
                    wifiOnly, AppSettings::setWifiOnly,
                )
                RowDivider()
                SettingRow(
                    Icons.Default.Speed, "Límite de velocidad",
                    subtitle = speedOptions.firstOrNull { it.second == speed }?.first ?: "Sin límite",
                    onClick = { choice = Pick.SPEED },
                )
                RowDivider()
                SettingRow(
                    Icons.Default.HighQuality, "Calidad de video preseleccionada",
                    subtitle = heightOptions.firstOrNull { it.second == maxHeight }?.first ?: "La mejor disponible",
                    onClick = { choice = Pick.HEIGHT },
                )
                RowDivider()
                SettingRow(
                    Icons.Default.Info, "Progreso de la descarga",
                    subtitle = progressOptions.first { it.second == progressStyle }.first,
                    onClick = { choice = Pick.PROGRESS },
                )
                RowDivider()
                SettingRow(
                    Icons.Default.MusicNote, "Formato de audio",
                    subtitle = audio.uppercase(),
                    onClick = { choice = Pick.AUDIO },
                )
                RowDivider()
                SwitchRow(
                    Icons.Default.PhotoLibrary, "Mostrar en la galería",
                    "Los videos van a Películas/Flux y el audio a Música/Flux",
                    gallery, AppSettings::setSaveToGallery,
                )
                RowDivider()
                SettingRow(
                    Icons.Default.Folder, "Dónde se guarda",
                    subtitle = if (gallery) "Películas/Flux · Música/Flux" else "Descargas/Flux",
                )
            }
        }

        item {
            Section("Navegador") {
                SwitchRow(
                    Icons.Default.Shield, "Bloquear anuncios y rastreadores",
                    "Se aplica al abrir el navegador",
                    blockAds,
                ) { blockAds = it; browserPrefs.blockAds = it }
                RowDivider()
                SwitchRow(
                    Icons.Default.Block, "Bloquear redirecciones y ventanas emergentes",
                    null,
                    blockRedirects,
                ) { blockRedirects = it; browserPrefs.blockRedirects = it }
                RowDivider()
                SwitchRow(
                    Icons.Default.Public, "Abrir sitios en modo escritorio",
                    "Se puede cambiar por sitio desde el menú ⋮ del navegador y Flux lo recuerda",
                    defaultDesktop, AppSettings::setDefaultDesktop,
                )
                RowDivider()
                SwitchRow(
                    Icons.Default.PlayArrow, "Reproductor de Flux en pantalla completa",
                    "Al pasar un video a pantalla completa se abre en el reproductor de Flux: gestos, velocidad, calidad y más",
                    ownPlayer, AppSettings::setOwnPlayer,
                )
                RowDivider()
                SettingRow(
                    Icons.Default.FilterList, "Listas de filtros",
                    subtitle = "EasyList, uBlock y más",
                    onClick = { showFilters = true },
                )
                RowDivider()
                SettingRow(
                    Icons.Default.DeleteSweep, "Borrar cookies, caché e historial",
                    onClick = { confirmClear = true },
                )
            }
        }

        item {
            Section("Privacidad y seguridad") {
                SettingRow(
                    Icons.Default.Lock, "Carpeta privada",
                    subtitle = "Protegida con un código de ${Vault.PIN_LENGTH} dígitos",
                    onClick = onOpenVault,
                )
                RowDivider()
                SwitchRow(
                    Icons.Default.Folder, "Guardar descargas nuevas en la privada",
                    "No aparecerán en Descargas ni en la galería",
                    vaultDefault,
                ) { on ->
                    if (on && !Vault.hasPin) needPin = true else AppSettings.setVaultByDefault(on)
                }
                RowDivider()
                SwitchRow(
                    Icons.Default.Visibility, "Permitir capturas en la privada",
                    "Si está apagado, la carpeta y el reproductor privado bloquean capturas y grabaciones de pantalla",
                    captures, AppSettings::setAllowVaultCaptures,
                )
            }
        }

        item {
            val version = remember {
                runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "—"
            }
            Section("Acerca de") {
                SettingRow(Icons.Default.Info, "Flux", subtitle = "Versión $version")
                RowDivider()
                SettingRow(
                    Icons.Default.Info, "Uso responsable",
                    subtitle = "Descarga solo contenido que tengas derecho a guardar",
                )
            }
        }
    }

    when (choice) {
        Pick.THEME -> ChoiceDialog("Tema", themeOptions, theme, AppSettings::setThemeMode) { choice = null }
        Pick.SPEED -> ChoiceDialog("Límite de velocidad", speedOptions, speed, AppSettings::setSpeedLimit) { choice = null }
        Pick.AUDIO -> ChoiceDialog("Formato de audio", audioOptions, audio, AppSettings::setAudioFormat) { choice = null }
        Pick.HEIGHT -> ChoiceDialog("Calidad preseleccionada", heightOptions, maxHeight, AppSettings::setMaxHeight) { choice = null }
        Pick.PROGRESS -> ChoiceDialog("Progreso de la descarga", progressOptions, progressStyle, AppSettings::setProgressStyle) { choice = null }
        null -> Unit
    }

    if (showFilters) FiltersDialog { showFilters = false }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("¿Borrar datos de navegación?") },
            text = { Text("Se eliminarán cookies, caché, datos de sitios e historial. Tus marcadores se conservan.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    clearBrowsingData(ctx.applicationContext)
                    Toast.makeText(ctx, "Datos de navegación borrados", Toast.LENGTH_SHORT).show()
                }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }

    if (needPin) {
        AlertDialog(
            onDismissRequest = { needPin = false },
            title = { Text("Primero crea tu código") },
            text = { Text("Para guardar descargas en la carpeta privada necesitas protegerla con un código.") },
            confirmButton = { TextButton(onClick = { needPin = false; onOpenVault() }) { Text("Crear código") } },
            dismissButton = { TextButton(onClick = { needPin = false }) { Text("Ahora no") } },
        )
    }
}

private fun clearBrowsingData(ctx: android.content.Context) {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
    runCatching {
        WebView(ctx).apply {
            clearCache(true)
            clearHistory()
            destroy()
        }
    }
    BrowserStore(ctx).saveHistory(emptyList())
}

// ----------------------------------------------------------------------
// Componentes
// ----------------------------------------------------------------------
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = FluxOrange,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) { Column(content = content) }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(
        icon, title, subtitle,
        onClick = { onChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (label, value) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(value); onDismiss() }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
