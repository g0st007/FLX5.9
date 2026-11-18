package app.flux.downloader.ui

import android.content.ContentValues
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flux.downloader.engine.DownloadManager
import app.flux.downloader.engine.Status
import app.flux.downloader.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Un archivo que Flux guardó en Descargas/Flux, Películas/Flux o Música/Flux. */
private data class FluxFile(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val durationMs: Long,
) {
    val isAudio: Boolean get() = mime.startsWith("audio/")
    val ext: String get() = name.substringAfterLast('.', "").uppercase().take(4)
    val title: String get() = name.substringBeforeLast('.')
}

private fun loadFiles(ctx: Context): List<FluxFile> {
    val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DURATION,
    )
    val rp = MediaStore.MediaColumns.RELATIVE_PATH
    val selection = "$rp LIKE ? OR $rp LIKE ? OR $rp LIKE ?"
    val args = arrayOf("Download/Flux%", "Movies/Flux%", "Music/Flux%")
    val out = ArrayList<FluxFile>()
    ctx.contentResolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
        val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val iDur = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
        while (c.moveToNext()) {
            val mime = c.getString(iMime).orEmpty()
            if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
            out += FluxFile(
                uri = ContentUris.withAppendedId(collection, c.getLong(iId)),
                name = c.getString(iName).orEmpty(),
                mime = mime,
                size = c.getLong(iSize),
                durationMs = if (c.isNull(iDur)) 0L else c.getLong(iDur),
            )
        }
    }
    return out
}

/** Resultado de importar una selección de archivos. */
private class ImportResult(val ok: Int, val skipped: Int, val failed: Int)

private fun displayNameOf(ctx: Context, uri: Uri): String? =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

private fun mimeOf(ctx: Context, uri: Uri, name: String): String {
    val declared = ctx.contentResolver.getType(uri).orEmpty()
    if (declared.startsWith("video/") || declared.startsWith("audio/")) return declared
    val ext = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: declared
}

/**
 * Copia un archivo elegido por el usuario a la carpeta de Flux (Películas/Flux o Música/Flux) para que aparezca
 * en el gestor igual que lo descargado. Devuelve 1 = importado, 0 = no es video ni audio, -1 = falló.
 */
private fun importOne(ctx: Context, uri: Uri): Int {
    val name = displayNameOf(ctx, uri)?.takeIf { it.isNotBlank() } ?: "archivo"
    val mime = mimeOf(ctx, uri, name)
    val isVideo = mime.startsWith("video/")
    val isAudio = mime.startsWith("audio/")
    if (!isVideo && !isAudio) return 0
    val collection = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/Flux" else "Music/Flux")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val target = ctx.contentResolver.insert(collection, values) ?: return -1
    return try {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("sin lectura")
        val output = ctx.contentResolver.openOutputStream(target) ?: throw java.io.IOException("sin escritura")
        input.use { i -> output.use { o -> i.copyTo(o, 256 * 1024) } }
        ctx.contentResolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        1
    } catch (e: Exception) {
        runCatching { ctx.contentResolver.delete(target, null, null) }
        -1
    }
}

private val thumbCache = LruCache<String, Bitmap>(48)

private fun loadThumb(ctx: Context, f: FluxFile): Bitmap? {
    thumbCache.get(f.uri.toString())?.let { return it }
    val bmp = runCatching { ctx.contentResolver.loadThumbnail(f.uri, Size(480, 480), null) }.getOrNull()
        ?: runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, f.uri)
                if (f.isAudio) r.embeddedPicture?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                else r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                r.release()
            }
        }.getOrNull()
    if (bmp != null) thumbCache.put(f.uri.toString(), bmp)
    return bmp
}

