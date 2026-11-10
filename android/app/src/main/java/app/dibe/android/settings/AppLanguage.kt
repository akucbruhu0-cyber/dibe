package app.dibe.android.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import app.dibe.android.app
import kotlinx.serialization.Serializable
import java.util.Locale

/** The UI language. Tags match res/xml/locales_config.xml. */
@Serializable
enum class AppLanguage(val tag: String) { SYSTEM(""), ENGLISH("en"), HEBREW("iw") }

/**
 * Android 13+ keeps the per-app locale itself (also editable from the system's app settings);
 * older releases read [AppSettings.appLanguage], applied by [withAppLanguage] on each activity start.
 */
fun Context.appLanguage(): AppLanguage {
    if (Build.VERSION.SDK_INT < 33) return app.settings.settings.value.appLanguage
    return when (getSystemService(LocaleManager::class.java).applicationLocales[0]?.language) {
        "he", "iw" -> AppLanguage.HEBREW
        "en" -> AppLanguage.ENGLISH
        else -> AppLanguage.SYSTEM
    }
}

/** Android 13+ recreates the activity itself; older releases are recreated here to re-run [withAppLanguage]. */
fun Activity.setAppLanguage(language: AppLanguage) {
    if (Build.VERSION.SDK_INT >= 33) {
        getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
    } else {
        app.settings.update { it.copy(appLanguage = language) }
        recreate()
    }
}

/**
 * API 29–32: the activity's context in the chosen language (its layout direction follows the locale).
 * ponytail: only activity resources switch; the service's notifications keep the phone's language there.
 */
fun Context.withAppLanguage(language: AppLanguage): Context {
    if (Build.VERSION.SDK_INT >= 33) return this
    // Language names and sort order come from the default locale: keep it in step with the UI.
    val locale = if (language == AppLanguage.SYSTEM) Resources.getSystem().configuration.locales[0] else Locale.forLanguageTag(language.tag)
    Locale.setDefault(locale)
    if (language == AppLanguage.SYSTEM) return this
    return createConfigurationContext(Configuration(resources.configuration).apply { setLocale(locale) })
}
