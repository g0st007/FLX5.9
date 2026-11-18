# Flux (nombre provisional)

Descargador de video y audio todo en uno para Android. Motor: yt-dlp + FFmpeg (youtubedl-android).
YouTube queda excluido por decisión de producto (`engine/SiteGuard.kt`).

## Cómo abrirlo
1. Android Studio (Koala o más nuevo) → *Open* → carpeta `Flux`.
2. Dejar que sincronice Gradle (genera el wrapper automáticamente).
3. Ejecutar en un dispositivo o emulador con Android 10+ (minSdk 29).

## Estructura
- `engine/Engine.kt` — análisis del enlace y descarga con yt-dlp/FFmpeg
- `engine/SiteGuard.kt` — validación de enlaces y sitios bloqueados
- `engine/Downloads.kt` — cola y estado de descargas · `DownloadService.kt` — descargas en segundo plano
- `adblock/` — motor de filtros y gestor de listas
- `browser/` — navegador seguro (controlador con la política de seguridad, pantalla, bloqueador)
- `engine/Storage.kt` — guarda en Descargas/Flux vía MediaStore
- `ui/` — pantalla principal (Compose), ViewModel, tema negro/naranja
- `logo.svg` — vista previa del logo; el ícono real está en `res/drawable/ic_launcher_foreground.xml`

## Fase 1 (esto)
Pegar o compartir enlace → analizar → elegir video (calidad) o solo audio (MP3/M4A/OPUS) → descargar con progreso, cancelar y abrir.

## Fase 2 (hecha)
Servicio en primer plano con notificación de progreso y botón "Cancelar todo", cola (2 descargas en paralelo), aviso al completar. Estado global en `engine/Downloads.kt`.

## Fase 3 (hecha): navegador seguro
`browser/` — WebView endurecido: solo HTTPS, certificados inválidos bloqueados, Navegación segura, popups/esquemas externos/redirecciones automáticas bloqueados, permisos denegados, cookies de terceros off, ejecutables bloqueados, bloqueo de anuncios y rastreadores, detección de video en la página y botón Descargar. Navegación y búsqueda con DuckDuckGo.

## Fase 4 (hecha): bloqueo serio + navegador completo
- `adblock/` — motor de filtros compatible con la sintaxis de EasyList/uBlock (reglas de red con índice por dominio y token, excepciones, opciones third-party / domain / tipo de recurso, `$important`) más filtrado cosmético (CSS que oculta huecos). `FilterManager` descarga y actualiza las listas (EasyList, EasyPrivacy, uBlock, badware, Peter Lowe, Fanboy, EasyList Español) cada 72 h o a demanda; hay una lista semilla para el primer arranque.
- Pausa de protección por sitio, contador de bloqueos, listas activables.
- Descargas: solo YouTube está bloqueado. Los archivos generales usan el gestor del sistema; los ejecutables piden confirmación.
- Pestañas, marcadores, historial, modo escritorio y botón de borrado total.

## Pulido y rendimiento (v0.1.1)
- Tema: `FluxTheme` ahora envuelve todo en un `Surface` con `contentColor` claro (antes los íconos/títulos sin color salían negros sobre negro). Tipografía y formas propias.
- Peso estimado por calidad (≈ MB/GB) en las opciones de video; el análisis lee el JSON completo de yt-dlp (`engine/Engine.kt`).
- Reddit: `engine/SiteResolver.kt` resuelve posts que enlazan a RedGifs para evitar el error de inicio de sesión.
- Rendimiento: build `release` con R8 (CI genera el APK release), progreso de descargas limitado a ~4 actualizaciones/s, listas de filtros y actualización de yt-dlp diferidas tras el arranque.
- Navegador: íconos reales de los sitios, selector de videos legible, botón Descargar en una línea.

## Identidad visual (v0.1.2)
- Tipografía: Inter (subconjunto latino, ~58 KB por peso) + Inter Display ExtraBold para el titular. Licencia en `FONTS-LICENSE.txt`.
- Modo claro y oscuro automáticos (texto blanco en oscuro, negro en claro).
- Pantalla principal con titular de marketing ("+1.000 sitios. Una sola app."), botones Pegar/Analizar y chips de sitios.

