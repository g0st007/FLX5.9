package app.flux.downloader.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Collections

/**
 * Para páginas genéricas (que ningún extractor de yt-dlp conoce): descarga el HTML y busca los medios
 * que la propia página declara (video/source, og:video, JSON-LD, URLs .mp4/.m3u8/.mpd en scripts),
 * incluidos los de un nivel de iframes. Luego los lee con [MediaProbe].
 */
object PageScanner {

    private val quoted = Regex(
        """["'(]([^"'\s<>()]+?\.(?:m3u8|mpd|mp4|m4v|mov|webm)(?:\?[^"'\s<>()]*)?)["')]""",
        RegexOption.IGNORE_CASE,
    )
    private val metaVideo = Regex(
        """<meta[^>]+(?:property|name)=["'](?:og:video(?::secure_url|:url)?|twitter:player:stream)["'][^>]*content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val metaVideoRev = Regex(
        """<meta[^>]+content=["']([^"']+)["'][^>]*(?:property|name)=["'](?:og:video(?::secure_url|:url)?|twitter:player:stream)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ogTitle = Regex("""<meta[^>]+property=["']og:title["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val titleTag = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
    private val ogImage = Regex("""<meta[^>]+property=["']og:image["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private fun clean(html: String): String =
        html.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").replace("&#x2F;", "/")

    private fun resolve(base: String, ref: String): String? = runCatching {
        val r = if (ref.startsWith("//")) "https:$ref" else ref
        URI(base).resolve(r).toString()
    }.getOrNull()

    private fun collect(html: String, base: String, out: MutableSet<String>) {
        val text = clean(html)
        quoted.findAll(text).forEach { m ->
            resolve(base, m.groupValues[1])?.let { if (MediaProbe.isCandidate(it)) out += it }
        }
        for (rx in listOf(metaVideo, metaVideoRev)) {
            rx.findAll(text).forEach { m -> resolve(base, m.groupValues[1])?.let { if (MediaProbe.isCandidate(it, trusted = true)) out += it } }
        }
    }

    private fun decode(s: String): String =
        s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").trim()

    suspend fun scan(pageUrl: String): MediaInfo? = withContext(Dispatchers.IO) {
        val html = MediaProbe.getText(
            pageUrl,
            mapOf("User-Agent" to MediaProbe.UA, "Accept" to "text/html,*/*;q=0.8", "Accept-Language" to "es,en;q=0.8"),
            3_000_000,
        ) ?: return@withContext null

        val found: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
        collect(html, pageUrl, found)

        if (found.isEmpty()) {
            val frames = iframe.findAll(clean(html))
                .mapNotNull { resolve(pageUrl, it.groupValues[1]) }
                .filter { it.startsWith("https://") }
                .distinct().take(3).toList()
            if (frames.isNotEmpty()) {
                coroutineScope {
                    frames.map { f ->
                        async {
                            MediaProbe.getText(f, mapOf("User-Agent" to MediaProbe.UA, "Referer" to pageUrl), 2_000_000)
                                ?.let { collect(it, f, found) }
                        }
                    }.awaitAll()
                }
            }
        }
        if (found.isEmpty()) return@withContext null

        val headers = mapOf("User-Agent" to MediaProbe.UA, "Referer" to pageUrl)
        val results = coroutineScope {
            found.toList().take(8).map { u -> async { MediaProbe.probe(u, headers) } }.awaitAll().filterNotNull()
        }
        if (results.isEmpty()) return@withContext null

        val hidden = results.flatMap { it.hidden }.map { MediaProbe.normalize(it) }.toSet()
        val usable = results.filter { MediaProbe.normalize(it.url) !in hidden && !it.live }
        // El mejor candidato: más calidades y, a igualdad, el de más peso
        val best = usable.maxWithOrNull(
            compareBy<ProbeResult>({ it.qualities.size }, { it.qualities.firstOrNull()?.bytes ?: 0L }),
        ) ?: return@withContext null

        val title = ogTitle.find(html)?.groupValues?.get(1)?.let(::decode)
            ?: titleTag.find(html)?.groupValues?.get(1)?.let(::decode)
            ?: runCatching { URI(pageUrl).host }.getOrNull()
            ?: "Video"
        MediaInfo(
            url = best.url,
            title = title,
            uploader = runCatching { URI(pageUrl).host?.removePrefix("www.") }.getOrNull(),
            thumbnail = ogImage.find(html)?.groupValues?.get(1)?.let { resolve(pageUrl, decode(it)) },
            durationSec = best.durationSec,
            qualities = best.qualities,
            headers = headers,
            kind = best.kind,
            mime = best.mime,
        )
    }
}
