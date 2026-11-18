package app.flux.downloader.engine

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

enum class MediaKind { DIRECT, HLS, DASH }

/** Resultado de leer un medio por su cuenta, sin yt-dlp. [Quality.height] == 0 significa "Original". */
data class ProbeResult(
    val url: String,
    val kind: MediaKind,
    val durationSec: Long,
    val qualities: List<Quality>,
    val audioOnly: Boolean = false,
    /** Listas variante y de audio que pertenecen a este medio: no deben listarse aparte. */
    val hidden: Set<String> = emptySet(),
    val live: Boolean = false,
    val mime: String? = null,
)

/**
 * Lector nativo de medios: archivos directos (HEAD), listas HLS (.m3u8) y DASH (.mpd).
 * Saca calidades, duración y peso estimado en una o dos peticiones HTTP, sin lanzar yt-dlp.
 */
object MediaProbe {

    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    private val mediaExt = Regex(
        """\.(m3u8|mpd|mp4|m4v|mov|webm|mkv|flv|mp3|m4a|aac|ogg|opus|wav)(?=[?#]|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val audioExt = Regex("""\.(mp3|m4a|aac|ogg|opus|wav)(?=[?#]|$)""", RegexOption.IGNORE_CASE)

    /** Fragmentos y piezas sueltas de un streaming: nunca son "el video". */
    private val segment = Regex(
        """[/_.-](?:seg|segment|frag|fragment|chunk)[-_]?\d+|\.m4s(?:[?#]|$)|/init[-_.]?[^/?]*\.mp4|[?&](?:bytestart|byteend|range)=""",
        RegexOption.IGNORE_CASE,
    )
    private val resolutionInUrl = Regex("""(\d{3,4})x(\d{3,4})""")
    private val pInUrl = Regex("""[/_.-](\d{3,4})p(?=[/_.\-?]|$)""")

    /** ¿Esta URL parece un medio? [trusted] = lo reportó el reproductor de la página (aunque no tenga extensión). */
    fun isCandidate(url: String, trusted: Boolean = false): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (segment.containsMatchIn(url)) return false
        return trusted || mediaExt.containsMatchIn(url)
    }

    fun normalize(url: String): String = url.substringBefore('#')

    fun urlHeight(url: String): Int? {
        resolutionInUrl.find(url)?.groupValues?.get(2)?.toIntOrNull()?.let { return it }
        return pInUrl.find(url)?.groupValues?.get(1)?.toIntOrNull()
    }

    suspend fun probe(
        url: String,
        headers: Map<String, String>,
        hintHeight: Int = 0,
        hintDuration: Double = 0.0,
    ): ProbeResult? = withContext(Dispatchers.IO) {
        runCatching {
            val path = url.substringBefore('?').lowercase()
            when {
                path.endsWith(".m3u8") -> probeHls(url, headers, hintHeight)
                path.endsWith(".mpd") -> probeDash(url, headers)
                else -> probeDirect(url, headers, hintHeight, hintDuration)
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ Archivo directo

    private fun probeDirect(url: String, headers: Map<String, String>, hintHeight: Int, hintDuration: Double): ProbeResult? {
        val (len, type) = headInfo(url, headers)
        val t = type?.lowercase().orEmpty()
        if (t.contains("mpegurl")) return probeHls(url, headers, hintHeight)
        if (t.contains("dash+xml")) return probeDash(url, headers)
        val audio = t.startsWith("audio/") || audioExt.containsMatchIn(url)
        val looksMedia = t.isEmpty() || t.startsWith("video/") || t.startsWith("audio/") || t.contains("octet-stream")
        if (!looksMedia) return null
        // Píxeles de seguimiento, miniaturas y clips vacíos
        if (len != null && len < (if (audio) 40_000L else 150_000L)) return null
        val h = urlHeight(url) ?: hintHeight.takeIf { it > 0 } ?: 0
        val mime = type?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
        // Sin resolución en la URL ni en el reproductor: se lee la cabecera del MP4 (sin bajar el archivo)
        var height = h
        var duration = hintDuration
        val isMp4 = !audio && (t.contains("mp4") || t.contains("quicktime") ||
            Regex("""\.(mp4|m4v|mov)(?=[/?#]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url.substringBefore('?')))
        if (isMp4 && len != null && (height == 0 || duration <= 0.0)) {
            readMp4(url, headers, len)?.let { meta ->
                if (height == 0) height = meta.height
                if (duration <= 0.0) duration = meta.durationSec
            }
        }
        return ProbeResult(
            url, MediaKind.DIRECT, duration.toLong(),
            listOf(Quality(height, len, false, url = url, kind = MediaKind.DIRECT, mime = mime)),
            audioOnly = audio, mime = mime,
        )
    }

    // ------------------------------------------------------------------ HLS

    private fun attr(line: String, key: String): String? =
        Regex("""[,:]$key=("[^"]*"|[^,]*)""").find(line)?.groupValues?.get(1)?.trim()?.trim('"')

    private fun resolve(base: String, ref: String): String =
        runCatching { URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref)

    private class MediaPlaylist(val durationSec: Double, val segments: Int, val ended: Boolean, val firstSegment: String?)

    private fun parseMediaPlaylist(base: String, text: String): MediaPlaylist {
        var dur = 0.0
        var count = 0
        var first: String? = null
        val lines = text.lines().map { it.trim() }
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#EXTINF:")) {
                dur += l.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0
                count++
                if (first == null) {
                    var j = i + 1
                    while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                    if (j < lines.size) first = resolve(base, lines[j])
                }
            }
            i++
        }
        return MediaPlaylist(dur, count, text.contains("#EXT-X-ENDLIST"), first)
    }

    private fun probeHls(url: String, headers: Map<String, String>, hintHeight: Int, asMasterSearch: Boolean = false): ProbeResult? {
        val text = getText(url, headers) ?: return null
        if (!text.trimStart().startsWith("#EXTM3U")) return null
        val lines = text.lines().map { it.trim() }

        if (lines.none { it.startsWith("#EXT-X-STREAM-INF") }) {
            // Lista de medios (sin variantes): duración exacta; el peso se estima con el primer fragmento
            val pl = parseMediaPlaylist(url, text)
            if (pl.segments == 0) return null
            val segLen = pl.firstSegment?.let { headInfo(it, headers).first }
            val bytes = if (segLen != null && pl.ended) segLen * pl.segments else null
            val h = hintHeight.takeIf { it > 0 } ?: urlHeight(url) ?: 0
            // Es la lista de UNA calidad: se busca la lista maestra de al lado para conocer todas las demás
            if (!asMasterSearch) {
                discoverMaster(url, headers)?.let { return it }
            }
            return ProbeResult(
                url, MediaKind.HLS, pl.durationSec.toLong(),
                listOf(Quality(h, bytes, true, url = url, kind = MediaKind.HLS)), live = !pl.ended,
            )
        }

        class V(val height: Int, val bandwidth: Long, val uri: String)
        val variants = ArrayList<V>()
        val hidden = HashSet<String>()
        var separateAudio = false
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-MEDIA") && l.contains("TYPE=AUDIO")) {
                separateAudio = true
                attr(l, "URI")?.let { hidden += resolve(url, it) }
            }
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val bw = attr(l, "AVERAGE-BANDWIDTH")?.toLongOrNull() ?: attr(l, "BANDWIDTH")?.toLongOrNull() ?: 0L
                val h = attr(l, "RESOLUTION")?.substringAfter('x')?.toIntOrNull() ?: 0
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    val u = resolve(url, lines[j])
                    variants += V(h, bw, u)
                    hidden += u
                }
                i = j
            }
            i++
        }
        if (variants.isEmpty()) return null

        // Duración: de la lista de medios de cualquier variante (basta una)
        // Si la primera lista de medios no se puede leer se prueban las demás: sin duración no hay peso ni se
        // puede saber a qué video pertenece la calidad.
        var pl: MediaPlaylist? = null
        for (v in variants.sortedBy { it.bandwidth }.take(4)) {
            pl = getText(v.uri, headers)?.let { parseMediaPlaylist(v.uri, it) }?.takeIf { it.segments > 0 }
            if (pl != null) break
        }
        val dur = pl?.durationSec ?: 0.0
        val live = pl != null && !pl.ended

        val qualities = variants.groupBy { it.height }
            .map { (h, list) ->
                val best = list.maxByOrNull { it.bandwidth }!!
                val bytes = if (dur > 0 && best.bandwidth > 0 && !live) {
                    val audioExtra = if (separateAudio) 128_000L else 0L
                    ((best.bandwidth + audioExtra) / 8.0 * dur).toLong()
                } else null
                Quality(h, bytes, true, url = url, kind = MediaKind.HLS)
            }
            .sortedByDescending { it.height }

        return ProbeResult(
            url, MediaKind.HLS, dur.toLong(), qualities,
            audioOnly = variants.all { it.height == 0 } && (qualities.size == 1),
            hidden = hidden, live = live,
        )
    }

    /**
     * Para la lista de una sola calidad (index-f2-v1.m3u8…): prueba las listas maestras habituales en la misma
     * carpeta y en la de arriba. Solo se acepta si la maestra realmente incluye esta lista.
     */
    private fun discoverMaster(url: String, headers: Map<String, String>): ProbeResult? {
        val base = url.substringBefore('?')
        val query = if (url.contains('?')) url.substringAfter('?') else ""
        val dir = base.substringBeforeLast('/')
        val parent = dir.substringBeforeLast('/', "")
        val names = listOf("master.m3u8", "playlist.m3u8", "index.m3u8", "main.m3u8", "manifest.m3u8")
        val tried = LinkedHashSet<String>()
        for (d in listOf(dir, parent)) {
            if (!d.startsWith("http")) continue
            for (n in names) {
                val c = "$d/$n"
                if (c == base) continue
                tried += if (query.isNotEmpty()) "$c?$query" else c
            }
        }
        for (cand in tried.take(6)) {
            val txt = getText(cand, headers, 400_000) ?: continue
            if (!txt.contains("#EXT-X-STREAM-INF")) continue
            val res = probeHls(cand, headers, 0, asMasterSearch = true) ?: continue
            if (res.hidden.any { it.substringBefore('?') == base }) return res
        }
        return null
    }

    // ------------------------------------------------------------------ MP4: resolución y duración sin bajarlo

    class Mp4Meta(val width: Int, val height: Int, val durationSec: Double)

    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, i: Int): Long = (u32(b, i) shl 32) or u32(b, i + 4)

