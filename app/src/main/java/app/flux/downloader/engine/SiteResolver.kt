package app.flux.downloader.engine

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Convierte enlaces "problemáticos" en uno que yt-dlp pueda descargar sin iniciar sesión.
 *
 * Reddit: muchos posts con video (sobre todo los alojados en RedGifs) fallan con yt-dlp porque
 * el extractor de Reddit pide sesión. Aquí se lee el post (JSON público) y, si apunta a RedGifs,
 * se devuelve el enlace de RedGifs, que se descarga sin cuenta. Si no se puede resolver,
 * se devuelve el enlace original y todo sigue como antes.
 */
object SiteResolver {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    private val redgifsRegex =
        Regex("""https?://(?:www\.|v3\.)?redgifs\.com/(?:watch|ifr)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host }.getOrNull()?.lowercase()

    private fun isRedditPost(host: String) =
        host == "reddit.com" || host.endsWith(".reddit.com") || host == "redd.it"

    /** Devuelve el enlace a descargar (puede ser el mismo que se recibió). Bloqueante: llamar desde IO. */
    fun resolve(url: String): String {
        val host = hostOf(url) ?: return url
        if (!isRedditPost(host)) return url
        return runCatching { redditToRedgifs(url) }.getOrNull() ?: url
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
        }

    private fun redditToRedgifs(url: String): String? {
        // 1) Enlaces cortos (/s/..., redd.it/...) -> permalink real
        var permalink = url
        if (!url.contains("/comments/")) {
            val c = open(url)
            try {
                c.requestMethod = "GET"
                c.responseCode
                permalink = c.url.toString()
            } finally {
                c.disconnect()
            }
        }
        if (!permalink.contains("/comments/")) return null

        val path = URI(permalink).path.trimEnd('/')
        // 2) JSON público del post (dos hosts por si uno responde bloqueado)
        for (base in listOf("https://www.reddit.com", "https://old.reddit.com")) {
            val body = fetch("$base$path.json?raw_json=1") ?: continue
            val found = findRedgifs(body)
            if (found != null) return found
        }
        return null
    }

    private fun fetch(url: String): String? {
        val c = open(url)
        return try {
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    private fun findRedgifs(body: String): String? {
        val post = runCatching {
            JSONArray(body).getJSONObject(0).getJSONObject("data")
                .getJSONArray("children").getJSONObject(0).getJSONObject("data")
        }.getOrNull() ?: return null

        // El post y, si es un crosspost, el original
        val sources = ArrayList<JSONObject>()
        sources += post
        post.optJSONArray("crosspost_parent_list")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(sources::add)
        }
        for (src in sources) {
            // Buscamos solo en los campos donde Reddit guarda el destino/embed del video
            val haystack = buildString {
                for (k in listOf("url_overridden_by_dest", "url")) append(src.optString(k)).append(' ')
                for (k in listOf("media", "secure_media")) {
                    val m = src.optJSONObject(k) ?: continue
                    append(m.optJSONObject("oembed")?.optString("html").orEmpty()).append(' ')
                    append(m.optJSONObject("oembed")?.optString("url").orEmpty()).append(' ')
                }
                append(src.optJSONObject("secure_media_embed")?.optString("content").orEmpty())
            }.replace("&amp;", "&").replace("\\/", "/")
            val id = redgifsRegex.find(haystack)?.groupValues?.get(1)
            if (id != null) return "https://www.redgifs.com/watch/${id.lowercase()}"
        }
        return null
    }
}
