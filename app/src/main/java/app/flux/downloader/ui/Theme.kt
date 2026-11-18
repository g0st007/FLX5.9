package app.flux.downloader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flux.downloader.R

val FluxOrange = Color(0xFFFF6A00)

private val FluxColors = darkColorScheme(
    primary = FluxOrange,
    onPrimary = Color(0xFF1A0B00),
    secondary = FluxOrange,
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF222222),
    onSurfaceVariant = Color(0xFFB5B5B5),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF2A2A2A),
    error = Color(0xFFFF5252),
    secondaryContainer = Color(0xFF3A1F08),
    onSecondaryContainer = FluxOrange,
    // Menús, diálogos y barras: neutros, sin el tinte azulado/violeta por defecto de Material
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF101010),
    surfaceContainer = Color(0xFF161616),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF242424),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFE85D00),
    onPrimary = Color(0xFF1A0B00),
    secondary = Color(0xFFE85D00),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF0A0A0A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFFEFEFEF),
    onSurfaceVariant = Color(0xFF5C5C5C),
    outline = Color(0xFFC4C4C4),
    outlineVariant = Color(0xFFE3E3E3),
    error = Color(0xFFD93025),
    secondaryContainer = Color(0xFFFFE6D5),
    onSecondaryContainer = Color(0xFFB34700),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE6E6E6),
)

/** Inter: la tipografía de la app (subconjunto latino incluido en res/font). */
private val Sans = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Inter Display ExtraBold: solo para el titular grande de la pantalla principal. */
val FluxHeroFont = FontFamily(Font(R.font.inter_display_extrabold, FontWeight.ExtraBold))

val FluxHeroStyle = TextStyle(
    fontFamily = FluxHeroFont,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 38.sp,
    lineHeight = 41.sp,
    letterSpacing = (-1.4).sp,
)

/** Escala tipográfica propia: jerarquía clara, interlineado cómodo y títulos con carácter. */
private val FluxTypography = Typography(
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp),
)

private val FluxShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun FluxTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) FluxColors else LightColors
    MaterialTheme(colorScheme = colors, typography = FluxTypography, shapes = FluxShapes) {
        // Sin este Surface, LocalContentColor queda en NEGRO: de ahí el título, los
        // íconos y las etiquetas "invisibles" sobre el fondo oscuro.
        // Texto principal: blanco en modo oscuro, negro en modo claro.
        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}