## Cambios (v0.5.0)
- **Todas las calidades**: cada calidad lleva su propia fuente (`Quality.url/kind/mime`) y las detecciones del mismo video se unen en una sola (`dedupeDetected`). Nuevas formas de encontrarlas: escaneo de los `<script>` de la página y del Resource Timing (`scanText`/`scanPerf`), búsqueda de la lista maestra HLS junto a una lista de una sola calidad (`discoverMaster`), lectura de resolución y duración de MP4 por rangos HTTP sin bajarlos (`readMp4`) y unión de las calidades del extractor de yt-dlp al video que se reproduce. Se consulta siempre al extractor (con caché por página).
- **Ventanas emergentes**: respetan el ajuste (desactivado = se abren en pestaña nueva); con bloqueo activo solo se abren las que vienen de un toque en un enlace real y que no son anuncios; ya no se cargan en la misma pestaña.
- **Mantener pulsado** un enlace o imagen: abrir en pestaña nueva / en segundo plano, copiar, compartir.
- **Redirecciones**: solo cuenta como del usuario un toque en un enlace hacia ese destino (o un tramo de una carga que él inició); los destinos de las listas de anuncios se bloquean siempre; si se bloquea una redirección justo después de volver atrás se retrocede otra vez (trampas de historial).
- **Anuncios**: los scripts y marcos propios del sitio vuelven a filtrarse (solo se protegen medios y fetch/XHR propios); las listas se cargan antes tras el arranque.

## Cambios (v0.4.1)
- La barra "Video detectado" ahora tiene **pantalla completa** (abre el reproductor de Flux con el video que se reproduce o el único de la página, desde donde iba) y el botón de descarga pasa a ser solo ícono, para que el texto se lea completo.

## Cambios (v0.4.0)
- **Reproductor propio de Flux** (`PlayerScreen`, Media3 + HLS/DASH): se abre al pasar a pantalla completa un video del navegador (con el punto donde iba y las cabeceras del sitio), desde "Ver en Flux" en el selector, y para archivos locales. Gestos (doble toque ±10 s, brillo/volumen, deslizar para adelantar, mantener = 2x), velocidad 0,25x–3x, calidad/audio/subtítulos de streams, aspecto (ajustar/llenar/zoom), bucle, bloqueo de controles, orientación automática o fija, imagen en imagen, descargar el video que se ve y reanudar donde se dejó. Ajuste para desactivarlo.
- **Selector**: se muestran todas las detecciones (así se elige calidad); las repetidas llevan "Mismo video"; "Todos" y la casilla de seleccionar todos usan 1 por video.
- **Parpadeo** al entrar a la pestaña Navegador: el WebView se crea con el color de fondo del tema.
- **Modo escritorio por defecto** (ajustable, y recordado por sitio en ambos sentidos).
- **Progreso por defecto**: "MB descargados y peso total" (texto renombrado).

## Cambios (v0.3.0)
- **Selector para galerías**: cada detección con miniatura, título, duración, calidad y peso (datos que aporta el reproductor de la página); "Reproduciendo ahora" arriba con descarga de un toque; selección múltiple, "Elegidos" y "Todos" con la calidad de Ajustes y **sin repetidos** (`dedupeDetected`).
- **Barra "Video detectado"** bajo la dirección (ya no tapa la página).
- **Ajuste "Progreso de la descarga"**: porcentaje y tiempo / MB-GB descargados y total / todo junto (`ProgressStyle`).
- **Compatibilidad del navegador**: autoplay silenciado permitido (vistas previas y miniaturas animadas) con el sonido frenado hasta que el usuario interactúe; `<meta viewport>` respetado; UA de escritorio derivado del real; **modo escritorio recordado por sitio**; ventanas emergentes interceptadas (`onCreateWindow`); redirecciones no iniciadas por un toque bloqueadas con opción "Permitir"; hook de JS que imita el código nativo de `fetch`/XHR.
- **Diagnóstico del sitio** (menú ⋮): lista lo bloqueado por filtros, errores HTTP/red de subrecursos, errores de JS y bloqueos de Navegación segura, con "Copiar".
- Portada de las descargas del detector bajada con el Referer del sitio (y fotograma del archivo como respaldo).
- Corrección: la etiqueta "MP4/" ahora sale "MP4"; el nombre "Original" para archivos directos.

