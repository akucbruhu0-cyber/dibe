package app.dibe.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Desktop neutrals (desktop/src/globals.css) with the logo/promo greens in place of the
 * desktop's blue primary (promo-video/src/theme.ts).
 */
object Brand {
    val Green = Color(0xFF3CB54A)
    /** Primary on white: AA contrast for text and filled buttons. */
    val GreenDark = Color(0xFF145A1E)
    val GreenDeep = Color(0xFF0D4516)
    val GreenSoft = Color(0xFF9BE3A4)
    val Orange = Color(0xFFFFB347)
    /** Onboarding animation backgrounds (fixed, the videos are opaque). */
    val Night = Color(0xFF040A07)
    val Night2 = Color(0xFF0C2616)
    val Red = Color(0xFFB4453C)
    val RedDark = Color(0xFFC4655C)
}

object Light {
    val Background = Color(0xFFFFFFFF)
    val Foreground = Color(0xFF1A1C1F)
    val Muted = Color(0xFFF4F4F4)
    val MutedForeground = Color(0xFF6E6E6E)
    val Border = Color(0xFFE6E6E6)
    /** Controls' outlines (off switch, field border): 3.3:1 on white. */
    val Control = Color(0xFF8E8E8E)
    val Sidebar = Color(0xFFF9F9F9)
    val GreenTint = Color(0xFFEAF6EC)
}

object Dark {
    val Background = Color(0xFF181818)
    val Card = Color(0xFF212121)
    val Popover = Color(0xFF2A2A2A)
    val Foreground = Color(0xFFECECEC)
    val Muted = Color(0xFF242424)
    val MutedForeground = Color(0xFF9A9A9A)
    val Border = Color(0xFF303030)
    val Control = Color(0xFF8A8A8A)
    val GreenTint = Color(0xFF15301A)
}
