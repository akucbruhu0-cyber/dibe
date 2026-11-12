package app.dibe.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dibe.android.R

/** Rubik covers Latin and Hebrew (the desktop uses it for PDF and the promo for everything). */
val Rubik = FontFamily(
    Font(R.font.rubik_regular, FontWeight.Normal),
    Font(R.font.rubik_regular, FontWeight.Medium),
    Font(R.font.rubik_bold, FontWeight.SemiBold),
    Font(R.font.rubik_bold, FontWeight.Bold),
)

private val LightScheme = lightColorScheme(
    primary = Light.PrimaryStrong,
    onPrimary = Color.White,
    primaryContainer = Light.Primary,
    onPrimaryContainer = Light.OnPrimary,
    inversePrimary = Light.Primary,
    secondary = Light.PillDark,
    onSecondary = Color.White,
    secondaryContainer = Light.Sidebar,
    onSecondaryContainer = Light.Foreground,
    tertiary = Brand.Orange,
    background = Light.Background,
    onBackground = Light.Foreground,
    surface = Light.Card,
    onSurface = Light.Foreground,
    surfaceVariant = Light.Muted,
    onSurfaceVariant = Light.MutedForeground,
    // Elevation is a shadow here, never a tint.
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Light.Card,
    surfaceContainerLow = Light.Card,
    surfaceContainer = Light.Muted,
    surfaceContainerHigh = Light.Card,
    surfaceContainerHighest = Light.Border,
    // M3 draws the border and thumb of an off switch, and outlined fields, in `outline`: it needs 3:1
    // on white. Decorative hairlines use outlineVariant.
    outline = Light.Control,
    outlineVariant = Light.Border,
    error = Brand.Red,
    onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = Dark.PrimaryStrong,
    onPrimary = Dark.OnPrimary,
    primaryContainer = Dark.Primary,
    onPrimaryContainer = Dark.OnPrimary,
    inversePrimary = Light.PrimaryStrong,
    secondary = Dark.Foreground,
    onSecondary = Dark.Background,
    secondaryContainer = Dark.Muted,
    onSecondaryContainer = Dark.Foreground,
    tertiary = Brand.Orange,
    background = Dark.Background,
    onBackground = Dark.Foreground,
    surface = Dark.Card,
    onSurface = Dark.Foreground,
    surfaceVariant = Dark.Muted,
    onSurfaceVariant = Dark.MutedForeground,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Dark.Background,
    surfaceContainerLow = Dark.Card,
    surfaceContainer = Dark.Muted,
    surfaceContainerHigh = Dark.Popover,
    surfaceContainerHighest = Dark.Border,
    outline = Dark.Control,
    outlineVariant = Dark.Border,
    error = Brand.RedDark,
    onError = Dark.Background,
)

/**
 * Every paragraph takes its direction from its own words (TextDirection.Content), not from the UI
 * language: a Hebrew transcript, name or summary reads right to left with its punctuation in place
 * even when the app runs in English, and English text stays left to right in a Hebrew UI.
 */
private fun style(size: Int, weight: FontWeight = FontWeight.Normal, line: Float = 1.45f, tracking: Float = -0.015f) =
    TextStyle(
        fontFamily = Rubik,
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = (size * line).sp,
        letterSpacing = tracking.em,
        textDirection = TextDirection.Content,
    )

/** Desktop scale: 14px body, tight headings (-0.035em), 11px uppercase eyebrow. */
private val DibeTypography = Typography(
    displaySmall = style(32, FontWeight.SemiBold, 1.1f, -0.035f),
    headlineMedium = style(28, FontWeight.SemiBold, 1.1f, -0.035f),
    headlineSmall = style(22, FontWeight.SemiBold, 1.2f, -0.035f),
    titleLarge = style(20, FontWeight.SemiBold, 1.25f, -0.025f),
    titleMedium = style(16, FontWeight.SemiBold, 1.3f),
    titleSmall = style(14, FontWeight.SemiBold, 1.3f),
    bodyLarge = style(16, line = 1.55f),
    bodyMedium = style(14, line = 1.55f),
    bodySmall = style(12, line = 1.45f),
    labelLarge = style(14, FontWeight.SemiBold, 1.2f),
    labelMedium = style(12, FontWeight.Medium, 1.2f),
    labelSmall = style(11, FontWeight.Medium, 1.2f, 0.08f),
)

/** Desktop radius scale: 12 / 14 / 16 / 20 / 24. */
private val DibeShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun DibeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDibeColors provides if (dark) DarkDibeColors else LightDibeColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = DibeTypography,
            shapes = DibeShapes,
            content = content,
        )
    }
}
