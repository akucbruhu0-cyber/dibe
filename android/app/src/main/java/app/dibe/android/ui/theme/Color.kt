package app.dibe.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Pocket-style glass in light pastel green (plans/pocket-glass/DESIGN.md): a mint shell, white
 * floating cards, a pastel-green fill with a deeper green for text, and green-tinted neutrals.
 */
object Brand {
    /** Live dot: reads as "on" against white and the shell. */
    val Green = Color(0xFF3CB54A)
    val Orange = Color(0xFFFFB347)
    /** Onboarding animation backgrounds (fixed, the videos are opaque). */
    val Night = Color(0xFF040A07)
    val Night2 = Color(0xFF0C2616)
    val Red = Color(0xFFB4453C)
    val RedDark = Color(0xFFC4655C)
}

object Light {
    val ShellFrom = Color(0xFFFFFFFF)
    val ShellTo = Color(0xFFFFFFFF)
    val Background = Color(0xFFEEF7F0)
    val Foreground = Color(0xFF16201A)
    val Card = Color(0xFFFFFFFF)
    /** The pastel green: a fill, never text on white. Lighter than the desktop's #BFE9CB: on a phone the fills are
     *  whole-width, so the same tone reads heavy. [OnPrimary] on it is 7.8:1 (WCAG AAA for body text). */
    val Primary = Color(0xFFDCF4E3)
    val OnPrimary = Color(0xFF1D5233)
    /** Links, rings, focus, small icons. */
    val PrimaryStrong = Color(0xFF26794A)
    val Muted = Color(0xFFF0F6F1)
    val MutedForeground = Color(0xFF627067)
    val Border = Color(0xFFDCE8DF)
    /** Controls' outlines (off switch, field border): 3:1 on white. */
    val Control = Color(0xFF84918A)
    val Sidebar = Color(0xFFE3F0E5)
    val PillDark = Color(0xFF1C2721)
}

object Dark {
    val ShellFrom = Color(0xFF0E1711)
    val ShellTo = Color(0xFF121A10)
    val Background = Color(0xFF0F1712)
    val Foreground = Color(0xFFE6F0E8)
    val Card = Color(0xFF18221B)
    val Popover = Color(0xFF202C23)
    val Primary = Color(0xFFA9DDB8)
    val OnPrimary = Color(0xFF0E2A18)
    val PrimaryStrong = Color(0xFF8FD3A6)
    val Muted = Color(0xFF1B261E)
    val MutedForeground = Color(0xFF93A397)
    val Border = Color(0xFF26332A)
    val Control = Color(0xFF7E8C82)
    val Sidebar = Color(0xFF121B15)
}

/** The tokens Material's scheme has no slot for: shell, glass, hairline shadow, dark pill, glow. */
@Immutable
data class DibeColors(
    val shellFrom: Color,
    val shellTo: Color,
    val glass: Color,
    val glassStrong: Color,
    val glassBorder: Color,
    /** The 1px ring of the hairline shadow. */
    val hairline: Color,
    /** Tint of the soft drop shadow under floating cards. */
    val shadow: Color,
    /** Primary CTA pill (dark in light mode, light in dark mode). */
    val pillDark: Color,
    val onPillDark: Color,
    /** One period of the sliding glow, repeated twice so a shift by one width is seamless. */
    val glow: List<Color>,
    val glowAlpha: Float,
    /** The mint ring of white secondary pills: 1.39:1 on white, enough beside a 9:1 label (WCAG 1.4.11). */
    val pillRing: Color,
    /** AI text while it writes: deeper hues, legible on the card. */
    val shimmer: List<Color>,
)

/** Pocket's glow stops: blue, violet, magenta, coral, amber. */
private val GlowHues = listOf(Color(0xFF528CF3), Color(0xFF8676E5), Color(0xFFC45ABE), Color(0xFFEB4B57), Color(0xFFE49655))

val LightDibeColors = DibeColors(
    shellFrom = Light.ShellFrom,
    shellTo = Light.ShellTo,
    glass = Color.White.copy(alpha = 0.72f),
    glassStrong = Color.White.copy(alpha = 0.90f),
    glassBorder = Color.White.copy(alpha = 0.65f),
    hairline = Color(0xFF163C24).copy(alpha = 0.07f),
    shadow = Color(0xFF163C24),
    pillDark = Light.PillDark,
    onPillDark = Color.White,
    glow = GlowHues + GlowHues + GlowHues.first(),
    glowAlpha = 0.9f,
    pillRing = Color(0xFFBFE3CB),
    shimmer = listOf(Color(0xFF2E8A55), Color(0xFF5DB57E), Color(0xFF8CC66A), Color(0xFF3FA58F), Color(0xFF2E8A55)),
)

val DarkDibeColors = DibeColors(
    shellFrom = Dark.ShellFrom,
    shellTo = Dark.ShellTo,
    glass = Color(0xFF1A261E).copy(alpha = 0.70f),
    glassStrong = Color(0xFF1A261E).copy(alpha = 0.90f),
    glassBorder = Color.White.copy(alpha = 0.08f),
    hairline = Color.White.copy(alpha = 0.06f),
    shadow = Color.Black,
    pillDark = Dark.Foreground,
    onPillDark = Dark.Background,
    glow = GlowHues + GlowHues + GlowHues.first(),
    glowAlpha = 0.45f,
    pillRing = Color(0xFF2E4A37),
    shimmer = listOf(Color(0xFF8FD3A6), Color(0xFFB5E6A0), Color(0xFFC9EBA0), Color(0xFF8ED8C6), Color(0xFF8FD3A6)),
)

val LocalDibeColors = staticCompositionLocalOf { LightDibeColors }
