package app.flux.downloader.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flux.downloader.vault.PinResult
import app.flux.downloader.vault.Vault
import app.flux.downloader.vault.VaultItem
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Carpeta privada: crear código → pedir código → lista de archivos. Se bloquea al salir. */
@Composable
fun VaultScreen(onClose: () -> Unit, onPlay: (PlayerSource) -> Unit) {
    val unlocked by Vault.unlocked.collectAsStateWithLifecycle()
    var settingPin by remember { mutableStateOf(!Vault.hasPin) }
    var isChange by remember { mutableStateOf(false) }

    BackHandler { onClose() }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        when {
            settingPin -> PinSetup(
                isChange = isChange,
                onDone = { settingPin = false; isChange = false },
                onCancel = { if (Vault.hasPin) { settingPin = false; isChange = false } else onClose() },
            )
            !unlocked -> PinEntry(onClose)
            else -> VaultList(
                onClose = onClose,
                onPlay = onPlay,
                onChangePin = { isChange = true; settingPin = true },
            )
        }
    }
}

// ----------------------------------------------------------------------
// Código
// ----------------------------------------------------------------------
@Composable
private fun PinEntry(onClose: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var lockedSecs by remember { mutableIntStateOf(0) }

    LaunchedEffect(lockedSecs) {
        if (lockedSecs > 0) {
            delay(1_000)
            lockedSecs -= 1
            if (lockedSecs == 0) error = null
        }
    }

    PinPad(
        title = "Carpeta privada",
        subtitle = "Introduce tu código",
        error = when {
            lockedSecs > 0 -> "Demasiados intentos. Espera $lockedSecs s"
            else -> error
        },
        enabled = lockedSecs == 0,
        onComplete = { pin ->
            when (val r = Vault.verify(pin)) {
                PinResult.Ok -> error = null
                is PinResult.Wrong -> error = "Código incorrecto · te quedan ${r.attemptsLeft} intentos"
                is PinResult.Locked -> { error = null; lockedSecs = r.seconds }
            }
        },
        onCancel = onClose,
    )
}

@Composable
private fun PinSetup(isChange: Boolean, onDone: () -> Unit, onCancel: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    PinPad(
        title = when {
            first != null -> "Repite el código"
            isChange -> "Nuevo código"
            else -> "Crea tu código"
        },
        subtitle = if (first == null) "${Vault.PIN_LENGTH} dígitos para proteger tu carpeta privada" else null,
        error = error,
        enabled = true,
        onComplete = { pin ->
            val f = first
            when {
                f == null -> { first = pin; error = null }
                f == pin -> { Vault.setPin(pin); onDone() }
                else -> { first = null; error = "Los códigos no coinciden. Inténtalo de nuevo" }
            }
        },
        onCancel = onCancel,
    )
}

@Composable
private fun PinPad(
    title: String,
    subtitle: String?,
    error: String?,
    enabled: Boolean,
    onComplete: (String) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(pin) {
        if (pin.length == Vault.PIN_LENGTH) {
            onComplete(pin)
            pin = ""
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = FluxOrange, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            error ?: subtitle.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(Vault.PIN_LENGTH) { i ->
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (i < pin.length) FluxOrange else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Spacer(Modifier.height(28.dp))

        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { d -> PinKey(d, enabled) { if (pin.length < Vault.PIN_LENGTH) pin += d } }
            }
            Spacer(Modifier.height(14.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).clickable(enabled = onCancel != null) { onCancel?.invoke() },
                contentAlignment = Alignment.Center,
            ) {
                if (onCancel != null) {
                    Text("Cancelar", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            PinKey("0", enabled) { if (pin.length < Vault.PIN_LENGTH) pin += "0" }
            Box(
                Modifier.size(72.dp).clip(CircleShape).clickable(enabled = enabled && pin.isNotEmpty()) { pin = pin.dropLast(1) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Borrar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PinKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}

// ----------------------------------------------------------------------
// Lista
// ----------------------------------------------------------------------
@Composable
private fun VaultList(onClose: () -> Unit, onPlay: (PlayerSource) -> Unit, onChangePin: () -> Unit) {
    val items by Vault.items.collectAsStateWithLifecycle()
    var topMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<VaultItem?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cerrar y bloquear") }
            Column(Modifier.weight(1f)) {
                Text("Carpeta privada", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (items.size == 1) "1 archivo" else "${items.size} archivos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { topMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Más opciones") }
                DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                    DropdownMenuItem(text = { Text("Cambiar código") }, onClick = { topMenu = false; onChangePin() })
                    DropdownMenuItem(text = { Text("Bloquear ahora") }, onClick = { topMenu = false; onClose() })
                }
            }
        }

        if (items.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Lock, contentDescription = null, tint = FluxOrange, modifier = Modifier.size(32.dp)) }
                Spacer(Modifier.height(16.dp))
                Text("Aquí no hay nada todavía", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Mueve una descarga con “Mover a la carpeta privada” o activa en Ajustes " +
                        "que las nuevas se guarden aquí.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    VaultRow(
                        item = item,
                        onPlay = { onPlay(PlayerSource(Uri.fromFile(Vault.fileOf(item)), item.title, isPrivate = true)) },
                        onExport = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { Vault.exportToPublic(item) }
                                Toast.makeText(
                                    ctx,
                                    if (ok) "Devuelto a tus descargas" else "No se pudo sacar el archivo",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onDelete = { confirmDelete = item },
                    )
                }
            }
        }

        Text(
            "Estos archivos solo existen dentro de Flux: no aparecen en la galería ni en otras apps. " +
                "La carpeta se bloquea sola al salir.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
        )
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("¿Eliminar para siempre?") },
            text = { Text("“${item.title}” se borrará de la carpeta privada y no se puede recuperar.") },
            confirmButton = {
                TextButton(onClick = { Vault.delete(item); confirmDelete = null }) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun VaultRow(item: VaultItem, onPlay: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.clickable(onClick = onPlay).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (item.thumb != null) {
                    AsyncImage(
                        model = item.thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        if (item.isAudio) Icons.Default.MusicNote else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    formatSize(item.size) + " · " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.addedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Opciones") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Sacar de la carpeta privada") }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(
                        text = { Text("Eliminar", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}