@Composable
fun FilesScreen(onOpenVault: () -> Unit, onPlay: (PlayerSource) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloads by DownloadManager.downloads.collectAsStateWithLifecycle()
    val doneCount = downloads.count { it.status == Status.DONE }

    var refresh by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<FluxFile>?>(null) }
    var filter by remember { mutableIntStateOf(0) } // 0 todo · 1 video · 2 audio
    var needPin by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<FluxFile?>(null) }
    var importing by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (hechos, total)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importing = 0 to uris.size
            var ok = 0
            var skipped = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                uris.forEachIndexed { i, u ->
                    when (importOne(ctx, u)) {
                        1 -> ok++
                        0 -> skipped++
                        else -> failed++
                    }
                    withContext(Dispatchers.Main) { importing = (i + 1) to uris.size }
                }
            }
            importing = null
            val msg = buildString {
                append(if (ok == 1) "1 archivo importado" else "$ok archivos importados")
                if (skipped > 0) append(" · $skipped no son video ni audio")
                if (failed > 0) append(" · $failed fallaron")
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            refresh++
        }
    }

    LaunchedEffect(doneCount, refresh) {
        files = withContext(Dispatchers.IO) { runCatching { loadFiles(ctx) }.getOrDefault(emptyList()) }
    }

    val all = files.orEmpty()
    val shown = when (filter) {
        1 -> all.filter { !it.isAudio }
        2 -> all.filter { it.isAudio }
        else -> all
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Archivos", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { picker.launch(arrayOf("video/*", "audio/*")) }) {
                    Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Importar a Flux")
                }
            }
        }
        item {
            FolderTile(
                icon = Icons.Default.Folder,
                title = "Flux",
                subtitle = if (files == null) "…" else "${all.size} archivos · ${formatSize(all.sumOf { it.size })}",
                onClick = { filter = 0 },
            )
        }
        item {
            FolderTile(
                icon = Icons.Default.Lock,
                title = "Carpeta privada",
                subtitle = if (Vault.hasPin) "Protegida con código" else "Crear código",
                onClick = onOpenVault,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Todo", "Videos", "Audio").forEachIndexed { i, label ->
                    FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
                }
            }
        }

        if (files != null && shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 40.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Folder, contentDescription = null, tint = FluxOrange, modifier = Modifier.size(32.dp)) }
                    Spacer(Modifier.height(16.dp))
                    Text("Aún no hay archivos aquí", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Lo que descargues con Flux aparecerá aquí. Con “Importar a Flux” puedes traer tus propios videos y audios desde el teléfono.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        items(shown, key = { it.uri.toString() }) { f ->
            FileCard(
                f = f,
                onPlay = { onPlay(PlayerSource(f.uri, f.title)) },
                onShare = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = f.mime
                        putExtra(Intent.EXTRA_STREAM, f.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(send, "Compartir"))
                },
                onMoveToVault = {
                    if (!Vault.hasPin) needPin = true
                    else scope.launch {
                        val ok = withContext(Dispatchers.IO) { Vault.importFromUri(f.uri, f.title, null, f.mime) }
                        Toast.makeText(ctx, if (ok) "Movido a la carpeta privada" else "No se pudo mover el archivo", Toast.LENGTH_SHORT).show()
                        refresh++
                    }
                },
                onDelete = { toDelete = f },
            )
        }
    }

    importing?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Importando a Flux") },
            text = {
                Column {
                    Text("$done de $total", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Se copian a la carpeta de Flux; los originales no se tocan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
        )
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("¿Eliminar el archivo?") },
            text = { Text("“${f.title}” se borrará de tu dispositivo.") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = runCatching { ctx.contentResolver.delete(f.uri, null, null) > 0 }.getOrDefault(false)
                    Toast.makeText(ctx, if (ok) "Archivo eliminado" else "No se pudo eliminar", Toast.LENGTH_SHORT).show()
                    toDelete = null
                    refresh++
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
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

@Composable
private fun FolderTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = FluxOrange) }
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FileCard(f: FluxFile, onPlay: () -> Unit, onShare: () -> Unit, onMoveToVault: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val thumb by produceState<Bitmap?>(null, f.uri) {
        value = withContext(Dispatchers.IO) { loadThumb(ctx, f) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            val b = thumb
            if (b != null) {
                Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    if (f.isAudio) Icons.Default.MusicNote else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
            }
            if (f.durationMs > 0) {
                Text(
                    formatDuration(f.durationMs / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(androidx.compose.ui.graphics.Color(0x99000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(f.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${f.ext} · ${formatSize(f.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Más opciones")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Reproducir") }, onClick = { menu = false; onPlay() })
                    DropdownMenuItem(text = { Text("Compartir") }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text("Mover a la carpeta privada") }, onClick = { menu = false; onMoveToVault() })
                    DropdownMenuItem(
                        text = { Text("Eliminar", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}
