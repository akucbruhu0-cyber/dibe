package app.dibe.android.service

import app.dibe.android.data.Segment

enum class AudioSource { MIC, SYSTEM, BOTH }

enum class RecordingPhase { IDLE, STARTING, RECORDING, PAUSED, FINISHING }

/** Problems the UI must explain; each maps to a localized string. */
enum class Problem {
    MIC_PERMISSION,
    NOTIFICATION_PERMISSION,
    PROJECTION_DENIED,
    /** The other app forbids capture (or nothing is playing): only silence for a while. */
    CAPTURE_SILENT,
    NO_MODEL,
    LOW_MEMORY,
    MODEL_NOT_LIVE,
    UNSUPPORTED_FORMAT,
    CORRUPT_FILE,
    ENGINE_FAILED,
    NO_SPACE,
    /** The audio input did not start or broke off (e.g. the audio server died); what came before it is kept. */
    CAPTURE_FAILED,
    /**
     * Another app holds the microphone (e.g. the camera recording video): Android gives it the input
     * and DIBE gets silence until it lets go. The recording goes on.
     */
    MIC_IN_USE,
}

data class RecordingState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val source: AudioSource = AudioSource.MIC,
    val elapsedMs: Long = 0,
    /** 0..1 RMS of the last buffer, for the level meter. */
    val level: Float = 0f,
    val finals: List<Segment> = emptyList(),
    val partial: String? = null,
    /** 0..100 while FINISHING transcribes the saved audio (live was off or incomplete); null otherwise. */
    val progress: Int? = null,
    /** Shown but recording continues (e.g. CAPTURE_SILENT); with [savedId], CAPTURE_FAILED or NO_SPACE for a recording cut short. */
    val warning: Problem? = null,
    /** Set once the recording is saved; the UI opens it. */
    val savedId: String? = null,
    val error: Problem? = null,
    val errorDetail: String? = null,
)

enum class FilePhase { DECODING, TRANSCRIBING, DONE, FAILED, CANCELLED }

data class FileJobState(
    val name: String,
    val phase: FilePhase,
    /** 0..100 */
    val progress: Int = 0,
    val segments: List<Segment> = emptyList(),
    val savedId: String? = null,
    val error: Problem? = null,
    val errorDetail: String? = null,
    /** Set when the job fills an existing transcript (e.g. a recovered recording) instead of a new one. */
    val targetId: String? = null,
)
