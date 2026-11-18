package app.flux.downloader.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.util.Rational
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.flux.downloader.engine.MediaInfo
import app.flux.downloader.engine.MediaKind
import app.flux.downloader.engine.MediaProbe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Qué reproducir. Para streams del navegador trae las cabeceras del sitio (Referer, Cookie…), el tipo
 * (HLS/DASH), el punto donde iba el video y, si se puede descargar, la info para hacerlo desde el reproductor.
 */
data class PlayerSource(
    val uri: Uri,
    val title: String,
    val isPrivate: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val kind: MediaKind? = null,
    val startMs: Long = 0L,
    val download: MediaInfo? = null,
)

internal fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private class TrackOpt(val label: String, val group: Tracks.Group, val index: Int)

private fun videoOptions(t: Tracks): List<TrackOpt> {
    val out = ArrayList<TrackOpt>()
    for (g in t.groups) {
        if (g.type != C.TRACK_TYPE_VIDEO) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val h = g.getTrackFormat(i).height
            if (h > 0) out += TrackOpt("${h}p", g, i)
        }
    }
    return out.distinctBy { it.label }.sortedByDescending { it.label.removeSuffix("p").toIntOrNull() ?: 0 }
}

private fun namedOptions(t: Tracks, type: Int, fallback: String): List<TrackOpt> {
    val out = ArrayList<TrackOpt>()
    for (g in t.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { Locale(it).displayLanguage }
            out += TrackOpt(f.label ?: lang ?: "$fallback ${out.size + 1}", g, i)
        }
    }
    return out
}

private val speedOptions = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)

private fun speedLabel(s: Float) = if (s == 1f) "Normal" else "${"%.2f".format(Locale.US, s).trimEnd('0').trimEnd('.')}x"

private fun clock(ms: Long): String = formatDuration(ms.coerceAtLeast(0) / 1000)

