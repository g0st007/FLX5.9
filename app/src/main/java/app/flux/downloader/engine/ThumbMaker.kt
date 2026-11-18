package app.flux.downloader.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Miniatura de respaldo: un fotograma del propio archivo, para descargas que no traen portada. */
object ThumbMaker {

    fun make(ctx: Context, file: File, id: String): String? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val frame = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0)
                ?: return@runCatching null
            val w = 480
            val h = (frame.height * (w.toFloat() / frame.width)).toInt().coerceAtLeast(1)
            val scaled = if (frame.width > w) Bitmap.createScaledBitmap(frame, w, h, true) else frame
            val out = File(File(ctx.filesDir, "thumbs").apply { mkdirs() }, "$id.jpg")
            out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            Uri.fromFile(out).toString()
        } finally {
            r.release()
        }
    }.getOrNull()

    /**
     * Baja la portada remota con las cabeceras del sitio (muchas piden el Referer de la página) y la deja local:
     * así la miniatura de la lista aparece enseguida y no depende de que el sitio la sirva otra vez.
     */
    fun fetchRemote(ctx: Context, url: String, headers: Map<String, String>, id: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.instanceFollowRedirects = true
        headers["User-Agent"]?.let { c.setRequestProperty("User-Agent", it) }
        headers["Referer"]?.let { c.setRequestProperty("Referer", it) }
        c.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
        try {
            if (c.responseCode !in 200..299) return@runCatching null
            val type = c.contentType.orEmpty()
            if (type.isNotEmpty() && !type.startsWith("image/")) return@runCatching null
            if (c.contentLengthLong > 4_000_000) return@runCatching null
            val bytes = c.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > 4_000_000) return@runCatching null
            val out = File(File(ctx.filesDir, "thumbs").apply { mkdirs() }, "$id.jpg")
            out.writeBytes(bytes)
            Uri.fromFile(out).toString()
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    fun delete(ctx: Context, id: String) {
        runCatching { File(File(ctx.filesDir, "thumbs"), "$id.jpg").delete() }
    }
}
