package app.dibe.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
    primary = Brand.GreenDark,
    onPrimary = Light.Background,
    primaryContainer = Light.GreenTint,
    onPrimaryContainer = Brand.GreenDeep,
    secondary = Brand.Green,
    onSecondary = Light.Background,
    secondaryContainer = Light.Muted,
    onSecondaryContainer = Light.Foreground,
    tertiary = Brand.Orange,
    background = Light.Background,
    onBackground = Light.Foreground,
    surface = Light.Background,
    onSurface = Light.Foreground,
    surfaceVariant = Light.Muted,
    onSurfaceVariant = Light.MutedForeground,
    surfaceContainerLowest = Light.Background,
    surfaceContainerLow = Light.Sidebar,
    surfaceContainer = Light.Muted,
    surfaceContainerHigh = Light.Muted,
    surfaceContainerHighest = Light.Border,
    // M3 draws the border and thumb of an off switch, and outlined fields, in `outline`: it needs 3:1
    // on white. Decorative hairlines use outlineVariant (the desktop border).
    outline = Light.Control,
    outlineVariant = Light.Border,
    error = Brand.Red,
    onError = Light.Background,
)

private val DarkScheme = darkColorScheme(
    primary = Brand.Green,
    onPrimary = Brand.Night,
    primaryContainer = Dark.GreenTint,
    onPrimaryContainer = Brand.GreenSoft,
    secondary = Brand.GreenSoft,
    onSecondary = Brand.Night,
    secondaryContainer = Dark.Muted,
    onSecondaryContainer = Dark.Foreground,
    tertiary = Brand.Orange,
    background = Dark.Background,
    onBackground = Dark.Foreground,
    surface = Dark.Background,
    onSurface = Dark.Foreground,
    surfaceVariant = Dark.Muted,
    onSurfaceVariant = Dark.MutedForeground,
    surfaceContainerLowest = Dark.Background,
    surfaceContainerLow = Dark.Card,
    surfaceContainer = Dark.Card,
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
private fun style(size: Int, weight: FontWeight = FontWeight.Normal, line: Float = 1.45f, tracking: Float = -0.01f) =
    TextStyle(
        fontFamily = Rubik,
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = (size * line).sp,
        letterSpacing = tracking.em,
        textDirection = TextDirection.Content,
    )

/** Desktop scale: 14px body, tight headings (-0.03em), 11px uppercase eyebrow. */
private val DibeTypography = Typography(
    displaySmall = style(32, FontWeight.SemiBold, 1.1f, -0.03f),
    headlineMedium = style(28, FontWeight.SemiBold, 1.1f, -0.03f),
    headlineSmall = style(22, FontWeight.SemiBold, 1.2f, -0.03f),
    titleLarge = style(20, FontWeight.SemiBold, 1.25f, -0.02f),
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
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = DibeTypography,
        shapes = DibeShapes,
        content = content,
    )
}
