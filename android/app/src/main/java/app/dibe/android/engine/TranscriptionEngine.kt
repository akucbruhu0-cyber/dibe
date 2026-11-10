package app.dibe.android.engine

import app.dibe.android.data.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/** What the loaded model can do: the desktop `EngineCapabilities`. */
@kotlinx.serialization.Serializable
data class EngineCapabilities(
    val engine: String,
    @kotlinx.serialization.SerialName("requires_vad") val requiresVad: Boolean = false,
    val languages: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("language_detection") val languageDetection: Boolean = false,
    val streaming: Boolean = false,
    val translation: Boolean = false,
    val timestamps: Boolean = true,
    @kotlinx.serialization.SerialName("text_prompts") val textPrompts: Boolean = false,
)

sealed interface LiveEvent {
    data class Final(val start: Double, val end: Double, val text: String) : LiveEvent
    /** Replaces the current partial; empty text clears it. */
    data class Partial(val start: Double, val end: Double, val text: String) : LiveEvent
    data class Warning(val code: String, val message: String) : LiveEvent
}

/** One live session; not thread-safe, owned by the recording pipeline. */
interface LiveSession {
    /** Queue audio; returns at once. */
    fun push(pcm16k: FloatArray)
    /** Inference on the engine thread. */
    suspend fun advance(): List<LiveEvent>
    suspend fun finish(): List<LiveEvent>
    fun cancel()
}

/**
 * The one engine of the app. All native calls run on one dedicated thread, and every call that
 * needs a model loads it there in the same step, so two callers with different models never see
 * each other's. Loading a different model unloads the current one first (one model in RAM at a time).
 * On a CPU no build of the engine runs on, every call throws [RuntimeException] ([UNSUPPORTED_CPU]).
 */
