package app.flux.downloader.browser

import app.flux.downloader.engine.MediaKind
import app.flux.downloader.engine.MediaProbe
import app.flux.downloader.engine.Quality

/** Un video/audio que el navegador detectó y ya leyó (calidades y peso incluidos). */
data class DetectedMedia(
    val id: String,
    val url: String,
    val kind: MediaKind,
    val durationSec: Long,
    val qualities: List<Quality>,
    val audioOnly: Boolean,
    /** Cabeceras con las que se leyó y con las que hay que descargar (Referer, User-Agent, Cookie). */
    val headers: Map<String, String>,
    /** Tipo de contenido real del archivo directo (video/mp4…): define la extensión al guardarlo. */
    val mime: String? = null,
    /** Lo que la página sabe del video: título, portada y si se está reproduciendo ahora. */
    val title: String = "",
    val thumb: String? = null,
    val playing: Boolean = false,
    /** Resolución real con la que se reproduce ahora (la del <video> de la página); 0 = no se sabe. */
    val playingHeight: Int = 0,
    /** Medios hallados en la misma respuesta/lista de la página (p. ej. todas las calidades de un video). */
    val group: String? = null,
)

private fun identity(m: DetectedMedia): String = runCatching {
    val u = java.net.URI(m.url)
    (u.host.orEmpty() + u.path.orEmpty()).trimEnd('/')
}.getOrDefault(m.url)

/** La misma ruta sin los números de calidad (…_1080p.mp4 y …_720p.mp4 son el mismo video). */
private val qualityToken = Regex("""(?i)(?<![a-z0-9])\d{3,4}p?(?![a-z0-9])""")

private fun identityNoQuality(m: DetectedMedia): String = qualityToken.replace(identity(m), "#")

private fun rank(m: DetectedMedia): Long {
    val q = m.qualities.firstOrNull()
    return (q?.height ?: 0).toLong() * 1_000_000_000_000L + (q?.bytes ?: 0L)
}

private fun sameVideo(a: DetectedMedia, b: DetectedMedia): Boolean {
    if (identity(a) == identity(b)) return true
    // Duraciones conocidas que no coinciden: no es el mismo video (un anuncio comparte reproductor y portada con él)
    if (a.durationSec > 0 && b.durationSec > 0 && kotlin.math.abs(a.durationSec - b.durationSec) > 2) return false
    if (a.group != null && a.group == b.group) return true
    val na = identityNoQuality(a)
    if (na.contains('#') && na == identityNoQuality(b)) return true
    val ta = a.thumb
    if (ta != null && ta == b.thumb && (a.durationSec > 0) == (b.durationSec > 0)) return true
    val sa = a.qualities.firstOrNull()?.bytes
    val sb = b.qualities.firstOrNull()?.bytes
    // El mismo archivo servido con otra firma/token: mismo peso exacto
    if (a.kind == MediaKind.DIRECT && b.kind == MediaKind.DIRECT && sa != null && sa == sb && sa > 1_000_000) return true
    val sameLength = a.durationSec > 0 && kotlin.math.abs(a.durationSec - b.durationSec) <= 2
    // Mismo título y misma duración
    if (a.title.isNotBlank() && a.title == b.title && sameLength) return true
    // Listas de streaming (una por calidad) de la misma duración: son calidades del mismo video
    if (sameLength && a.durationSec > 30) return true
    return false
}

/** Cada calidad lleva su propia fuente: se completa con la del medio si no la trae. */
private fun withSource(m: DetectedMedia): List<Quality> =
    m.qualities.map { it.copy(url = it.url ?: m.url, kind = it.kind ?: m.kind, mime = it.mime ?: m.mime) }

private fun mergeGroup(g: List<DetectedMedia>): DetectedMedia {
    if (g.size == 1) {
        val m = g[0]
        return m.copy(qualities = withSource(m))
    }
    val best = g.firstOrNull { it.playing } ?: g.maxByOrNull { rank(it) }!!
    val merged = LinkedHashMap<Int, Quality>()
    g.flatMap { withSource(it) }
        .sortedWith(compareByDescending<Quality> { it.bytes != null && !it.estimated }.thenByDescending { it.bytes != null })
        .forEach { q -> if (q.height !in merged) merged[q.height] = q }
    // Una misma URL no puede ser dos calidades: se queda la que coincide con la altura escrita en la propia URL
    val byUrl = merged.values.filter { it.url != null }.groupBy { it.url }
    for ((u, list) in byUrl) {
        if (list.size < 2) continue
        val tk = MediaProbe.urlHeight(u!!)
        val keep = list.firstOrNull { it.height == tk } ?: list.first()
        list.filter { it !== keep }.forEach { merged.remove(it.height) }
    }
    return best.copy(
        qualities = merged.values.sortedByDescending { if (it.height == 0) -1 else it.height },
        title = best.title.ifBlank { g.firstOrNull { it.title.isNotBlank() }?.title.orEmpty() },
        thumb = best.thumb ?: g.firstOrNull { it.thumb != null }?.thumb,
        playing = g.any { it.playing },
        playingHeight = g.maxOf { it.playingHeight },
        durationSec = maxOf(best.durationSec, g.maxOf { it.durationSec }),
    )
}

