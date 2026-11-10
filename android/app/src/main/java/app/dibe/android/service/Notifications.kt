package app.dibe.android.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.dibe.android.MainActivity
import app.dibe.android.R
import app.dibe.android.data.TranscriptEntry

/** Same key as MainActivity.EXTRA_OPEN_ID: it opens this saved transcript. */
private const val EXTRA_OPEN_ID = "app.dibe.android.extra.OPEN_ID"

internal const val ONGOING_ID = 1
private const val CHANNEL = "work"

internal class Notifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.notification_channel))
                .build(),
        )
    }

    /** The foreground notification: the recording when there is one, else the file job. */
    fun ongoing(recording: RecordingState, file: FileJobState?): Notification {
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(null))
        val fileActive = file != null && file.phase in ACTIVE_FILE_PHASES
        when {
            recording.phase == RecordingPhase.STARTING || recording.phase == RecordingPhase.RECORDING -> b
                .setContentTitle(context.getString(R.string.notification_recording))
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() - recording.elapsedMs)
                // The app is often out of sight exactly when another app takes the mic; say it here.
                .apply { if (recording.warning == Problem.MIC_IN_USE) setContentText(context.getString(R.string.ui_problem_mic_in_use)) }
                .addAction(0, context.getString(R.string.action_pause), action(DibeService.ACTION_PAUSE))
                .addAction(0, context.getString(R.string.action_stop), action(DibeService.ACTION_STOP))
            recording.phase == RecordingPhase.PAUSED -> b
                .setContentTitle(context.getString(R.string.notification_paused))
                .setContentText(DateUtils.formatElapsedTime(recording.elapsedMs / 1000))
                .addAction(0, context.getString(R.string.action_resume), action(DibeService.ACTION_RESUME))
                .addAction(0, context.getString(R.string.action_stop), action(DibeService.ACTION_STOP))
            recording.phase == RecordingPhase.FINISHING -> b
                .setContentTitle(context.getString(R.string.notification_finishing))
                .setProgress(100, recording.progress ?: 0, recording.progress == null)
            fileActive -> b
                .setContentTitle(file.name)
                .setContentText(
                    context.getString(
                        if (file.phase == FilePhase.DECODING) R.string.notification_decoding else R.string.notification_transcribing,
                    ),
                )
                .setProgress(100, file.progress, false)
                .addAction(0, context.getString(R.string.action_cancel), action(DibeService.ACTION_CANCEL_FILE))
            else -> b.setContentTitle(context.getString(R.string.notification_finishing)).setProgress(0, 0, true)
        }
        return b.build()
    }

    fun update(notification: Notification) = post(ONGOING_ID, notification)

    fun ready(entry: TranscriptEntry) = post(
        entry.id.hashCode(),
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_ready))
            .setContentText(entry.name)
            .setAutoCancel(true)
            .setContentIntent(openApp(entry.id))
            .build(),
    )

    /** Without POST_NOTIFICATIONS nothing shows; the work goes on regardless. */
    private fun post(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.notify(id, notification)
    }

    private fun openApp(id: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (id != null) intent.putExtra(EXTRA_OPEN_ID, id)
        // A request code per transcript, or every "ready" notification would open the latest one.
        return PendingIntent.getActivity(
            context,
            id?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        context,
        0,
        Intent(context, DibeService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE,
    )
}

internal val ACTIVE_FILE_PHASES = setOf(FilePhase.DECODING, FilePhase.TRANSCRIBING)
