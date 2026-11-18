package app.flux.downloader.engine

import android.app.Application
import android.net.Uri
import app.flux.downloader.DownloadService
import app.flux.downloader.settings.AppSettings
import app.flux.downloader.settings.NetworkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class Status { QUEUED, RUNNING, SAVING, DONE, FAILED, CANCELED }

val ACTIVE_STATUSES = setOf(Status.QUEUED, Status.RUNNING, Status.SAVING)

data class DownloadItem(
    val id: String,
    val title: String,
    val thumbnail: String?,
    val label: String,
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,
    val etaSec: Long = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val uri: Uri? = null,
    val mime: String? = null,
    val error: String? = null,
    val location: String? = null,
    val waitingWifi: Boolean = false,
    val inVault: Boolean = false,
)

/**
 * Estado global de descargas. Vive fuera de la UI: sigue funcionando si la app
 * pasa a segundo plano gracias a [DownloadService].
 */
object DownloadManager {

    private const val MAX_PARALLEL = 2

    private lateinit var app: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Semaphore(MAX_PARALLEL)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    fun init(application: Application) {
        app = application
        Notifications.createChannels(app)
    }

    fun active(): List<DownloadItem> = _downloads.value.filter { it.status in ACTIVE_STATUSES }

    fun start(info: MediaInfo, choice: Choice) {
        val id = UUID.randomUUID().toString()
        val label = when {
            choice.audioOnly -> choice.audioFormat.uppercase()
            choice.height != null -> "${choice.height}p"
            info.kind == MediaKind.DIRECT -> "Original"
            else -> "Mejor calidad"
        }
        _downloads.update { listOf(DownloadItem(id, info.title, info.thumbnail, label)) + it }
        DownloadService.start(app)

        // Portada del detector: se baja ya con el Referer del sitio para que la lista la muestre enseguida
        val remoteThumb = info.thumbnail
        if (remoteThumb != null && remoteThumb.startsWith("http") && info.headers.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                ThumbMaker.fetchRemote(app, remoteThumb, info.headers, id)?.let { t -> update(id) { it.copy(thumbnail = t) } }
            }
        }

        jobs[id] = scope.launch {
            var toPrivate = false
            try {
                // "Solo Wi-Fi": la descarga espera en cola hasta que haya una red sin límite de datos
                while (AppSettings.wifiOnly.value && !NetworkState.isUnmetered(app)) {
                    update(id) { if (it.status == Status.CANCELED) it else it.copy(waitingWifi = true) }
                    delay(2_000)
                }
                update(id) { it.copy(waitingWifi = false) }
                gate.withPermit {
                    update(id) { if (it.status == Status.CANCELED) it else it.copy(status = Status.RUNNING) }
                    var lastPush = 0L
                    var lastPct = -1f
                    // La descarga reporta muy seguido: solo se actualiza la UI cuando cambia de verdad (>=0,5 %)
                    // y como máximo ~4 veces por segundo.
                    val progress: (Float, Long, Long) -> Unit = { pct, eta, total ->
                        val now = System.currentTimeMillis()
                        if (pct >= 100f || (now - lastPush >= 250 && kotlin.math.abs(pct - lastPct) >= 0.5f)) {
                            lastPush = now
                            lastPct = pct
                            update(id) {
                                it.copy(
                                    progress = (pct / 100f).coerceIn(0f, 1f),
                                    etaSec = eta,
                                    totalBytes = if (total > 0) total else it.totalBytes,
                                    downloadedBytes = if (total > 0) (total * (pct / 100f)).toLong() else it.downloadedBytes,
                                )
                            }
                        }
                    }
                    val file = if (info.kind == MediaKind.DIRECT && !choice.audioOnly) {
                        try {
                            NativeDownloader.download(app, info.url, info.headers, info.title, info.mime, id, progress)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Último recurso: yt-dlp. Si también falla, se informa el error del descargador propio.
                            Engine.cleanup(app, id)
                            try {
                                Engine.download(app, info.url, choice, id, info.headers, info.title, progress)
                            } catch (e2: CancellationException) {
                                throw e2
                            } catch (e2: Exception) {
                                throw e
                            }
                        }
                    } else {
                        Engine.download(app, info.url, choice, id, info.headers, info.title, progress)
                    }
                    update(id) { it.copy(status = Status.SAVING, progress = 1f) }
                    val saved = Storage.save(app, file, info.title, info.thumbnail)
                        ?: error("No se pudo guardar el archivo")
                    toPrivate = saved.inVault
                    // Sin portada (p. ej. descargas del detector): un fotograma del propio archivo
                    val cur = _downloads.value.firstOrNull { it.id == id }?.thumbnail
                    if (!saved.inVault && (info.thumbnail == null || (info.headers.isNotEmpty() && cur?.startsWith("file:") != true))) {
                        ThumbMaker.make(app, file, id)?.let { t -> update(id) { it.copy(thumbnail = t) } }
                    }
                    update(id) {
                        it.copy(
                            status = Status.DONE, uri = saved.uri, mime = saved.mime,
                            location = saved.location, inVault = saved.inVault,
                        )
                    }
                }
                Notifications.done(app, if (toPrivate) "Descarga guardada en la carpeta privada" else info.title)
            } catch (e: CancellationException) {
                update(id) { it.copy(status = Status.CANCELED) }
                throw e
            } catch (e: Exception) {
                update(id) {
                    if (it.status == Status.CANCELED) it
                    else it.copy(status = Status.FAILED, error = friendlyError(e))
                }
            } finally {
                Engine.cleanup(app, id)
                jobs.remove(id)
            }
        }
    }

    fun cancel(id: String) {
        update(id) { it.copy(status = Status.CANCELED) }
        Engine.cancel(id)
        jobs[id]?.cancel()
    }

    fun cancelAll() {
        active().forEach { cancel(it.id) }
    }

    /** La descarga ya se movió a la carpeta privada: deja de tener Uri pública. */
    fun markPrivate(id: String) {
        update(id) { it.copy(uri = null, inVault = true, location = "Carpeta privada") }
    }

    fun remove(id: String) {
        ThumbMaker.delete(app, id)
        _downloads.update { list -> list.filterNot { it.id == id } }
    }

    private fun update(id: String, change: (DownloadItem) -> DownloadItem) {
        _downloads.update { list -> list.map { if (it.id == id) change(it) else it } }
    }
}