/**
 * Reproductor propio de Flux (Media3/ExoPlayer) a pantalla completa:
 * - gestos: doble toque ±10 s, deslizar horizontal para adelantar, vertical izquierda = brillo, derecha = volumen,
 *   mantener pulsado = 2x;
 * - velocidad (0,25x–3x), calidad (HLS/DASH), audio, subtítulos, relación de aspecto, bucle, bloqueo de controles,
 *   rotación, imagen en imagen y descarga del video que se está viendo;
 * - recuerda por dónde ibas y pausa al salir de la app.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(source: PlayerSource, onDownload: ((MediaInfo, Int?) -> Unit)?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("player", Context.MODE_PRIVATE) }
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val config = LocalConfiguration.current
    val inPip = remember(config) { activity?.isInPictureInPictureMode == true }
    val posKey = remember(source.uri) { "pos:" + source.uri.toString().hashCode() }

    var isPlaying by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var resizeIdx by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    var loop by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var hud by remember { mutableStateOf<String?>(null) }
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }
    var orient by remember { mutableIntStateOf(0) } // 0 auto · 1 horizontal · 2 vertical
    var error by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var boosted by remember { mutableStateOf(false) }
    var qualitySel by remember { mutableStateOf("Automática") }
    var audioSel by remember { mutableStateOf<String?>(null) }
    var textSel by remember { mutableStateOf("Desactivados") }
    // Funciones del reproductor que se pueden activar o quitar desde sus ajustes (todas activas por defecto)
    val features = remember {
        mutableStateMapOf<String, Boolean>().apply { playerFeatures.forEach { put(it.key, prefs.getBoolean("f_${it.key}", true)) } }
    }
    fun on(key: String) = features[key] != false
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    val player = remember(source.uri) {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setUserAgent(source.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: MediaProbe.UA)
            .setDefaultRequestProperties(source.headers.filterKeys { !it.equals("User-Agent", true) })
        val dataSource = DefaultDataSource.Factory(ctx, http)
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                val item = MediaItem.Builder().setUri(source.uri)
                when (source.kind) {
                    MediaKind.HLS -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
                    MediaKind.DASH -> item.setMimeType(MimeTypes.APPLICATION_MPD)
                    else -> Unit
                }
                setMediaItem(item.build())
                prepare()
                val start = if (source.startMs > 0) source.startMs else prefs.getLong(posKey, 0L)
                if (start > 0) seekTo(start)
                playWhenReady = true
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) controls = true
            }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
            override fun onPlayerError(e: PlaybackException) {
                error = e.message?.takeIf { it.isNotBlank() } ?: "No se pudo reproducir este video"
            }
        }
        player.addListener(listener)
        onDispose {
            // Recordar por dónde iba (solo videos largos y sin llegar al final)
            val d = player.duration
            val p = player.currentPosition
            if (d > 60_000 && p in 5_000 until d - 5_000) prefs.edit().putLong(posKey, p).apply()
            else prefs.edit().remove(posKey).apply()
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player) {
        while (true) {
            if (!seeking) positionMs = player.currentPosition
            durationMs = player.duration.takeIf { it > 0 } ?: 0L
            delay(250)
        }
    }
    LaunchedEffect(loop) { player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }
    LaunchedEffect(hud) { if (hud != null) { delay(900); hud = null } }
    LaunchedEffect(controls, isPlaying, dialog, seeking, locked) {
        if (controls && isPlaying && dialog == null && !seeking && !locked) { delay(3_500); controls = false }
    }

    // Brillo y volumen (gestos)
    val originalBrightness = remember { activity?.window?.attributes?.screenBrightness ?: -1f }
    var brightness by remember { mutableFloatStateOf(if (originalBrightness >= 0) originalBrightness else 0.5f) }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVol.toFloat()) }
    fun applyBrightness(v: Float) {
        brightness = v.coerceIn(0.02f, 1f)
        activity?.window?.let { w -> val a = w.attributes; a.screenBrightness = brightness; w.attributes = a }
    }
    fun applyVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * maxVol).roundToInt(), 0)
    }

    // Inmersivo: sin barras del sistema; al salir se restaura todo
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val prevStatus = controller?.isAppearanceLightStatusBars
        val prevNav = controller?.isAppearanceLightNavigationBars
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (controller != null && prevStatus != null) controller.isAppearanceLightStatusBars = prevStatus
            if (controller != null && prevNav != null) controller.isAppearanceLightNavigationBars = prevNav
            window?.let { w -> val a = w.attributes; a.screenBrightness = originalBrightness; w.attributes = a }
        }
    }

    // Orientación: automática según el video (horizontal si es ancho, vertical si es alto) o fijada por el usuario
    LaunchedEffect(orient, videoSize) {
        activity?.requestedOrientation = when {
            orient == 1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            orient == 2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            videoSize.width > videoSize.height -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            videoSize.height > videoSize.width -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler { if (!locked) onClose() }

    fun seekBy(deltaMs: Long) {
        val end = if (player.duration > 0) player.duration else Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, end))
    }

    val resizeModes = listOf(
        "Ajustar" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
        "Llenar pantalla" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
        "Zoom (recortar)" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )
    val qualities = remember(tracks) { videoOptions(tracks) }
    val audios = remember(tracks) { namedOptions(tracks, C.TRACK_TYPE_AUDIO, "Audio") }
    val texts = remember(tracks) { namedOptions(tracks, C.TRACK_TYPE_TEXT, "Subtítulos") }

    fun applyTrack(opt: TrackOpt?, type: Int) {
        var b = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
        if (type == C.TRACK_TYPE_TEXT) b = b.setTrackTypeDisabled(type, opt == null)
        if (opt != null) b = b.setOverrideForType(TrackSelectionOverride(opt.group.mediaTrackGroup, opt.index))
        player.trackSelectionParameters = b.build()
    }

    val dtapOn = on("doubletap")
    val boostOn = on("boost")
    val fBrightness = on("brightness")
    val fVolume = on("volume")
    val fSeek = on("seekdrag")
    val gestures = if (locked) Modifier else Modifier
        .pointerInput(dtapOn, boostOn) {
            detectTapGestures(
                onTap = { controls = !controls },
                onDoubleTap = if (!dtapOn) null else ({ o: androidx.compose.ui.geometry.Offset ->
                    val left = o.x < size.width / 2f
                    seekBy(if (left) -10_000L else 10_000L)
                    hud = if (left) "−10 s" else "+10 s"
                }),
                onPress = {
                    if (boostOn) {
                        val job = scope.launch {
                            delay(450)
                            boosted = true
                            player.setPlaybackSpeed(2f)
                        }
                        tryAwaitRelease()
                        job.cancel()
                        if (boosted) {
                            boosted = false
                            player.setPlaybackSpeed(speed)
                        }
                    }
                },
            )
        }
        .pointerInput(fBrightness, fVolume, fSeek) {
            var mode = 0 // 1 brillo · 2 volumen · 3 adelantar/atrasar
            var startX = 0f
            var accum = 0f
            var seekStart = 0L
            var seekTarget = 0L
            detectDragGestures(
                onDragStart = { o ->
                    startX = o.x
                    mode = 0
                    accum = 0f
                    seekStart = player.currentPosition
                },
                onDragEnd = {
                    if (mode == 3) player.seekTo(seekTarget)
                    mode = 0
                },
                onDragCancel = { mode = 0 },
                onDrag = { change, drag ->
                    change.consume()
                    if (zoom > 1.01f) {
                        // Con zoom, un dedo mueve la imagen
                        val mx = size.width * (zoom - 1f) / 2f
                        val my = size.height * (zoom - 1f) / 2f
                        panX = (panX + drag.x).coerceIn(-mx, mx)
                        panY = (panY + drag.y).coerceIn(-my, my)
                        return@detectDragGestures
                    }
                    if (mode == 0) {
                        mode = if (abs(drag.x) > abs(drag.y)) 3 else if (startX < size.width / 2f) 1 else 2
                        if ((mode == 1 && !fBrightness) || (mode == 2 && !fVolume) || (mode == 3 && !fSeek)) mode = -1
                    }
                    when (mode) {
                        1 -> {
                            applyBrightness(brightness - drag.y / size.height * 1.4f)
                            hud = "Brillo ${(brightness * 100).roundToInt()}%"
                        }
                        2 -> {
                            applyVolume(volume - drag.y / size.height * 1.4f)
                            hud = "Volumen ${(volume * 100).roundToInt()}%"
                        }
                        3 -> {
                            accum += drag.x
                            val end = if (player.duration > 0) player.duration else Long.MAX_VALUE
                            seekTarget = (seekStart + accum / size.width * 90_000f).toLong().coerceIn(0L, end)
                            hud = clock(seekTarget)
                        }
                    }
                },
            )
        }

    val zoomOn = on("zoom")
    val zoomGesture = if (!zoomOn) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                if (event.changes.size >= 2) {
                    zoom = (zoom * event.calculateZoom()).coerceIn(1f, 6f)
                    val pan = event.calculatePan()
                    val mx = size.width * (zoom - 1f) / 2f
                    val my = size.height * (zoom - 1f) / 2f
                    panX = (panX + pan.x).coerceIn(-mx, mx)
                    panY = (panY + pan.y).coerceIn(-my, my)
                    if (zoom <= 1.01f) { panX = 0f; panY = 0f }
                    hud = "Zoom " + String.format(Locale.US, "%.1f", zoom) + "x"
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).then(gestures).then(zoomGesture)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = panX, translationY = panY),
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    useController = false
                    keepScreenOn = true
                }
            },
            update = { it.resizeMode = resizeModes[resizeIdx].second },
            onRelease = { it.player = null },
        )

        if (!inPip) {
            // Aviso de 2x mientras se mantiene pulsado
            if (boosted) Pill("▶▶  2x", Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
            hud?.let { Pill(it, Modifier.align(Alignment.Center)) }

            if (error != null) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xDD000000)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("No se pudo reproducir", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(error.orEmpty(), color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onClose) { Text("Cerrar", color = Color.White) }
                        Button(onClick = { error = null; player.prepare(); player.play() }) { Text("Reintentar") }
                    }
                }
            }

            // Controles bloqueados: solo un candado para desbloquear
            if (locked) {
                IconButton(
                    onClick = { locked = false; controls = true },
                    modifier = Modifier.align(Alignment.CenterStart).displayCutoutPadding().padding(8.dp).alpha(0.7f),
                ) { Icon(Icons.Default.Lock, contentDescription = "Desbloquear controles", tint = Color.White) }
            }

            AnimatedVisibility(visible = controls && !locked && error == null, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color(0x66000000)).displayCutoutPadding()) {
                    // Barra superior
                    Row(
                        Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White) }
                        Text(
                            source.title,
                            modifier = Modifier.weight(1f),
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (onDownload != null && source.download != null) {
                            IconButton(onClick = { dialog = "download" }) { Icon(Icons.Default.Download, contentDescription = "Descargar", tint = Color.White) }
                        }
                        if (on("pip")) {
                            IconButton(onClick = {
                                runCatching {
                                    activity?.enterPictureInPictureMode(
                                        PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build(),
                                    )
                                }
                            }) { Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Imagen en imagen", tint = Color.White) }
                        }
                        IconButton(onClick = { dialog = "settings" }) {
                            Icon(Icons.Default.Settings, contentDescription = "Ajustes del reproductor", tint = Color.White)
                        }
                    }

                    // Centro
                    Row(
                        Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { seekBy(-10_000L) }, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.Default.Replay10, contentDescription = "Atrasar 10 s", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                        Box(
                            Modifier.size(68.dp).clip(CircleShape).background(Color(0x55FFFFFF)).clickable { if (isPlaying) player.pause() else player.play() },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (buffering) {
                                CircularProgressIndicator(color = FluxOrange, modifier = Modifier.size(34.dp), strokeWidth = 3.dp)
                            } else {
                                Icon(
                                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pausa" else "Reproducir",
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp),
                                )
                            }
                        }
                        IconButton(onClick = { seekBy(10_000L) }, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.Default.Forward10, contentDescription = "Adelantar 10 s", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }

                    // Barra inferior
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(clock(if (seeking) seekValue.toLong() else positionMs), color = Color.White, fontSize = 12.sp)
                            Slider(
                                value = (if (seeking) seekValue else positionMs.toFloat()).coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
                                onValueChange = { seeking = true; seekValue = it },
                                onValueChangeFinished = { player.seekTo(seekValue.toLong()); seeking = false },
                                valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                                enabled = durationMs > 0,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = FluxOrange,
                                    activeTrackColor = FluxOrange,
                                    inactiveTrackColor = Color(0x55FFFFFF),
                                ),
                            )
                            Text(clock(durationMs), color = Color.White, fontSize = 12.sp)
                        }
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (zoomOn) {
                                ActionChip(
                                    Icons.Default.ZoomIn,
                                    if (zoom > 1.01f) String.format(Locale.US, "%.1f", zoom) + "x" else "Zoom",
                                    zoom > 1.01f,
                                ) {
                                    zoom = when { zoom < 1.4f -> 1.5f; zoom < 1.9f -> 2f; zoom < 2.9f -> 3f; else -> 1f }
                                    panX = 0f
                                    panY = 0f
                                }
                            }
                            if (on("speed")) ActionChip(Icons.Default.Speed, speedLabel(speed), speed != 1f) { dialog = "speed" }
                            if (on("aspect")) ActionChip(Icons.Default.AspectRatio, resizeModes[resizeIdx].first, resizeIdx != 0) { dialog = "aspect" }
                            if (qualities.size > 1) ActionChip(Icons.Default.HighQuality, qualitySel, qualitySel != "Automática") { dialog = "quality" }
                            if (audios.size > 1) ActionChip(Icons.Default.Audiotrack, "Audio", false) { dialog = "audio" }
                            if (texts.isNotEmpty()) ActionChip(Icons.Default.Subtitles, "Subtítulos", textSel != "Desactivados") { dialog = "text" }
                            if (on("loop")) ActionChip(if (loop) Icons.Default.RepeatOne else Icons.Default.Repeat, "Bucle", loop) { loop = !loop }
                            if (on("orient")) {
                                ActionChip(
                                    Icons.Default.ScreenRotation,
                                    when (orient) { 1 -> "Horizontal"; 2 -> "Vertical"; else -> "Auto" },
                                    orient != 0,
                                ) { orient = (orient + 1) % 3 }
                            }
                            if (on("lock")) ActionChip(Icons.Default.LockOpen, "Bloquear", false) { locked = true; controls = false }
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        "speed" -> PlayerChoiceDialog("Velocidad", speedOptions.map { speedLabel(it) to it }, speed, { speed = it }) { dialog = null }
        "aspect" -> PlayerChoiceDialog(
            "Relación de aspecto",
            resizeModes.mapIndexed { i, m -> m.first to i },
            resizeIdx,
            { resizeIdx = it },
        ) { dialog = null }
        "quality" -> PlayerChoiceDialog(
            "Calidad",
            listOf("Automática" to (null as TrackOpt?)) + qualities.map { it.label to it },
            qualities.firstOrNull { it.label == qualitySel },
            { opt -> applyTrack(opt, C.TRACK_TYPE_VIDEO); qualitySel = opt?.label ?: "Automática" },
        ) { dialog = null }
        "audio" -> PlayerChoiceDialog(
            "Pista de audio",
            audios.map { it.label to it },
            audios.firstOrNull { it.label == audioSel },
            { opt -> applyTrack(opt, C.TRACK_TYPE_AUDIO); audioSel = opt.label },
        ) { dialog = null }
        "text" -> PlayerChoiceDialog(
            "Subtítulos",
            listOf("Desactivados" to (null as TrackOpt?)) + texts.map { it.label to it },
            texts.firstOrNull { it.label == textSel },
            { opt -> applyTrack(opt, C.TRACK_TYPE_TEXT); textSel = opt?.label ?: "Desactivados" },
        ) { dialog = null }
        "settings" -> PlayerSettingsDialog(
            features = features,
            onToggle = { key, value ->
                features[key] = value
                prefs.edit().putBoolean("f_$key", value).apply()
                if (key == "zoom" && !value) { zoom = 1f; panX = 0f; panY = 0f }
            },
            onReset = {
                playerFeatures.forEach { features[it.key] = true }
                prefs.edit().apply { playerFeatures.forEach { putBoolean("f_${it.key}", true) } }.apply()
            },
        ) { dialog = null }
        "download" -> {
            val info = source.download
            if (info != null && onDownload != null) {
                val opts: List<Pair<String, Int?>> =
                    if (info.qualities.isEmpty()) listOf("Mejor calidad" to null)
                    else info.qualities.map { q ->
                        listOfNotNull(
                            if (q.height == 0) "Original" else "${q.height}p",
                            q.bytes?.let { (if (q.estimated) "≈ " else "") + formatSize(it) },
                        ).joinToString(" · ") to q.height.takeIf { it > 0 }
                    }
                PlayerChoiceDialog("Descargar este video", opts, null as Int?, { h ->
                    onDownload(info, h)
                    Toast.makeText(ctx, "Descarga iniciada", Toast.LENGTH_SHORT).show()
                }) { dialog = null }
            }
        }
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier) {
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xCC000000)).padding(horizontal = 16.dp, vertical = 8.dp),
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) Color(0x33FF6A00) else Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) FluxOrange else Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (active) FluxOrange else Color.White, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun <T> PlayerChoiceDialog(
    title: String,
    options: List<Pair<String, T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
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

/** Función del reproductor que el usuario puede activar o quitar (nunca se elimina: se vuelve a activar aquí). */
private class PlayerFeature(val key: String, val label: String, val hint: String)

