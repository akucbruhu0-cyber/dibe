package app.dibe.android

import android.app.Application
import app.dibe.android.ai.AiClient
import app.dibe.android.data.TranscriptStore
import app.dibe.android.engine.TranscriptionEngine
import app.dibe.android.models.DeviceInfo
import app.dibe.android.models.ModelRepository
import app.dibe.android.service.Sessions
import app.dibe.android.settings.SettingsStore
import java.io.File

/** Manual DI: one instance of each, created on first use. */
class DibeApp : Application() {
    val device by lazy { DeviceInfo(this) }
    val models by lazy { ModelRepository(this, device) }
    val engine by lazy { TranscriptionEngine() }
    val store by lazy { TranscriptStore(File(filesDir, "transcripts")) }
    val settings by lazy { SettingsStore(this) }
    val ai by lazy { AiClient() }
    val sessions by lazy { Sessions(this) }
}

val android.content.Context.app: DibeApp get() = applicationContext as DibeApp
