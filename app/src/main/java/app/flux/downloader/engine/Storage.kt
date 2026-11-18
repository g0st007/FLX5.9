package app.flux.downloader.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.flux.downloader.settings.AppSettings
import app.flux.downloader.vault.Vault
import java.io.File

/** Resultado de guardar una descarga. [uri] es null cuando va a la carpeta privada. */
data class Saved(val uri: Uri?, val mime: String, val location: String, val inVault: Boolean = false)

object Storage {

    fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "flv" -> "video/x-flv"
        "3gp" -> "video/3gpp"
        "mpg" -> "video/mpeg"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    /** Punto de entrada de las descargas: carpeta privada si así se configuró, si no, almacenamiento público. */
    fun save(ctx: Context, file: File, title: String, thumb: String?): Saved? {
        if (AppSettings.vaultByDefault.value && Vault.hasPin) {
            val mime = mimeOf(file)
            Vault.importFile(file, title, thumb, mime) ?: return null
            return Saved(null, mime, "Carpeta privada", inVault = true)
        }
        return saveToPublic(ctx, file)
    }

    /**
     * Copia el archivo a Descargas/Flux (MediaStore, sin permisos) o, si el ajuste "Mostrar en la
     * galería" está activo, a Películas/Flux o Música/Flux para que lo vean la galería y los reproductores.
     */
    fun saveToPublic(ctx: Context, file: File, displayName: String? = null): Saved? {
        val mime = mimeOf(file)
        val gallery = AppSettings.saveToGallery.value && (mime.startsWith("video/") || mime.startsWith("audio/"))
        val collection: Uri
        val relative: String
        val location: String
        when {
            gallery && mime.startsWith("video/") -> {
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                relative = Environment.DIRECTORY_MOVIES + "/Flux"
                location = "Películas/Flux"
            }
            gallery -> {
                collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                relative = Environment.DIRECTORY_MUSIC + "/Flux"
                location = "Música/Flux"
            }
            else -> {
                collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                relative = Environment.DIRECTORY_DOWNLOADS + "/Flux"
                location = "Descargas/Flux"
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName ?: file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values) ?: return null
        val out = resolver.openOutputStream(uri) ?: return null
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
        return Saved(uri, mime, location)
    }
}
