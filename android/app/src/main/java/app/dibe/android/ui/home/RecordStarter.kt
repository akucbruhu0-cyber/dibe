package app.dibe.android.ui.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.dibe.android.R
import app.dibe.android.service.AudioSource
import app.dibe.android.ui.components.openAppSettings

private enum class Ask { MIC_RATIONALE, MIC_BLOCKED, NOTIFICATIONS }

/** Asked once ever: a "no" here is respected, and recording works without it. */
private const val PREFS = "ui"
private const val NOTIFICATIONS_ASKED = "notifications_asked"

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Walks a recording start through what it needs, in order: the microphone (with a rationale, or
 * the way to app settings once it is blocked), notifications on 13+ (explained, never required),
 * then the screen-capture consent for system audio. Call the returned function with the source.
 */
@Composable
fun rememberRecordStarter(onStart: (AudioSource, Intent?) -> Unit, onProjectionDenied: () -> Unit): (AudioSource) -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val start by rememberUpdatedState(onStart)
    val denied by rememberUpdatedState(onProjectionDenied)
    var ask by rememberSaveable { mutableStateOf<Ask?>(null) }
    var source by rememberSaveable { mutableStateOf(AudioSource.MIC) }
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) start(source, data) else denied()
    }

    fun capture() {
        if (source == AudioSource.MIC) return start(source, null)
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        // 14+: skip the "one app or entire screen" choice; audio capture is the whole device anyway.
        projection.launch(
            if (Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            },
        )
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { capture() }

    fun afterMic() {
        val needsAsk = Build.VERSION.SDK_INT >= 33 &&
            !context.granted(Manifest.permission.POST_NOTIFICATIONS) &&
            !prefs.getBoolean(NOTIFICATIONS_ASKED, false)
        if (needsAsk) ask = Ask.NOTIFICATIONS else capture()
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> afterMic()
            // Denied without a rationale to show: "don't ask again", only settings can change it.
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false -> ask = Ask.MIC_BLOCKED
        }
    }

    when (ask) {
        Ask.MIC_RATIONALE -> PermissionDialog(
            title = R.string.perm_mic_title,
            body = R.string.perm_mic_body,
            confirm = R.string.perm_allow,
            onConfirm = {
                ask = null
                mic.launch(Manifest.permission.RECORD_AUDIO)
            },
            onDismiss = { ask = null },
        )
        Ask.MIC_BLOCKED -> PermissionDialog(
            title = R.string.perm_mic_title,
            body = R.string.perm_mic_blocked_body,
            confirm = R.string.ui_action_open_settings,
            onConfirm = {
                ask = null
                context.openAppSettings()
            },
            onDismiss = { ask = null },
        )
        Ask.NOTIFICATIONS -> PermissionDialog(
            title = R.string.perm_notifications_title,
            body = R.string.perm_notifications_body,
            confirm = R.string.perm_continue,
            onConfirm = {
                ask = null
                prefs.edit { putBoolean(NOTIFICATIONS_ASKED, true) }
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            // Dismissing still records: notifications are a convenience.
            onDismiss = {
                ask = null
                prefs.edit { putBoolean(NOTIFICATIONS_ASKED, true) }
                capture()
            },
        )
        null -> Unit
    }

    return { chosen ->
        source = chosen
        when {
            context.granted(Manifest.permission.RECORD_AUDIO) -> afterMic()
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true -> ask = Ask.MIC_RATIONALE
            else -> mic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
private fun PermissionDialog(title: Int, body: Int, confirm: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onConfirm) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.perm_not_now)) } },
    )
}
