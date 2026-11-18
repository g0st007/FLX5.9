package app.flux.downloader.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ajustes de la app. Cada valor es un StateFlow para que la UI y el motor reaccionen al instante. */
object AppSettings {

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    /** Cómo se muestra el avance de una descarga. */
    enum class ProgressStyle { PERCENT_ETA, SIZE, BOTH }

    private lateinit var sp: SharedPreferences

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _wifiOnly = MutableStateFlow(false)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    /** "" = sin límite; si no, formato de yt-dlp: 500K, 1M, 2M, 5M. */
    private val _speedLimit = MutableStateFlow("")
    val speedLimit: StateFlow<String> = _speedLimit.asStateFlow()

    private val _audioFormat = MutableStateFlow("mp3")
    val audioFormat: StateFlow<String> = _audioFormat.asStateFlow()

    /** 0 = la mejor disponible; si no, altura máxima preseleccionada (1080, 720, 480). */
    private val _maxHeight = MutableStateFlow(0)
    val maxHeight: StateFlow<Int> = _maxHeight.asStateFlow()

    private val _saveToGallery = MutableStateFlow(false)
    val saveToGallery: StateFlow<Boolean> = _saveToGallery.asStateFlow()

    private val _vaultByDefault = MutableStateFlow(false)
    val vaultByDefault: StateFlow<Boolean> = _vaultByDefault.asStateFlow()

    private val _progressStyle = MutableStateFlow(ProgressStyle.SIZE)
    val progressStyle: StateFlow<ProgressStyle> = _progressStyle.asStateFlow()

    /** Los sitios se abren en modo escritorio salvo que el usuario elija "móvil" para ese sitio. */
    private val _defaultDesktop = MutableStateFlow(true)
    val defaultDesktop: StateFlow<Boolean> = _defaultDesktop.asStateFlow()

    /** Al pasar un video del navegador a pantalla completa, abrirlo en el reproductor de Flux. */
    private val _ownPlayer = MutableStateFlow(true)
    val ownPlayer: StateFlow<Boolean> = _ownPlayer.asStateFlow()

    private val _allowVaultCaptures = MutableStateFlow(false)
    val allowVaultCaptures: StateFlow<Boolean> = _allowVaultCaptures.asStateFlow()

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _themeMode.value = runCatching { ThemeMode.valueOf(sp.getString("theme", "SYSTEM") ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM)
        _wifiOnly.value = sp.getBoolean("wifi_only", false)
        _speedLimit.value = sp.getString("speed_limit", "") ?: ""
        _audioFormat.value = sp.getString("audio_format", "mp3") ?: "mp3"
        _maxHeight.value = sp.getInt("max_height", 0)
        _saveToGallery.value = sp.getBoolean("save_to_gallery", false)
        _vaultByDefault.value = sp.getBoolean("vault_by_default", false)
        _allowVaultCaptures.value = sp.getBoolean("allow_vault_captures", false)
        _progressStyle.value = runCatching { ProgressStyle.valueOf(sp.getString("progress_style", "SIZE") ?: "SIZE") }
            .getOrDefault(ProgressStyle.SIZE)
        _defaultDesktop.value = sp.getBoolean("default_desktop", true)
        _ownPlayer.value = sp.getBoolean("own_player", true)
    }

    fun setThemeMode(v: ThemeMode) { _themeMode.value = v; sp.edit().putString("theme", v.name).apply() }
    fun setWifiOnly(v: Boolean) { _wifiOnly.value = v; sp.edit().putBoolean("wifi_only", v).apply() }
    fun setSpeedLimit(v: String) { _speedLimit.value = v; sp.edit().putString("speed_limit", v).apply() }
    fun setAudioFormat(v: String) { _audioFormat.value = v; sp.edit().putString("audio_format", v).apply() }
    fun setMaxHeight(v: Int) { _maxHeight.value = v; sp.edit().putInt("max_height", v).apply() }
    fun setSaveToGallery(v: Boolean) { _saveToGallery.value = v; sp.edit().putBoolean("save_to_gallery", v).apply() }
    fun setVaultByDefault(v: Boolean) { _vaultByDefault.value = v; sp.edit().putBoolean("vault_by_default", v).apply() }
    fun setProgressStyle(v: ProgressStyle) { _progressStyle.value = v; sp.edit().putString("progress_style", v.name).apply() }
    fun setDefaultDesktop(v: Boolean) { _defaultDesktop.value = v; sp.edit().putBoolean("default_desktop", v).apply() }
    fun setOwnPlayer(v: Boolean) { _ownPlayer.value = v; sp.edit().putBoolean("own_player", v).apply() }
    fun setAllowVaultCaptures(v: Boolean) { _allowVaultCaptures.value = v; sp.edit().putBoolean("allow_vault_captures", v).apply() }
}

object NetworkState {
    /** true si la conexión activa no es de datos medidos (Wi-Fi, Ethernet…). */
    fun isUnmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
