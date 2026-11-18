package app.flux.downloader.ui

import app.flux.downloader.engine.MediaInfo

sealed interface Analysis {
    data object Idle : Analysis
    data object Loading : Analysis
    data class Ready(val info: MediaInfo) : Analysis
    data class Error(val message: String) : Analysis
}

fun formatDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 1 536 000 -> "1,5 MB". */
fun formatSize(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> "%.1f GB".format(bytes / gb)
        bytes >= mb -> "%.0f MB".format(bytes / mb)
        else -> "%.0f KB".format(bytes / kb)
    }
}
