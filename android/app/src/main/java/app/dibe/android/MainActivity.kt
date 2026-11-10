package app.dibe.android

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dibe.android.settings.ThemeMode
import app.dibe.android.settings.withAppLanguage
import app.dibe.android.ui.AppRequest
import app.dibe.android.ui.AppRoot
import app.dibe.android.ui.theme.DibeTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
    private val requests = Channel<AppRequest>(Channel.BUFFERED)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLanguage(newBase.app.settings.settings.value.appLanguage))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity already acted on its intent.
        if (savedInstanceState == null) handle(intent)
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // The in-app theme can differ from the system's: the bar icons follow the app.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            DibeTheme(dark = dark) { AppRoot(requests.receiveAsFlow()) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        // Recents relaunches the task with the intent it was started by, which was already acted on.
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return
        intent.getStringExtra(EXTRA_OPEN_ID)?.let {
            requests.trySend(AppRequest.OpenTranscript(it))
            return
        }
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        if (!isForeignContent(uri.scheme, uri.authority, packageName)) return
        requests.trySend(if (app.sessions.transcribeFile(uri)) AppRequest.ShowHome else AppRequest.FileBusy)
    }

    companion object {
        /** Opens a saved transcript by id (used by the "done" notification). */
        const val EXTRA_OPEN_ID = "app.dibe.android.extra.OPEN_ID"
    }
}

/**
 * Only another app's content: the activity is exported, so a file:// path or one of this app's own
 * providers would have it read its private files on a stranger's behalf.
 */
internal fun isForeignContent(scheme: String?, authority: String?, packageName: String): Boolean =
    scheme == "content" && authority != null &&
        !authority.equals(packageName, ignoreCase = true) && !authority.startsWith("$packageName.", ignoreCase = true)