private val playerFeatures = listOf(
    PlayerFeature("zoom", "Zoom", "Pellizcar con dos dedos y botón de zoom"),
    PlayerFeature("brightness", "Brillo", "Deslizar arriba/abajo en la mitad izquierda"),
    PlayerFeature("volume", "Volumen", "Deslizar arriba/abajo en la mitad derecha"),
    PlayerFeature("seekdrag", "Adelantar y atrasar deslizando", "Deslizar hacia los lados"),
    PlayerFeature("doubletap", "Doble toque para ±10 s", "Toque doble a la izquierda o derecha"),
    PlayerFeature("boost", "Mantener pulsado para 2x", "Velocidad doble mientras se mantiene el dedo"),
    PlayerFeature("speed", "Botón de velocidad", ""),
    PlayerFeature("aspect", "Botón de relación de aspecto", ""),
    PlayerFeature("loop", "Botón de bucle", ""),
    PlayerFeature("orient", "Botón de orientación", ""),
    PlayerFeature("lock", "Botón de bloqueo de controles", ""),
    PlayerFeature("pip", "Imagen en imagen", ""),
)

@Composable
private fun PlayerSettingsDialog(
    features: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustes del reproductor") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Activa solo lo que te gusta. Lo que desactives no se elimina: puedes volver a activarlo aquí cuando quieras.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                playerFeatures.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(f.label, style = MaterialTheme.typography.bodyLarge)
                            if (f.hint.isNotEmpty()) {
                                Text(f.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(
                            checked = features[f.key] != false,
                            onCheckedChange = { onToggle(f.key, it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = FluxOrange),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
        dismissButton = { TextButton(onClick = onReset) { Text("Activar todo") } },
    )
}