class TranscriptionEngine {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "dibe-engine") }
    private val engineThread = executor.asCoroutineDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    // Touched on the engine thread only.
    private var handle = 0L
    private var loadedVad: String? = null
    private var live: Session? = null

    private val _loadedModel = MutableStateFlow<String?>(null)
    private val _capabilities = MutableStateFlow<EngineCapabilities?>(null)

    /** Path of the loaded model, null when none. */
    val loadedModel: StateFlow<String?> = _loadedModel.asStateFlow()
    val capabilities: StateFlow<EngineCapabilities?> = _capabilities.asStateFlow()

    // Reads a header only, so it does not queue behind a transcription on the engine thread.
    suspend fun metadata(model: File): EngineCapabilities = withContext(Dispatchers.IO) {
        checkSupported()
        json.decodeFromString(NativeEngine.metadata(model.path))
    }

    suspend fun unload(): Unit = withContext(engineThread) { release() }

    /**
     * Transcribes 16 kHz mono PCM with [model], loaded first if it is not. Cancelling the calling
     * coroutine aborts the native work. [onProgress] gets 0..100, [onSegment] each segment as it is
     * finished; both run on the engine thread. [pcm16k] must not change until this returns.
     */
    suspend fun transcribe(
        model: File,
        vad: File,
        pcm16k: FloatArray,
        language: String,
        onProgress: (Int) -> Unit,
        onSegment: (Segment) -> Unit,
    ): List<Segment> = withContext(engineThread) {
        val handle = load(model, vad)
        val context = currentCoroutineContext()
        val segments = try {
            NativeEngine.transcribe(handle, pcm16k, language, Callbacks(onProgress, onSegment, context))
        } catch (e: RuntimeException) {
            // The engine's "aborted" error is how a cancellation comes back: report it as one.
            context.ensureActive()
            throw e
        }
        json.decodeFromString<List<Segment>>(segments).filter { it.text.isSpoken() }
    }

    /**
     * A live session on [model], loaded first if it is not. Replaces a live session still open, and
     * loading another model ends this one: their later calls fail.
     */
    suspend fun startLive(model: File, vad: File, language: String, partials: Boolean): LiveSession = withContext(engineThread) {
        val handle = load(model, vad)
        live?.close()
        Session(handle, NativeEngine.liveStart(handle, language, partials)).also { live = it }
    }

    /** On the engine thread. Returns the handle; no-op when [model] is already loaded. */
    private fun load(model: File, vad: File): Long {
        checkSupported()
        if (handle != 0L && _loadedModel.value == model.path && loadedVad == vad.path) return handle
        // Read before anything is unloaded, so a file that is no model leaves the current one in place.
        val capabilities = json.decodeFromString<EngineCapabilities>(NativeEngine.metadata(model.path))
        release()
        handle = NativeEngine.load(model.path, vad.path)
        loadedVad = vad.path
        _capabilities.value = capabilities
        _loadedModel.value = model.path
        return handle
    }

    private fun release() {
        live?.close()
        val loaded = handle
        handle = 0L
        loadedVad = null
        _loadedModel.value = null
        _capabilities.value = null
        if (loaded != 0L) NativeEngine.unload(loaded)
    }

    private fun events(json: String): List<LiveEvent> =
        this.json.decodeFromString<List<LiveEventJson>>(json).mapNotNull { event ->
            when (event.type) {
                "final" -> LiveEvent.Final(event.start, event.end, event.text).takeIf { event.text.isSpoken() }
                // A partial with no words clears the current one rather than showing a lone ".".
                "partial" -> LiveEvent.Partial(event.start, event.end, event.text.takeIf { it.isSpoken() }.orEmpty())
                "warning" -> LiveEvent.Warning(event.code, event.message)
                else -> null
            }
        }

    private class Callbacks(
        private val progress: (Int) -> Unit,
        private val segment: (Segment) -> Unit,
        private val context: CoroutineContext,
    ) : NativeCallbacks {
        override fun onProgress(percent: Int) = progress(percent)
        override fun onSegment(startCs: Long, stopCs: Long, text: String) {
            if (text.isSpoken()) segment(Segment(startCs, stopCs, text))
        }
        override fun shouldAbort() = !context.isActive
    }

    /** The desktop `LiveEvent` serde shape: `{"type":"final"|"partial"|"warning", ...}`. */
    @Serializable
    private data class LiveEventJson(
        val type: String,
        val start: Double = 0.0,
        val end: Double = 0.0,
        val text: String = "",
        val code: String = "",
        val message: String = "",
    )

    /**
     * Audio is queued here and handed to the native session on the engine thread, so a push never waits
     * for an inference in progress and never races one.
     */
    private inner class Session(private val handle: Long, private var native: Long) : LiveSession {
        private val queued = ArrayList<FloatArray>()

        override fun push(pcm16k: FloatArray) {
            val copy = pcm16k.copyOf()
            synchronized(queued) { queued += copy }
        }

        override suspend fun advance(): List<LiveEvent> = withContext(engineThread) {
            events(NativeEngine.liveAdvance(handle, flush()))
        }

        override suspend fun finish(): List<LiveEvent> = withContext(engineThread) {
            val session = flush()
            forget()
            // liveFinish frees the session even when it throws.
            events(NativeEngine.liveFinish(handle, session))
        }

        override fun cancel() {
            executor.execute(::close)
        }

        fun close() {
            if (native != 0L) NativeEngine.liveDrop(native)
            forget()
        }

        private fun forget() {
            native = 0L
            if (live === this) live = null
        }

        private fun flush(): Long {
            check(native != 0L) { "live session closed" }
            val audio = synchronized(queued) { queued.toList().also { queued.clear() } }
            audio.forEach { NativeEngine.livePush(native, it) }
            return native
        }
    }
}

private fun checkSupported() {
    if (!NativeEngine.supported) throw RuntimeException(UNSUPPORTED_CPU)
}

/** Mono PCM at [fromRate] to 16 kHz, continuous from one [push] to the next. Not thread-safe; any thread. */
class Resampler(fromRate: Int) : Closeable {
    private var handle = run {
        checkSupported()
        NativeEngine.resamplerNew(fromRate)
    }

    fun push(pcm: FloatArray): FloatArray = NativeEngine.resamplerPush(open(), pcm)

    /** The rest of the output, with silence past the end of the input. Closes the resampler. */
    fun finish(): FloatArray {
        val resampler = open()
        handle = 0L
        return NativeEngine.resamplerFinish(resampler)
    }

    override fun close() {
        if (handle != 0L) NativeEngine.resamplerDrop(handle)
        handle = 0L
    }

    private fun open(): Long {
        check(handle != 0L) { "resampler closed" }
        return handle
    }
}

/**
 * Parakeet emits a bare "." (or other punctuation) for noise between utterances — common in a room
 * like a lecture hall. A segment with no letter or digit carries nothing to read, so it is dropped.
 */
internal fun String.isSpoken(): Boolean = any { it.isLetterOrDigit() }