## Cambios (v0.2.1)
- **Descargador propio** para archivos directos (`NativeDownloader`): corrige el error de yt-dlp "The extracted extension ('php') is unusual" en sitios genéricos (get_file.php, video.php?id=…). El nombre y la extensión salen del tipo de contenido real, se envían las cabeceras del sitio, se respeta el límite de velocidad y cancelar corta la conexión al instante. Si falla, se prueba yt-dlp como último recurso.
- **Miniaturas** en las descargas del detector: portada de la página (poster / og:image) y, si no hay, un fotograma del propio archivo (`ThumbMaker`).
- El detector vuelve a informar lo que ya había enviado cuando una app de una sola página cambia de contenido.

## Detección universal (v0.2.0)
- **Detector nativo** en el navegador (`MediaProbe`, `DetectedMedia`): lee archivos directos (HEAD), listas HLS (.m3u8) y DASH (.mpd) por su cuenta y muestra calidades, duración y peso en un instante, sin yt-dlp ni lista de sitios. Un script al inicio de la página (`SNIFF_JS`) añade lo que el reproductor sabe (`<video>`, og:video, JSON-LD, cabeceras `Content-Type` de fetch/XHR).
- Filtros de basura: píxeles/miniaturas (<150 KB), fragmentos de streaming y variantes de una lista maestra no se listan aparte.
- La descarga de un medio detectado usa las **cabeceras del sitio** (Referer, User-Agent, Cookie) con yt-dlp.
- **Análisis en Descargas** (`Engine.analyze`): extractor de yt-dlp y lector nativo de la página (`PageScanner`) en paralelo; si el nativo encuentra medios y yt-dlp tarda más de 2,5 s, se usa el nativo. El estimador de peso ya no deja "—" en HLS.
- Al pulsar **Descargar** se limpia el enlace y desaparece la tarjeta de análisis. Si no hay video: botón "Abrir en el navegador de Flux".
- Nueva pestaña **Archivos**.
- Compatibilidad del navegador: cookies de terceros, contenido mixto pasivo, iframes http, redirecciones dentro del mismo sitio, y los medios y scripts propios del sitio nunca se bloquean; menú "Quitar bloqueos de la página".

## Cambios (v0.1.4)
- El selector de descarga del navegador analiza la página y lista cada calidad con su peso; tocar una calidad inicia la descarga directamente.
- Los videos detectados muestran resolución (si la URL la trae) y peso (Content-Length); las transmisiones HLS/DASH no informan peso.

## Funciones nuevas (v0.1.3)
- **Aviso flotante** "Video detectado" en el navegador (descartable por página) que abre el selector de descarga.
- **Reproductor propio** (Media3/ExoPlayer): velocidad desde el engranaje, pantalla completa horizontal, pausa al salir.
- **Carpeta privada** con código de 6 dígitos (PBKDF2 + sal, bloqueo tras 5 intentos, se bloquea al salir de la app, sin capturas por defecto). Archivos en almacenamiento interno de la app (sin cifrar en disco).
- **Ajustes**: tema, solo Wi-Fi, límite de velocidad, calidad y formato de audio por defecto, mostrar en galería, ajustes del navegador (anuncios, redirecciones, listas, borrar datos), privacidad.
- Referencias visuales de cada sección en `referencias/`.

## Siguiente
- Progreso unificado video+audio (hoy el % se reinicia al pasar de una pista a otra)
- Fase 3: ajustes (solo Wi-Fi, límite de velocidad, carpeta), playlists, subtítulos, recorte antes de descargar
- Fase 4: bóveda privada, reproductor integrado, navegador con detección de videos