    private fun boxType(b: ByteArray, i: Int): String = String(b, i, 4, Charsets.ISO_8859_1)

    /** Bytes [start, end] de una URL. null si el servidor no soporta rangos o falla. */
    private fun range(url: String, headers: Map<String, String>, start: Long, end: Long): ByteArray? = runCatching {
        val c = open(url, headers + ("Range" to "bytes=$start-$end"), "GET")
        try {
            val code = c.responseCode
            if (code != 206 && !(code == 200 && start == 0L)) return@runCatching null
            val want = (end - start + 1).toInt()
            c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < want) {
                    val n = input.read(buf, 0, minOf(buf.size, want - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun readMp4(url: String, headers: Map<String, String>, size: Long): Mp4Meta? {
        var offset = 0L
        repeat(24) {
            val hdr = range(url, headers, offset, offset + 15) ?: return null
            if (hdr.size < 8) return null
            var boxSize = u32(hdr, 0)
            val type = boxType(hdr, 4)
            var headerLen = 8
            if (boxSize == 1L) {
                if (hdr.size < 16) return null
                boxSize = u64(hdr, 8)
                headerLen = 16
            }
            if (boxSize == 0L) boxSize = size - offset
            if (boxSize < headerLen) return null
            if (type == "moov") {
                if (boxSize > 8_000_000L) return null
                val body = range(url, headers, offset + headerLen, offset + boxSize - 1) ?: return null
                return parseMoov(body)
            }
            offset += boxSize
            if (offset >= size) return null
        }
        return null
    }

    private fun parseMoov(b: ByteArray): Mp4Meta? {
        var duration = 0.0
        var w = 0
        var h = 0
        var i = 0
        while (i + 8 <= b.size) {
            val sz = u32(b, i).toInt()
            if (sz < 8 || i + sz > b.size) break
            when (boxType(b, i + 4)) {
                "mvhd" -> {
                    val v1 = b[i + 8].toInt() == 1
                    val ts = if (v1) u32(b, i + 28) else u32(b, i + 20)
                    val d = if (v1) u64(b, i + 32) else u32(b, i + 24)
                    if (ts > 0) duration = d.toDouble() / ts
                }
                "trak" -> if (w == 0 || h == 0) {
                    var j = i + 8
                    val end = i + sz
                    while (j + 8 <= end) {
                        val csz = u32(b, j).toInt()
                        if (csz < 8 || j + csz > end) break
                        if (boxType(b, j + 4) == "tkhd") {
                            val v1 = b[j + 8].toInt() == 1
                            val wOff = j + 8 + if (v1) 88 else 76
                            if (wOff + 8 <= b.size) {
                                val tw = (u32(b, wOff) shr 16).toInt()
                                val th = (u32(b, wOff + 4) shr 16).toInt()
                                if (tw > 0 && th > 0) { w = tw; h = th }
                            }
                        }
                        j += csz
                    }
                }
            }
            i += sz
        }
        return if (h > 0 || duration > 0) Mp4Meta(w, h, duration) else null
    }

    // ------------------------------------------------------------------ DASH

    private val isoDuration = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?""")

    private fun parseIsoDuration(s: String?): Double {
        val m = s?.let { isoDuration.find(it) } ?: return 0.0
        val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val min = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0
        return h * 3600 + min * 60 + sec
    }

    private fun probeDash(url: String, headers: Map<String, String>): ProbeResult? {
        val text = getText(url, headers) ?: return null
        if (!text.contains("<MPD") && !text.contains(":MPD")) return null

        class R(val height: Int, val bandwidth: Long, val mime: String)
        val reps = ArrayList<R>()
        var duration = 0.0
        var dynamic = false
        var setMime = ""
        var setType = ""

        val p = Xml.newPullParser()
        p.setInput(StringReader(text))
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name.substringAfter(':')) {
                    "MPD" -> {
                        duration = parseIsoDuration(p.getAttributeValue(null, "mediaPresentationDuration"))
                        dynamic = p.getAttributeValue(null, "type") == "dynamic"
                    }
                    "Period" -> if (duration == 0.0) duration = parseIsoDuration(p.getAttributeValue(null, "duration"))
                    "AdaptationSet" -> {
                        setMime = p.getAttributeValue(null, "mimeType").orEmpty()
                        setType = p.getAttributeValue(null, "contentType").orEmpty()
                    }
                    "Representation" -> {
                        val mime = p.getAttributeValue(null, "mimeType") ?: setMime.ifEmpty { setType }
                        reps += R(
                            p.getAttributeValue(null, "height")?.toIntOrNull() ?: 0,
                            p.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L,
                            mime,
                        )
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name.substringAfter(':') == "AdaptationSet") {
                setMime = ""
                setType = ""
            }
            ev = p.next()
        }

        val video = reps.filter { it.mime.startsWith("video") || it.height > 0 }
        val audioBw = reps.filter { it.mime.startsWith("audio") }.maxOfOrNull { it.bandwidth } ?: 0L
        if (video.isEmpty() && audioBw == 0L) return null

        if (video.isEmpty()) {
            val bytes = if (duration > 0 && !dynamic) (audioBw / 8.0 * duration).toLong() else null
            return ProbeResult(url, MediaKind.DASH, duration.toLong(), listOf(Quality(0, bytes, true)), audioOnly = true, live = dynamic)
        }
        val qualities = video.groupBy { it.height }
            .map { (h, list) ->
                val best = list.maxByOrNull { it.bandwidth }!!
                val bytes = if (duration > 0 && best.bandwidth > 0 && !dynamic) {
                    ((best.bandwidth + audioBw) / 8.0 * duration).toLong()
                } else null
                Quality(h, bytes, true, url = url, kind = MediaKind.DASH)
            }
            .sortedByDescending { it.height }
        return ProbeResult(url, MediaKind.DASH, duration.toLong(), qualities, live = dynamic)
    }

    // ------------------------------------------------------------------ HTTP

    private fun open(url: String, headers: Map<String, String>, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 6_000
            instanceFollowRedirects = true
            requestMethod = method
            setRequestProperty("Accept", "*/*")
            if (headers.keys.none { it.equals("User-Agent", true) }) setRequestProperty("User-Agent", UA)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    /** Texto de una URL (máx. [maxBytes]); null si falla. */
    fun getText(url: String, headers: Map<String, String>, maxBytes: Int = 2_000_000): String? = runCatching {
        val c = open(url, headers, "GET")
        try {
            if (c.responseCode !in 200..299) null
            else c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < maxBytes) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toString("UTF-8")
            }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    /** (peso en bytes, tipo de contenido). HEAD primero; si el servidor no lo permite, un GET de 1 byte. */
    /**
     * Peso y tipo del archivo, medidos con la misma petición que luego hace la descarga (GET con las mismas
     * redirecciones y cabeceras): si esto responde, bajarlo también funciona.
     */
    fun headInfo(url: String, headers: Map<String, String>): Pair<Long?, String?> =
        NativeDownloader.probeSize(url, headers) ?: (null to null)
}
