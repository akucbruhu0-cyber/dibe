package app.dibe.android.models

enum class Tier { LIGHT, MEDIUM, HEAVY }

enum class EngineKind { WHISPER, PARAKEET }

data class ModelSpec(
    val id: String,
    val name: String,
    val tier: Tier,
    val engine: EngineKind,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    /** Null when the host publishes none; then only size and magic bytes are checked. */
    val sha256: String?,
    val minRamGb: Int,
    /** False for models slower than real time on a phone: file transcription only. */
    val live: Boolean,
    /** Shown as the badge "recommended for Hebrew". */
    val hebrew: Boolean = false,
)

sealed interface ModelStatus {
    data object NotInstalled : ModelStatus
    /** [progress] in 0..1, or null while the size is unknown. */
    data class Downloading(val progress: Float?, val downloadedBytes: Long) : ModelStatus
    /** DownloadManager waits for network or retries; resumes on its own. */
    data class Waiting(val downloadedBytes: Long) : ModelStatus
    data object Verifying : ModelStatus
    data object Installed : ModelStatus
    data class Failed(val reason: ModelProblem) : ModelStatus
}

enum class ModelProblem { NO_SPACE, NETWORK, CORRUPT, UNKNOWN }

/** How a model fits this phone, from total RAM against [ModelSpec.minRamGb]. */
enum class Fit { GOOD, TIGHT, TOO_HEAVY }
