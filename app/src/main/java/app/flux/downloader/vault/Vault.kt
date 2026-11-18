package app.flux.downloader.vault

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import app.flux.downloader.engine.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class VaultItem(
    val id: String,
    val title: String,
    val fileName: String,
    val mime: String,
    val thumb: String?,
    val size: Long,
    val addedAt: Long,
) {
    val isAudio: Boolean get() = mime.startsWith("audio/")
}

sealed interface PinResult {
    data object Ok : PinResult
    data class Wrong(val attemptsLeft: Int) : PinResult
    data class Locked(val seconds: Int) : PinResult
}

/**
 * Carpeta privada: los archivos viven en el almacenamiento interno de la app (no aparecen en la
 * galería ni en otras apps) y se protegen con un código de [PIN_LENGTH] dígitos guardado con
 * PBKDF2 + sal. Tras 5 intentos fallidos se bloquea con espera creciente.
 */
object Vault {

    const val PIN_LENGTH = 6
    private const val MAX_FAILS = 5

    private lateinit var app: Application
    private lateinit var sp: SharedPreferences
    private val lock = Any()

    private val _items = MutableStateFlow<List<VaultItem>>(emptyList())
    val items: StateFlow<List<VaultItem>> = _items.asStateFlow()

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val dir: File get() = File(app.filesDir, "vault").apply { mkdirs() }
    private val indexFile: File get() = File(dir, "index.json")

    fun init(application: Application) {
        app = application
        sp = app.getSharedPreferences("vault", Context.MODE_PRIVATE)
        _items.value = readIndex()
    }

    // ------------------------------------------------------------------ Código

    val hasPin: Boolean get() = sp.contains("pin_hash")

    fun lock() { _unlocked.value = false }

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit()
            .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .putInt("fails", 0)
            .putLong("lock_until", 0L)
            .apply()
        _unlocked.value = true
    }

    fun verify(pin: String): PinResult {
        val now = System.currentTimeMillis()
        val until = sp.getLong("lock_until", 0L)
        if (now < until) return PinResult.Locked(((until - now + 999) / 1000).toInt())

        val salt = Base64.decode(sp.getString("pin_salt", "") ?: "", Base64.NO_WRAP)
        val expected = Base64.decode(sp.getString("pin_hash", "") ?: "", Base64.NO_WRAP)
        if (MessageDigest.isEqual(hash(pin, salt), expected)) {
            sp.edit().putInt("fails", 0).putLong("lock_until", 0L).apply()
            _unlocked.value = true
            return PinResult.Ok
        }

        val fails = sp.getInt("fails", 0) + 1
        if (fails % MAX_FAILS == 0) {
            val round = fails / MAX_FAILS
            val secs = minOf(30L shl (round - 1), 900L)
            sp.edit().putInt("fails", fails).putLong("lock_until", now + secs * 1000).apply()
            return PinResult.Locked(secs.toInt())
        }
        sp.edit().putInt("fails", fails).apply()
        return PinResult.Wrong(MAX_FAILS - fails % MAX_FAILS)
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 60_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    // ------------------------------------------------------------------ Archivos

    fun fileOf(item: VaultItem): File = File(dir, item.fileName)

    /** Copia un archivo ya descargado (en caché) a la carpeta privada. */
    fun importFile(file: File, title: String, thumb: String?, mime: String): VaultItem? = runCatching {
        val name = "${UUID.randomUUID()}.${file.extension.ifBlank { "bin" }}"
        val dest = File(dir, name)
        file.copyTo(dest, overwrite = true)
        VaultItem(UUID.randomUUID().toString(), title, name, mime, thumb, dest.length(), System.currentTimeMillis())
            .also { add(it) }
    }.getOrNull()

    /** Mueve un archivo guardado en Descargas/Galería (por su Uri) a la carpeta privada. */
    fun importFromUri(uri: Uri, title: String, thumb: String?, mime: String?): Boolean = runCatching {
        val resolver = app.contentResolver
        val type = mime ?: "application/octet-stream"
        val name = "${UUID.randomUUID()}.${extFor(type)}"
        val dest = File(dir, name)
        val input = resolver.openInputStream(uri) ?: error("No se pudo leer el archivo")
        input.use { src -> dest.outputStream().use { src.copyTo(it) } }
        add(VaultItem(UUID.randomUUID().toString(), title, name, type, thumb, dest.length(), System.currentTimeMillis()))
        runCatching { resolver.delete(uri, null, null) }
        true
    }.getOrDefault(false)

    /** Saca el archivo de la carpeta privada y lo deja en Descargas/Flux (o Galería, según ajustes). */
    fun exportToPublic(item: VaultItem): Boolean = runCatching {
        val f = fileOf(item)
        val display = item.title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80) + "." + f.extension
        Storage.saveToPublic(app, f, display) ?: error("No se pudo guardar")
        delete(item)
        true
    }.getOrDefault(false)

    fun delete(item: VaultItem) = synchronized(lock) {
        fileOf(item).delete()
        val list = _items.value.filterNot { it.id == item.id }
        _items.value = list
        writeIndex(list)
    }

    private fun add(item: VaultItem) = synchronized(lock) {
        val list = listOf(item) + _items.value
        _items.value = list
        writeIndex(list)
    }

    private fun extFor(mime: String): String = when (mime.lowercase()) {
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        "audio/mpeg" -> "mp3"
        "audio/mp4" -> "m4a"
        "audio/ogg" -> "opus"
        else -> "bin"
    }

    // ------------------------------------------------------------------ Índice

    private fun readIndex(): List<VaultItem> = runCatching {
        if (!indexFile.exists()) return emptyList()
        val a = JSONArray(indexFile.readText())
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val item = VaultItem(
                id = o.getString("id"),
                title = o.getString("t"),
                fileName = o.getString("f"),
                mime = o.optString("m", "video/mp4"),
                thumb = o.optString("th").takeIf { it.isNotBlank() },
                size = o.optLong("s", 0L),
                addedAt = o.optLong("d", 0L),
            )
            item.takeIf { File(dir, it.fileName).exists() }
        }
    }.getOrDefault(emptyList())

    private fun writeIndex(list: List<VaultItem>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject().put("id", it.id).put("t", it.title).put("f", it.fileName).put("m", it.mime)
                    .put("th", it.thumb ?: "").put("s", it.size).put("d", it.addedAt),
            )
        }
        runCatching { indexFile.writeText(a.toString()) }
    }
}
