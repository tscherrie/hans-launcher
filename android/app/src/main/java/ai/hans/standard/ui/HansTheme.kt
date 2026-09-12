package ai.hans.standard.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val HansLightColors = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6E2D8),
    onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFF323232),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF8F6EF),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFF8F6EF),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFE9E6DD),
    onSurfaceVariant = Color(0xFF292929),
    outline = Color(0xFF3D3D3D),
    outlineVariant = Color(0xFF777777),
    error = Color(0xFF8A1C1C),
    onError = Color.White,
)

private val HansDarkColors = darkColorScheme(
    primary = Color(0xFFF4F1E8),
    onPrimary = Color(0xFF101010),
    primaryContainer = Color(0xFF3A3935),
    onPrimaryContainer = Color(0xFFF4F1E8),
    secondary = Color(0xFFD7D4CC),
    onSecondary = Color(0xFF101010),
    background = Color(0xFF0E0E0E),
    onBackground = Color(0xFFF4F1E8),
    surface = Color(0xFF0E0E0E),
    onSurface = Color(0xFFF4F1E8),
    surfaceVariant = Color(0xFF292929),
    onSurfaceVariant = Color(0xFFE5E2DA),
    outline = Color(0xFFD7D4CC),
    outlineVariant = Color(0xFF8E8C86),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val HansTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 54.sp,
        lineHeight = 58.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        lineHeight = 48.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 29.sp,
        lineHeight = 35.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 29.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 25.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 23.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
)

private val HansShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(9.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
)

@Composable
fun HansTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) HansDarkColors else HansLightColors,
        typography = HansTypography,
        shapes = HansShapes,
        content = content,
    )
}
