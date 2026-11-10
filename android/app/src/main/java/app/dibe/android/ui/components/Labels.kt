package app.dibe.android.ui.components

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import app.dibe.android.R
import app.dibe.android.models.Fit
import app.dibe.android.models.Tier
import java.util.Locale

/** The desktop's 11sp uppercase muted label above a title. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), modifier, color = color, style = MaterialTheme.typography.labelSmall)
}

/** Timers and timestamps: monospace so the digits don't jitter as they tick (desktop `font-mono`). */
val ClockFamily = FontFamily.Monospace

/** `mm:ss`, or `h:mm:ss` from an hour on. */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val (h, m, s) = Triple(total / 3600, total % 3600 / 60, total % 60)
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%02d:%02d".format(Locale.ROOT, m, s)
}

@Composable
fun formatSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

@StringRes
fun Tier.label(): Int = when (this) {
    Tier.LIGHT -> R.string.ui_tier_light
    Tier.MEDIUM -> R.string.ui_tier_medium
    Tier.HEAVY -> R.string.ui_tier_heavy
}

@StringRes
fun Fit.label(): Int = when (this) {
    Fit.GOOD -> R.string.ui_fit_good
    Fit.TIGHT -> R.string.ui_fit_tight
    Fit.TOO_HEAVY -> R.string.ui_fit_too_heavy
}

/** Android still reports some ISO 639 codes by their old names; models use the new ones. */
fun normalizeLanguage(code: String): String = when (code) {
    "iw" -> "he"
    "in" -> "id"
    "ji" -> "yi"
    else -> code
}

/** "auto" → the given label; otherwise the language's name in the UI language. */
fun languageName(code: String, auto: String): String {
    if (code == "auto") return auto
    val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
    return if (name.isBlank() || name == code) code else name.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}
