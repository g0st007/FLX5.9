package app.flux.downloader.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.flux.downloader.browser.BrowserSession
import app.flux.downloader.engine.Choice
import app.flux.downloader.engine.DownloadItem
import app.flux.downloader.engine.DownloadManager
import app.flux.downloader.engine.Engine
import app.flux.downloader.engine.MediaInfo
import app.flux.downloader.engine.SiteGuard
import app.flux.downloader.engine.friendlyError
import app.flux.downloader.vault.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _link = MutableStateFlow("")
    val link: StateFlow<String> = _link.asStateFlow()

    private val _analysis = MutableStateFlow<Analysis>(Analysis.Idle)
    val analysis: StateFlow<Analysis> = _analysis.asStateFlow()

    val downloads: StateFlow<List<DownloadItem>> = DownloadManager.downloads

    /** Estado del navegador (sobrevive al cambio de pestaña). */
    val browserSession = BrowserSession()

    fun onLinkChange(value: String) {
        _link.value = value
        if (_analysis.value !is Analysis.Loading) _analysis.value = Analysis.Idle
    }

    /** Texto pegado o compartido desde otra app: extrae el enlace y lo analiza. */
    fun onSharedText(text: String) {
        _link.value = SiteGuard.extractUrl(text) ?: text.trim()
        analyze()
    }

    fun analyze() {
        val url = SiteGuard.extractUrl(_link.value)
        if (url == null) {
            _analysis.value = Analysis.Error("Pega un enlace válido (que empiece por https://)")
            return
        }
        if (SiteGuard.isBlocked(url)) {
            _analysis.value = Analysis.Error("Este sitio no es compatible con Flux.")
            return
        }
        _analysis.value = Analysis.Loading
        viewModelScope.launch {
            _analysis.value = try {
                Analysis.Ready(Engine.analyze(url))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Analysis.Error(friendlyError(e))
            }
        }
    }

    /** Enlace que otra pestaña pide abrir en el navegador (p. ej. cuando el análisis no encuentra video). */
    var browserRequest by mutableStateOf<String?>(null)

    fun openInBrowser(url: String) { browserRequest = url }

    fun startDownload(info: MediaInfo, choice: Choice) {
        DownloadManager.start(info, choice)
        // Ya está en la lista de descargas: se limpia el enlace y desaparece la tarjeta de análisis
        val cur = _analysis.value
        if (cur is Analysis.Ready && cur.info.url == info.url) {
            _link.value = ""
            _analysis.value = Analysis.Idle
        }
    }
    fun cancel(id: String) = DownloadManager.cancel(id)
    fun remove(id: String) = DownloadManager.remove(id)

    /** Mueve una descarga ya guardada a la carpeta privada (copia al almacenamiento interno y borra el original). */
    fun moveToVault(d: DownloadItem, onResult: (Boolean) -> Unit) {
        val uri = d.uri
        if (uri == null) { onResult(false); return }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { Vault.importFromUri(uri, d.title, d.thumbnail, d.mime) }
            if (ok) DownloadManager.markPrivate(d.id)
            onResult(ok)
        }
    }
}
