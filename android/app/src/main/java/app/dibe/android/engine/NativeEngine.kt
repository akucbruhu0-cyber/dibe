package app.dibe.android.engine

import android.util.Log
import java.io.File
import java.io.IOException

/** What [TranscriptionEngine] throws on a phone [NativeEngine] has no build for. */
internal const val UNSUPPORTED_CPU = "This phone's processor is not supported (needs ARMv8.2 dot-product)"

/**
 * JNI surface of `server/crates/dibe-jni` (libdibe.so). Every call blocks; callers run them on
 * [TranscriptionEngine]'s single engine thread because a loaded model is not thread-safe.
 * Failures throw [RuntimeException] with the Rust error chain as the message.
 * Times are centiseconds for segments (as in the desktop `Segment`) and seconds for live events.
 * Calling any of it while [supported] is false throws [UnsatisfiedLinkError].
 */
internal object NativeEngine {
    /** False when neither build of the engine runs on this CPU; nothing is loaded then. */
    val supported: Boolean

    init {
        val features = try {
            cpuFeatures(File("/proc/cpuinfo").readText())
        } catch (e: IOException) {
            Log.w("NativeEngine", "/proc/cpuinfo is unreadable", e)
            emptySet()
        } catch (e: SecurityException) {
            Log.w("NativeEngine", "/proc/cpuinfo is unreadable", e)
            emptySet()
        }
        val library = engineLibrary(features)
        library?.let(System::loadLibrary)
        supported = library != null
    }

    /** Capabilities JSON of a model file without loading it, the desktop `EngineCapabilities` shape. */
    external fun metadata(modelPath: String): String

    /** Loads the model (and the VAD for engines that need it). Returns an opaque handle. */
    external fun load(modelPath: String, vadPath: String): Long

    external fun unload(handle: Long)

    /** JSON array of `{"start":cs,"stop":cs,"text":"..."}`. Reads [pcm16k] in place: nothing may write it meanwhile. */
    external fun transcribe(handle: Long, pcm16k: FloatArray, language: String, callbacks: NativeCallbacks): String

    /** A resampler of mono PCM at [fromRate] to mono 16 kHz, continuous from one push to the next. */
    external fun resamplerNew(fromRate: Int): Long

    /** The output [pcm] completes. */
    external fun resamplerPush(resampler: Long, pcm: FloatArray): FloatArray

    /** The rest of the output; frees the resampler, also when it throws. */
    external fun resamplerFinish(resampler: Long): FloatArray

    external fun resamplerDrop(resampler: Long)

    /** Starts a `LiveCore` session on the loaded model. Returns a live handle. */
    external fun liveStart(handle: Long, language: String, partials: Boolean): Long

    /** Appends 16 kHz mono PCM; cheap, no inference. */
    external fun livePush(live: Long, pcm16k: FloatArray)

    /** Runs `LiveCore::advance`; JSON array of desktop `LiveEvent`s (`{"type":"final"|"partial"|"warning",...}`). */
    external fun liveAdvance(handle: Long, live: Long): String

    /** Runs `LiveCore::finish` and frees the live handle; same JSON as [liveAdvance]. */
    external fun liveFinish(handle: Long, live: Long): String

    /** Frees a live handle without finishing it. */
    external fun liveDrop(live: Long)
}

/**
 * The build of the engine this CPU runs (see scripts/android/build-native.sh), null for none: an
 * instruction the CPU lacks is a SIGILL, which kills the app past any catch.
 */
internal fun engineLibrary(features: Set<String>): String? = when {
    "i8mm" in features && "bf16" in features -> "dibe"
    "asimddp" in features -> "dibe_compat"
    else -> null
}

/** The features every core lists in /proc/cpuinfo: a thread may run on any of them. */
internal fun cpuFeatures(cpuinfo: String): Set<String> = cpuinfo.lineSequence()
    .filter { it.startsWith("Features") }
    .map { it.substringAfter(':').trim().split(Regex("\\s+")).toSet() }
    .reduceOrNull { all, core -> all intersect core }
    .orEmpty()

/** Called from the engine thread during [NativeEngine.transcribe]. */
interface NativeCallbacks {
    fun onProgress(percent: Int)
    fun onSegment(startCs: Long, stopCs: Long, text: String)
    fun shouldAbort(): Boolean
}
