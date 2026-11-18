package app.flux.downloader.engine

import android.content.Context
import app.flux.downloader.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Descarga de archivos directos (mp4, webm, mp3…) sin yt-dlp.
 *
 * yt-dlp se niega a guardar un archivo cuando la extensión de la URL le parece "inusual"
 * (típico de sitios genéricos: get_file.php, video.php?id=…). Aquí el nombre y la extensión salen del
 * tipo de contenido real que devuelve el servidor, se envían las cabeceras del sitio (Referer, Cookie…)
 * y se puede cancelar al instante.
 */
object NativeDownloader {

    private fun open(url: String, headers: Map<String, String>, fast: Boolean = false): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = if (fast) 6_000 else 15_000
            readTimeout = if (fast) 6_000 else 20_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Accept", "*/*")
            if (headers.keys.none { it.equals("User-Agent", true) }) setRequestProperty("User-Agent", MediaProbe.UA)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    /** Sigue redirecciones (también http→https) y no reenvía la cookie a otro dominio. */
    private fun openFollowing(url: String, headers: Map<String, String>, fast: Boolean = false): HttpURLConnection {
        var current = url
        val baseHost = runCatching { URI(url).host }.getOrNull()
        repeat(6) {
            val sameHost = runCatching { URI(current).host }.getOrNull() == baseHost
            val h = if (sameHost) headers else headers.filterKeys { !it.equals("Cookie", true) }
            val c = open(current, h, fast)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) throw IOException("HTTP Error $code")
                current = URI(current).resolve(loc).toString()
            } else {
                return c
            }
        }
        throw IOException("Demasiadas redirecciones")
    }

    private fun extFor(type: String?, url: String, hint: String?): String {
        val t = (type ?: "").substringBefore(';').trim().lowercase().ifBlank { hint.orEmpty().lowercase() }
        val byType = when (t) {
            "video/mp4", "video/x-m4v" -> "mp4"
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            "video/quicktime" -> "mov"
            "video/x-flv" -> "flv"
            "video/3gpp" -> "3gp"
            "video/mpeg" -> "mpg"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/aac" -> "aac"
            "audio/ogg", "audio/opus" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> null
        }
        if (byType != null) return byType
        val urlExt = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        if (urlExt in setOf("mp4", "webm", "mkv", "mov", "flv", "m4v", "mp3", "m4a", "aac", "ogg", "opus", "wav")) return urlExt
        return if (t.startsWith("audio/")) "m4a" else "mp4"
    }

    /** Límite de velocidad de los ajustes ("500K", "1M"…) en bytes por segundo; 0 = sin límite. */
    private fun limitBytesPerSec(): Long {
        val s = AppSettings.speedLimit.value
        if (s.isBlank()) return 0L
        val n = s.dropLast(1).toLongOrNull() ?: return 0L
        return when (s.last().uppercaseChar()) {
            'K' -> n * 1024
            'M' -> n * 1024 * 1024
            else -> 0L
        }
    }

    /** Devuelve el archivo descargado en caché. [onProgress] recibe (porcentaje 0..100, segundos restantes). */
    /** Combinaciones de cabeceras que se prueban si el servidor responde 400/401/403 (Referer del sitio, sin Referer…). */
    private fun headerProfiles(url: String, headers: Map<String, String>): List<Map<String, String>> {
        val origin = runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrNull()
        val out = ArrayList<Map<String, String>>()
        out += headers
        if (origin != null) {
            out += headers.filterKeys { !it.equals("Referer", true) } + ("Referer" to "$origin/")
            out += headers.filterKeys { !it.equals("Referer", true) && !it.equals("Origin", true) }
            out += headers + ("Origin" to origin)
        }
        return out.distinct()
    }

    private fun isAuthError(code: Int) = code == 400 || code == 401 || code == 403

    /** Abre la URL probando las combinaciones de cabeceras hasta que el servidor no responda con error de acceso. */
    private fun openWithProfiles(
        url: String,
        headers: Map<String, String>,
        extra: Map<String, String> = emptyMap(),
        fast: Boolean = false,
    ): HttpURLConnection {
        var last: IOException? = null
        for (h in headerProfiles(url, headers).let { if (fast) it.take(3) else it }) {
            val c = try {
                openFollowing(url, h + extra, fast)
            } catch (e: IOException) {
                last = e
                continue
            }
            val code = c.responseCode
            if (!isAuthError(code)) return c
            c.disconnect()
            last = IOException("HTTP Error $code")
        }
        throw last ?: IOException("HTTP Error 403")
    }

    /** Peso y tipo del archivo con la petición real de descarga (Range 0-0); null si no se puede bajar. */
    fun probeSize(url: String, headers: Map<String, String>): Pair<Long?, String?>? = runCatching {
        val c = openWithProfiles(url, headers, mapOf("Range" to "bytes=0-0"), fast = true)
        try {
            when (c.responseCode) {
                206 -> c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() to c.contentType
                in 200..299 -> c.contentLengthLong.takeIf { it > 0 } to c.contentType
                else -> null
            }
        } finally {
            runCatching { c.disconnect() }
        }
    }.getOrNull()

    suspend fun download(
        ctx: Context,
        url: String,
        headers: Map<String, String>,
        title: String,
        mimeHint: String?,
        id: String,
        onProgress: (Float, Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "dl/$id").apply { mkdirs() }
        val c = openWithProfiles(url, headers)
        // Cancelar la descarga corta la conexión de inmediato (la lectura bloqueada se interrumpe)
        coroutineContext[Job]?.invokeOnCompletion { runCatching { c.disconnect() } }
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP Error $code")
            val type = c.contentType
            if (type != null && (type.startsWith("text/", true) || type.contains("json", true))) {
                throw IOException(
                    "El sitio devolvió una página en lugar del video. Ábrelo en el navegador de Flux " +
                        "y vuelve a descargarlo (el enlace pudo caducar).",
                )
            }
            val ext = extFor(type, c.url.toString(), mimeHint)
            val file = File(dir, Engine.safeName(title) + "." + ext)
            val total = c.contentLengthLong
            val limit = limitBytesPerSec()
            val start = System.currentTimeMillis()
            var read = 0L
            var lastPush = 0L

            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (limit > 0) {
                            val expectedMs = read * 1000 / limit
                            val actualMs = now - start
                            if (expectedMs > actualMs) delay(expectedMs - actualMs)
                        }
                        if (total > 0 && now - lastPush >= 250) {
                            lastPush = now
                            val speed = read * 1000f / maxOf(1L, now - start)
                            onProgress(read * 100f / total, ((total - read) / maxOf(speed, 1f)).toLong(), total)
                        }
                    }
                }
            }
            if (read == 0L) throw IOException("El sitio no devolvió datos")
            if (total > 0 && read < total) throw IOException("La descarga se cortó antes de terminar")
            onProgress(100f, 0L, if (total > 0) total else read)
            file
        } finally {
            runCatching { c.disconnect() }
        }
    }
}
