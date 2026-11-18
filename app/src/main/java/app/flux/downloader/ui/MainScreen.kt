package app.flux.downloader.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import app.flux.downloader.engine.SiteGuard
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flux.downloader.engine.Choice
import app.flux.downloader.engine.DownloadItem
import app.flux.downloader.engine.Engine
import app.flux.downloader.engine.MediaInfo
import app.flux.downloader.engine.Status
import app.flux.downloader.settings.AppSettings
import app.flux.downloader.settings.AppSettings.ProgressStyle
import app.flux.downloader.vault.Vault
import coil.compose.AsyncImage

@Composable
fun MainScreen(
    vm: MainViewModel,
    onPlay: (PlayerSource) -> Unit,
    onOpenVault: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
) {
    val link by vm.link.collectAsStateWithLifecycle()
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val engineReady by Engine.ready.collectAsStateWithLifecycle()
    val engineError by Engine.error.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var needPin by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Cabecera: marca + titular de marketing (solo en la pantalla "vacía")
        item {
            val showHero = downloads.isEmpty() && analysis is Analysis.Idle
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Flux", style = MaterialTheme.typography.titleLarge)
                    Text(".", style = MaterialTheme.typography.titleLarge, color = FluxOrange)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onOpenVault) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Carpeta privada",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showHero) {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = FluxOrange)) { append("+1.000 sitios.") }
                            append("\nUna sola app.")
                        },
                        style = FluxHeroStyle,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Pega un enlace, elige la calidad y mira cuánto pesa antes de descargar. " +
                            "Video o audio, sin cuentas y sin anuncios.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        // Entrada del enlace
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(14.dp)) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = vm::onLinkChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://…") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { vm.analyze() }),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { clipboard.getText()?.text?.let { vm.onSharedText(it) } },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Pegar")
                        }
                        Button(
                            onClick = vm::analyze,
                            enabled = link.isNotBlank() && analysis !is Analysis.Loading,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                        ) { Text("Analizar") }
                    }
                }
            }
        }

        // Sitios compatibles (solo en la pantalla "vacía")
        if (downloads.isEmpty() && analysis is Analysis.Idle) {
            item { SiteChips() }
        }

        // Estado del motor
        if (engineError != null) {
            item { Text(engineError.orEmpty(), color = MaterialTheme.colorScheme.error) }
        } else if (!engineReady) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Preparando el motor de descargas…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Resultado del análisis
        when (val a = analysis) {
            Analysis.Idle -> Unit
            Analysis.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is Analysis.Error -> item {
                val url = SiteGuard.extractUrl(link)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(a.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    if (url != null && !SiteGuard.isBlocked(url)) {
                        OutlinedButton(
                            onClick = { onOpenInBrowser(url) },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Abrir en el navegador de Flux")
                        }
                    }
                }
            }
            is Analysis.Ready -> item { PreviewCard(a.info, onDownload = vm::startDownload) }
        }

        // Lista de descargas
        if (downloads.isNotEmpty()) {
            item { Text("Descargas", style = MaterialTheme.typography.titleLarge) }
            items(downloads, key = { it.id }) { d ->
                DownloadRow(
                    d = d,
                    onCancel = { vm.cancel(d.id) },
                    onRemove = { vm.remove(d.id) },
                    onPlay = { d.uri?.let { onPlay(PlayerSource(it, d.title)) } },
                    onOpenExternal = { openFile(context, d) },
                    onMoveToVault = {
                        if (!Vault.hasPin) needPin = true
                        else vm.moveToVault(d) { ok ->
                            Toast.makeText(
                                context,
                                if (ok) "Movido a la carpeta privada" else "No se pudo mover el archivo",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }
    }

    if (needPin) {
        AlertDialog(
            onDismissRequest = { needPin = false },
            title = { Text("Primero crea tu código") },
            text = { Text("Para usar la carpeta privada necesitas protegerla con un código de ${Vault.PIN_LENGTH} dígitos.") },
            confirmButton = { TextButton(onClick = { needPin = false; onOpenVault() }) { Text("Crear código") } },
            dismissButton = { TextButton(onClick = { needPin = false }) { Text("Ahora no") } },
        )
    }
}

private fun defaultHeight(info: MediaInfo): Int? {
    val max = AppSettings.maxHeight.value
    if (max == 0) return info.heights.firstOrNull()
    return info.heights.firstOrNull { it <= max } ?: info.heights.lastOrNull()
}

@Composable
private fun PreviewCard(info: MediaInfo, onDownload: (MediaInfo, Choice) -> Unit) {
    var audioOnly by remember(info.url) { mutableStateOf(false) }
    var height by remember(info.url) { mutableStateOf(defaultHeight(info)) }
    var audioFormat by remember(info.url) { mutableStateOf(AppSettings.audioFormat.value) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(
                model = info.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Text(info.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                info.uploader,
                info.durationSec.takeIf { it > 0 }?.let(::formatDuration),
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !audioOnly, onClick = { audioOnly = false }, label = { Text("Video") })
                FilterChip(selected = audioOnly, onClick = { audioOnly = true }, label = { Text("Solo audio") })
            }

            if (audioOnly) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("mp3", "m4a", "opus").forEach { f ->
                        FilterChip(selected = audioFormat == f, onClick = { audioFormat = f }, label = { Text(f.uppercase()) })
                    }
                }
            } else if (info.heights.isEmpty()) {
                Text("Se descargará en la mejor calidad disponible", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(info.qualities) { q ->
                        FilterChip(
                            selected = height == q.height,
                            onClick = { height = q.height },
                            label = {
                                Column(Modifier.padding(vertical = 4.dp)) {
                                    Text(if (q.height == 0) "Original" else "${q.height}p", style = MaterialTheme.typography.labelLarge)
                                    Text(
                                        q.bytes?.let { (if (q.estimated) "≈ " else "") + formatSize(it) } ?: "—",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                }
            }

            Button(
                onClick = { onDownload(info, Choice(audioOnly, if (audioOnly) null else height?.takeIf { it > 0 }, audioFormat)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Descargar")
            }
        }
    }
}

@Composable
private fun DownloadRow(
    d: DownloadItem,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onPlay: () -> Unit,
    onOpenExternal: () -> Unit,
    onMoveToVault: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val progressStyle by AppSettings.progressStyle.collectAsStateWithLifecycle()
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = d.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    statusText(d, progressStyle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                if (d.status == Status.RUNNING || d.status == Status.SAVING) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { d.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
            when (d.status) {
                Status.RUNNING, Status.QUEUED -> IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancelar")
                }
                Status.SAVING -> Unit
                Status.DONE -> {
                    if (d.uri != null) {
                        IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir") }
                    } else if (d.inVault) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "En la carpeta privada",
                            tint = FluxOrange,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Más opciones") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (d.uri != null) {
                                DropdownMenuItem(text = { Text("Abrir con otra app") }, onClick = { menu = false; onOpenExternal() })
                                DropdownMenuItem(text = { Text("Mover a la carpeta privada") }, onClick = { menu = false; onMoveToVault() })
                            }
                            DropdownMenuItem(text = { Text("Quitar de la lista") }, onClick = { menu = false; onRemove() })
                        }
                    }
                }
                else -> IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Quitar de la lista")
                }
            }
        }
    }
}

private fun statusText(d: DownloadItem, style: ProgressStyle): String = when (d.status) {
    Status.QUEUED -> if (d.waitingWifi) "${d.label} · Esperando Wi-Fi" else "${d.label} · En cola"
    Status.RUNNING -> {
        val pct = "${(d.progress * 100).toInt()}%"
        val eta = if (d.etaSec > 0) "quedan ${formatDuration(d.etaSec)}" else null
        val size = when {
            d.totalBytes > 0 -> "${formatSize(d.downloadedBytes)} de ${formatSize(d.totalBytes)}"
            d.downloadedBytes > 0 -> formatSize(d.downloadedBytes)
            else -> null
        }
        val parts = when (style) {
            ProgressStyle.PERCENT_ETA -> listOfNotNull(pct, eta)
            ProgressStyle.SIZE -> listOfNotNull(size ?: pct)
            ProgressStyle.BOTH -> listOfNotNull(pct, size, eta)
        }
        (listOf(d.label) + parts).joinToString(" · ")
    }
    Status.SAVING -> "${d.label} · Guardando…"
    Status.DONE -> "${d.label} · Guardado en ${d.location ?: "Descargas/Flux"}"
    Status.FAILED -> d.error ?: "Error en la descarga"
    Status.CANCELED -> "Cancelado"
}

private fun openFile(ctx: Context, d: DownloadItem) {
    val uri = d.uri ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, d.mime ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SiteChips() {
    val sites = listOf("TikTok", "Instagram", "X", "Reddit", "Facebook", "Vimeo", "Twitch", "Dailymotion")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sites.forEach { SiteChip(it, highlighted = false) }
        SiteChip("y más de 1.000", highlighted = true)
    }
}

@Composable
private fun SiteChip(text: String, highlighted: Boolean) {
    Box(
        Modifier
            .border(
                BorderStroke(
                    1.dp,
                    if (highlighted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                ),
                CircleShape,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (highlighted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
