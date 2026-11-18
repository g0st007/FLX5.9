package app.flux.downloader.engine

const val OPEN_IN_BROWSER_HINT =
    "No se encontró un video descargable en este enlace. Ábrelo en el navegador de Flux, reproduce el video y toca el aviso \"Video detectado\"."

fun friendlyError(e: Exception): String {
    val msg = e.message.orEmpty()
    return when {
        msg.contains("Unsupported URL", true) || msg.contains("Unable to extract", true) ||
            msg.contains("[generic]", true) || msg.contains("No video formats", true) -> OPEN_IN_BROWSER_HINT
        msg.contains("HTTP Error 403", true) -> "El sitio rechazó la descarga (403). Ábrelo en el navegador de Flux para detectar el video con tu sesión."
        msg.contains("HTTP Error 404", true) -> "El video ya no existe en ese enlace (404)."
        msg.contains("private", true) || msg.contains("login", true) || msg.contains("sign in", true) ->
            "El contenido es privado o requiere iniciar sesión."
        msg.contains("Unable to resolve", true) || msg.contains("timed out", true) || msg.contains("network", true) ->
            "Error de red. Revisa tu conexión e inténtalo de nuevo."
        msg.contains("DRM", true) -> "Este contenido está protegido con DRM y no se puede descargar."
        else -> msg.lineSequence().lastOrNull { it.isNotBlank() }?.take(160) ?: "Ocurrió un error inesperado."
    }
}
