package app.flux.downloader.engine

import android.app.Application
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import app.flux.downloader.settings.AppSettings
import org.json.JSONObject
import java.io.File

/** Una calidad de video disponible. [bytes] es el peso total estimado (video + audio) si se pudo calcular. */
data class Quality(
    val height: Int,
    val bytes: Long?,
    val estimated: Boolean,
    /** De dónde se baja esta calidad cuando cada una tiene su propia URL (null = la del medio). */
    val url: String? = null,
    /** Cómo se baja: DIRECT = descargador propio; HLS/DASH = yt-dlp; null con [url] = extractor del sitio. */
    val kind: MediaKind? = null,
    val mime: String? = null,
    /** Viene de los datos de la página (no de lo que el reproductor pidió): no se ofrece hasta medir su peso. */
    val declared: Boolean = false,
)

data class MediaInfo(
    val url: String,
    val title: String,
    val uploader: String?,
    val thumbnail: String?,
    val durationSec: Long,
    val qualities: List<Quality>,
    /** Cabeceras que el sitio exige para servir el medio (Referer, User-Agent, Cookie). Vacío para los extractores de yt-dlp. */
    val headers: Map<String, String> = emptyMap(),
    /** DIRECT = archivo suelto: se descarga con [NativeDownloader]. null = lo resuelve yt-dlp. */
    val kind: MediaKind? = null,
    val mime: String? = null,
) {
    val heights: List<Int> get() = qualities.map { it.height }
}

data class Choice(
    val audioOnly: Boolean,
    val height: Int?,
    val audioFormat: String = "mp3",
)