private fun groupSameVideo(list: List<DetectedMedia>): List<DetectedMedia> {
    val groups = ArrayList<MutableList<DetectedMedia>>()
    for (m in list) {
        val g = groups.firstOrNull { grp -> grp.any { sameVideo(it, m) } }
        if (g == null) groups += mutableListOf(m) else g += m
    }
    return groups.map(::mergeGroup)
}

/** Menos de esto es un anuncio, un preview o una miniatura animada; desde [MAIN_SEC] es el contenido real. */
private const val SHORT_SEC = 45L
private const val MAIN_SEC = 120L
private const val TINY_BYTES = 8_000_000L

private fun bestBytes(m: DetectedMedia): Long = m.qualities.maxOfOrNull { it.bytes ?: 0L } ?: 0L

private fun hasInfo(m: DetectedMedia): Boolean =
    m.durationSec > 0 || m.title.isNotBlank() || m.qualities.any { it.bytes != null || it.height > 0 }

/**
 * Lo que de verdad se puede descargar de la página, sin basura:
 *  1. une las detecciones del mismo video (todas sus calidades, con peso);
 *  2. si la página tiene un video largo (el contenido), descarta lo corto (anuncios previos, previews y
 *     miniaturas animadas) y lo que no trae ningún dato;
 *  3. las listas de streaming sin duración conocida pasan a ser calidades del video principal;
 *  4. si lo que sonaba era un anuncio, "Reproduciendo ahora" pasa al contenido real;
 *  5. el video sin título toma el de la página.
 */
fun cleanDetected(list: List<DetectedMedia>, pageTitle: String = "", pageThumb: String? = null): List<DetectedMedia> {
    val merged = groupSameVideo(list)
    val mains = merged.filter { it.durationSec >= MAIN_SEC }
    var out: List<DetectedMedia>
    if (mains.isEmpty()) {
        // Sin video largo no hay con qué comparar (clips cortos, TikTok…): solo se oculta lo que no trae dato alguno
        out = if (merged.any { hasInfo(it) }) merged.filter { hasInfo(it) || it.playing } else merged
    } else {
        val keep = ArrayList<DetectedMedia>()
        val orphans = ArrayList<DetectedMedia>()
        for (m in merged) {
            when {
                m.durationSec >= MAIN_SEC -> keep += m
                m.durationSec in 1 until SHORT_SEC -> Unit
                m.durationSec == 0L && m.kind == MediaKind.DIRECT && bestBytes(m) < TINY_BYTES -> Unit
                m.durationSec == 0L && m.kind != MediaKind.DIRECT -> orphans += m
                else -> keep += m
            }
        }
        if (mains.size == 1 && orphans.isNotEmpty()) {
            val i = keep.indexOfFirst { it.id == mains[0].id }
            if (i >= 0) keep[i] = mergeGroup(listOf(keep[i]) + orphans)
        }
        out = keep
        val adWasPlaying = merged.any { it.playing && keep.none { k -> k.id == it.id } && orphans.none { o -> o.id == it.id } }
        if (adWasPlaying && out.none { it.playing }) {
            val main = out.filter { it.durationSec >= MAIN_SEC }.maxByOrNull { it.durationSec }
            if (main != null) out = out.map { if (it.id == main.id) it.copy(playing = true) else it }
        }
    }
    if (pageTitle.isNotBlank() || pageThumb != null) {
        // El video principal (el que suena o el más largo) toma el título y la portada de la página si no trae los suyos
        val lead = out.firstOrNull { it.playing } ?: out.maxByOrNull { it.durationSec }
        out = out.map {
            if (it.id != lead?.id) it
            else it.copy(
                title = it.title.ifBlank { pageTitle },
                thumb = it.thumb ?: pageThumb,
            )
        }
    }
    return out
}

fun dedupeDetected(list: List<DetectedMedia>): List<DetectedMedia> = cleanDetected(list)

/** Calidad que se elige sola: la mejor que no pase del tope de Ajustes (0 = la mejor). null = "Original". */
/** Un MP4 directo sin peso medido no está comprobado: no se ofrece para descargar (el peso es la garantía de que baja). */
fun isUnverified(q: Quality): Boolean = q.declared && q.kind == MediaKind.DIRECT && q.bytes == null

fun pickQualityObj(m: DetectedMedia, maxHeight: Int): Quality? {
    val qs = m.qualities.filter { !isUnverified(it) }.ifEmpty { m.qualities }
    if (qs.isEmpty()) return null
    if (maxHeight == 0) return qs.maxByOrNull { it.height } ?: qs.first()
    return qs.filter { it.height in 1..maxHeight }.maxByOrNull { it.height } ?: qs.minByOrNull { it.height } ?: qs.first()
}

fun pickQuality(m: DetectedMedia, maxHeight: Int): Int? = pickQualityObj(m, maxHeight)?.height?.takeIf { it > 0 }