/** Envuelve yt-dlp + FFmpeg. Toda la lógica de descarga vive aquí. */
object Engine {

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun init(app: Application) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                _ready.value = true
                // Los sitios cambian a diario: actualizamos el extractor en segundo plano,
                // pero sin competir con el primer arranque de la interfaz.
                delay(15_000)
                runCatching { YoutubeDL.getInstance().updateYoutubeDL(app) }
            } catch (e: Exception) {
                _error.value = "No se pudo iniciar el motor: ${e.message}"
            }
        }
    }

    suspend fun awaitReady() {
        withTimeout(60_000) {
            while (!_ready.value) {
                _error.value?.let { error(it) }
                delay(150)
            }
        }
    }

    private val analysisScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Analiza un enlace. Lanza en paralelo el extractor de yt-dlp (sitios conocidos) y el lector nativo de
     * la página (sitios genéricos). Si el nativo encuentra medios y yt-dlp tarda más de 2,5 s, se usa el
     * nativo; si el nativo no encuentra nada, se espera a yt-dlp.
     */
    suspend fun analyze(url: String): MediaInfo {
        awaitReady()
        val target = withContext(Dispatchers.IO) { SiteResolver.resolve(url) }
        val procId = "an-${System.nanoTime()}"
        val yt = analysisScope.async { runCatching { ytAnalyze(target, procId) } }
        val nat = analysisScope.async { runCatching { PageScanner.scan(target) }.getOrNull() }
        try {
            val fromPage = nat.await()
            if (fromPage == null) return yt.await().getOrThrow()
            val y = withTimeoutOrNull(2_500) { yt.await() }
            return if (y != null && y.isSuccess) y.getOrThrow() else fromPage
        } finally {
            if (yt.isActive) {
                runCatching { YoutubeDL.getInstance().destroyProcessById(procId) }
                yt.cancel()
            }
            if (nat.isActive) nat.cancel()
        }
    }

    private fun ytAnalyze(target: String, procId: String): MediaInfo {
        val request = YoutubeDLRequest(target).apply {
            addOption("--no-playlist")
            addOption("--dump-single-json")
            addOption("--no-warnings")
            addOption("--socket-timeout", "10")
            addOption("--extractor-retries", "1")
        }
        val out = YoutubeDL.getInstance().execute(request, procId).out
        val json = JSONObject(out.substring(out.indexOf('{').coerceAtLeast(0)))
        return parseInfo(target, json)
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    /** kbit/s de un formato: tbr, o vbr + abr cuando el extractor no informa el total. */
    private fun bitrateOf(f: JSONObject): Double {
        fun d(k: String) = f.optDouble(k, 0.0).let { if (it.isNaN()) 0.0 else it }
        val tbr = d("tbr")
        return if (tbr > 0) tbr else d("vbr") + d("abr")
    }

    private fun parseInfo(url: String, json: JSONObject): MediaInfo {
        val duration = json.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it }
        val formats = json.optJSONArray("formats")

        class F(val height: Int, val bytes: Long?, val estimated: Boolean, val tbr: Double, val avc: Boolean, val hasAudio: Boolean)

        fun sizeOf(f: JSONObject): Pair<Long?, Boolean> {
            val exact = f.optLong("filesize", 0L)
            if (exact > 0) return exact to false
            val approx = f.optLong("filesize_approx", 0L)
            if (approx > 0) return approx to false
            val rate = bitrateOf(f)
            if (rate > 0 && duration > 0) return (rate * 1000 / 8 * duration).toLong() to true
            return null to false
        }

        val video = ArrayList<F>()
        var bestAudio: F? = null
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val vcodec = f.optString("vcodec", "none")
                val acodec = f.optString("acodec", "none")
                val hasV = vcodec.isNotEmpty() && vcodec != "none" && vcodec != "null"
                val hasA = acodec.isNotEmpty() && acodec != "none" && acodec != "null"
                val (bytes, est) = sizeOf(f)
                val tbr = bitrateOf(f)
                val h = f.optInt("height", 0)
                if (hasV && h > 0) {
                    video += F(h, bytes, est, tbr, vcodec.startsWith("avc") || vcodec.startsWith("h264"), hasA)
                } else if (!hasV && hasA) {
                    val cand = F(0, bytes, est, tbr, false, true)
                    val cur = bestAudio
                    if (cur == null || cand.tbr > cur.tbr) bestAudio = cand
                }
            }
        }

        val audioBest = bestAudio

        // Misma preferencia que usa la descarga (-S res,vcodec:h264): a igual resolución, h264 y mayor bitrate.
        val qualities = video.groupBy { it.height }
            .map { (h, list) ->
                val pick = list.sortedWith(compareByDescending<F> { it.avc }.thenByDescending { it.tbr }).first()
                val audio = if (pick.hasAudio) null else audioBest
                val videoBytes = pick.bytes
                val audioBytes = audio?.bytes
                // Si el audio es aparte y no informa su peso, se supone 128 kbps (así nunca queda "—")
                val audioGuess = if (duration > 0) (128.0 * 1000 / 8 * duration).toLong() else null
                val total: Long? = when {
                    videoBytes == null -> null
                    pick.hasAudio -> videoBytes
                    audioBytes != null -> videoBytes + audioBytes
                    audioGuess != null -> videoBytes + audioGuess
                    else -> null
                }
                Quality(h, total, pick.estimated || audio?.estimated == true || (!pick.hasAudio && audioBytes == null))
            }
            .sortedByDescending { it.height }

        return MediaInfo(
            url = url,
            title = json.str("title") ?: "Video",
            uploader = json.str("uploader") ?: json.str("channel") ?: json.str("uploader_id"),
            thumbnail = json.str("thumbnail"),
            durationSec = duration.toLong(),
            qualities = qualities,
        )
    }

    /**
     * Descarga a una carpeta temporal y devuelve el archivo final.
     * [onProgress] recibe (porcentaje 0..100, segundos restantes).
     */
    suspend fun download(
        ctx: Context,
        url: String,
        choice: Choice,
        id: String,
        headers: Map<String, String> = emptyMap(),
        fileTitle: String? = null,
        onProgress: (Float, Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        awaitReady()
        val dir = File(ctx.cacheDir, "dl/$id").apply { mkdirs() }

        val req = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            // Medios detectados por Flux: nombre propio (el de la página); extractores: el título de yt-dlp
            val name = if (headers.isNotEmpty() && !fileTitle.isNullOrBlank()) safeName(fileTitle) else null
            addOption("-o", "${dir.absolutePath}/" + (name?.replace("%", "%%") ?: "%(title).80B") + ".%(ext)s")
            headers.forEach { (k, v) ->
                if (k.equals("User-Agent", ignoreCase = true)) addOption("--user-agent", v) else addOption("--add-header", "$k:$v")
            }
            addOption("--concurrent-fragments", "4")
            addOption("--retries", "10")
            addOption("--embed-metadata")
            AppSettings.speedLimit.value.takeIf { it.isNotBlank() }?.let { addOption("--limit-rate", it) }

            if (choice.audioOnly) {
                addOption("-f", "bestaudio/best")
                addOption("-x")
                addOption("--audio-format", choice.audioFormat)
                addOption("--audio-quality", "0")
            } else {
                val h = choice.height
                val selector = if (h != null) "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b" else "bv*+ba/b"
                addOption("-f", selector)
                // Prioriza resolución y, a igualdad, códecs compatibles con cualquier reproductor
                addOption("-S", "res,vcodec:h264,acodec:m4a")
                addOption("--merge-output-format", "mp4")
            }
        }

        var total = 0L
        YoutubeDL.getInstance().execute(req, id) { progress, eta, line ->
            parseTotalBytes(line)?.let { total = it }
            onProgress(progress, eta, total)
        }

        dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: error("La descarga terminó pero no se generó ningún archivo")
    }

    private val totalRegex = Regex("""of\s+~?\s*([\d.]+)\s*([KMGT]?i?B)""")

    /** "[download]  45.3% of ~ 12.34MiB at …" -> bytes totales. */
    private fun parseTotalBytes(line: String?): Long? {
        val m = line?.let { totalRegex.find(it) } ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2]
        val base = if (unit.contains("i")) 1024.0 else 1000.0
        val pow = when (unit.firstOrNull()) { 'K' -> 1; 'M' -> 2; 'G' -> 3; 'T' -> 4; else -> 0 }
        return (n * Math.pow(base, pow.toDouble())).toLong()
    }

    internal fun safeName(t: String): String =
        t.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").trim().take(80).ifBlank { "video" }

    fun cancel(id: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
    }

    fun cleanup(ctx: Context, id: String) {
        File(ctx.cacheDir, "dl/$id").deleteRecursively()
    }
}
