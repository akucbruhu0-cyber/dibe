package app.dibe.android.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.GregorianCalendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Folder of project folders under [root]: `<name>-<yyyyMMdd-HHmmss>/transcript.vibe.json` plus
 * `audio.<ext>`, the same layout as the desktop store. Writes are atomic (temp file + rename).
 *
 * Like the desktop, listing reads folder names only, and a corrupt or foreign record reads as null.
 * [save], [rename] and [update] throw [IOException] when the disk refuses; [delete] is best-effort.
 */
class TranscriptStore(private val root: File) {
    private val _entries = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    private val writes = Mutex()

    /** Old id to new: a screen or chore holding an id from before a rename (say, the AI's) still finds its project. */
    private val moved = ConcurrentHashMap<String, String>()

    /** Newest first. Refreshed after every write. */
    val entries: StateFlow<List<TranscriptEntry>> = _entries.asStateFlow()

    suspend fun refresh() {
        _entries.value = withContext(Dispatchers.IO) { list() }
    }

    /** Moves [audio] into the project folder when given. Returns the new entry. */
    suspend fun save(record: TranscriptRecord, audio: File?): TranscriptEntry {
        val folder = writes.withLock { withContext(Dispatchers.IO) { saveBlocking(record, audio) } }
        refresh()
        val (name, createdAt) = parseStamp(folder.name)
        return TranscriptEntry(folder.name, name, createdAt)
    }

    suspend fun read(id: String): TranscriptRecord? = withContext(Dispatchers.IO) { folder(id)?.let(::readRecord) }

    /**
     * Returns the new id (the folder is renamed). With [onlyIfNamed], the project is left as it is
     * unless its name still is that one, checked under the write lock.
     */
    suspend fun rename(id: String, name: String, onlyIfNamed: String? = null): String {
        val newId = writes.withLock { withContext(Dispatchers.IO) { renameBlocking(id, name, onlyIfNamed) } }
        refresh()
        return newId
    }

    suspend fun delete(id: String) {
        writes.withLock {
            withContext(Dispatchers.IO) {
                val folder = folder(id)
                if (folder != null && !folder.deleteRecursively()) Log.w(TAG, "failed to delete transcript $id")
            }
        }
        refresh()
    }

    suspend fun update(id: String, change: (TranscriptRecord) -> TranscriptRecord): TranscriptRecord =
        writes.withLock {
            withContext(Dispatchers.IO) {
                val folder = folder(id) ?: throw IOException("no transcript $id")
                val record = readRecord(folder) ?: throw IOException("unreadable transcript $id")
                change(record).also { writeAtomic(File(folder, RECORD), it) }
            }
        }

    /** The project's own media copy; every save names it `audio.<ext>`, so no record read is needed. */
    fun audioFile(id: String): File? =
        folder(id)?.listFiles()?.firstOrNull { it.isFile && (it.name == AUDIO || it.name.startsWith("$AUDIO.")) }

    private fun list(): List<TranscriptEntry> {
        val folders = root.listFiles() ?: return emptyList()
        return folders
            .filter { it.isDirectory && File(it, RECORD).isFile }
            .map { folder -> parseStamp(folder.name).let { (name, createdAt) -> TranscriptEntry(folder.name, name, createdAt) } }
            .sortedWith(compareByDescending<TranscriptEntry> { it.createdAtMillis }.thenByDescending { it.id })
    }

    /** The id a project has now, following renames made since [id] was handed out; null for an invalid id. */
    fun currentId(id: String): String? = folder(id)?.name

    /** Ids come from the UI and the share sheet; anything that could leave [root] is no id. */
    private fun folder(id: String): File? {
        if (id.isEmpty() || id == "." || id == ".." || '/' in id || '\\' in id) return null
        var current = id
        var hops = 0
        // Bounded, so a cycle of renames whose folders are all gone ends.
        while (!File(root, current).exists() && hops++ < moved.size) current = moved[current] ?: break
        return File(root, current)
    }

    private fun readRecord(folder: File): TranscriptRecord? =
        try {
            decodeRecord(File(folder, RECORD).readText())
        } catch (e: IOException) {
            Log.w(TAG, "failed to read transcript ${folder.name}", e)
            null
        }

    private fun saveBlocking(record: TranscriptRecord, audio: File?): File {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("cannot create $root")
        val createdAt = parseIso(record.createdAt) ?: System.currentTimeMillis()
        val folder = reserveFolder(record.name, createdAt)
        var moved: File? = null
        try {
            var saved = record
            if (audio != null) {
                val extension = extensionOf(audio.name)
                val target = File(folder, if (extension.isEmpty()) AUDIO else "$AUDIO.$extension")
                Files.move(audio.toPath(), target.toPath())
                moved = target
                // The staged file is gone; a record that named it now names the copy, as the desktop does for its own recordings.
                val sourcePath =
                    if (record.sourcePath.isBlank() || record.sourcePath == audio.absolutePath) target.absolutePath else record.sourcePath
                saved = record.copy(audioFile = target.name, sourcePath = sourcePath)
            }
            writeAtomic(File(folder, RECORD), saved)
            return folder
        } catch (e: IOException) {
            if (moved != null && audio != null) {
                try {
                    Files.move(moved.toPath(), audio.toPath())
                } catch (restore: IOException) {
                    Log.e(TAG, "failed to restore staged audio $audio", restore)
                    // Keeping the folder keeps the audio; an incomplete project beats a lost recording.
                    throw e
                }
            }
            folder.deleteRecursively()
            throw e
        }
    }

    /** mkdir itself is the reservation: two saves cannot both claim one candidate. */
    private fun reserveFolder(name: String, createdAt: Long): File {
        val stem = toFileStem(name)
        val suffix = stamp(createdAt)
        var attempt = 1
        while (true) {
            val candidate = File(root, if (attempt == 1) "$stem-$suffix" else "$stem-$attempt-$suffix")
            if (candidate.mkdir()) return candidate
            if (!candidate.exists()) throw IOException("cannot create $candidate")
            attempt++
        }
    }

    private fun renameBlocking(id: String, newName: String, onlyIfNamed: String?): String {
        val folder = folder(id) ?: throw IOException("no transcript $id")
        val record = readRecord(folder) ?: throw IOException("unreadable transcript $id")
        if (onlyIfNamed != null && record.name != onlyIfNamed) return folder.name
        val stem = toFileStem(newName)
        val current = folder.name
        val keptStamp = STAMP.find(current)?.let { current.substring(it.range.first) } ?: "-${stamp(System.currentTimeMillis())}"
        var renamed = folder
        if ("$stem$keptStamp" != current) {
            var attempt = 1
            while (true) {
                val candidate = File(root, "$stem${if (attempt == 1) "" else "-$attempt"}$keptStamp")
                attempt++
                // rename(2) would replace an empty folder of that name, so taken names are skipped first.
                if (candidate.exists()) continue
                if (folder.renameTo(candidate)) {
                    renamed = candidate
                    moved[current] = candidate.name
                    break
                }
                if (!candidate.exists()) throw IOException("cannot rename $current")
            }
        }
        val oldAudio = record.audioFile?.let { File(folder, it).absolutePath }
        val sourcePath = if (oldAudio != null && record.sourcePath == oldAudio) File(renamed, record.audioFile).absolutePath else record.sourcePath
        try {
            writeAtomic(File(renamed, RECORD), record.copy(name = stem, sourcePath = sourcePath))
        } catch (e: IOException) {
            if (renamed != folder) {
                if (renamed.renameTo(folder)) moved.remove(current) else Log.e(TAG, "failed to roll back rename of $current")
            }
            throw e
        }
        return renamed.name
    }

    private fun writeAtomic(target: File, record: TranscriptRecord) {
        val temporary = File(target.parentFile, "${target.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(temporary).use {
                it.write(encodeRecord(record).toByteArray())
                it.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    internal companion object {
        const val RECORD = "transcript.vibe.json"
        private const val AUDIO = "audio"
        private const val TAG = "TranscriptStore"
        private const val EPOCH_ISO = "1970-01-01T00:00:00.000Z"

        private val STAMP = Regex("-(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})(\\d{2})$")
        private val STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

        @OptIn(ExperimentalSerializationApi::class)
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            prettyPrint = true
            prettyPrintIndent = "\t"
        }

        /** `yyyyMMdd-HHmmss` in local time — sortable and readable in a file manager. */
        fun stamp(millis: Long): String =
            STAMP_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

        /** Display name and local creation time from a folder name; the epoch when it has no stamp. */
        fun parseStamp(stem: String): Pair<String, Long> {
            val match = STAMP.find(stem) ?: return stem to 0L
            val (year, month, day, hours, minutes, seconds) = match.destructured
            // Lenient like JS `new Date(y, m, d, ...)`: out-of-range fields roll over instead of failing.
            val createdAt = GregorianCalendar(year.toInt(), month.toInt() - 1, day.toInt(), hours.toInt(), minutes.toInt(), seconds.toInt())
            return stem.substring(0, match.range.first).ifEmpty { stem } to createdAt.timeInMillis
        }

        /** Strip the extension and anything a file system would object to. */
        fun toFileStem(name: String): String {
            val cleaned = name
                .replace(Regex("\\.[^./\\\\]+$"), "")
                .replace(Regex("[/\\\\?%*:|\"<>]"), "-")
                .replace(Regex("[\\u0000-\\u001f]"), "")
                .replace(Regex("$JS_SPACE+"), " ")
                .jsTrim()
                .replace(Regex("^\\.+"), "")
            return cleaned.ifEmpty { "transcript" }.take(120)
        }

        fun extensionOf(name: String): String {
            val dot = name.lastIndexOf('.')
            if (dot <= 0) return ""
            val extension = name.substring(dot + 1)
            return if (Regex("[A-Za-z0-9]{1,10}").matches(extension)) extension else ""
        }

        fun parseIso(value: String): Long? =
            try {
                Instant.parse(value).toEpochMilli()
            } catch (e: DateTimeParseException) {
                null
            }

        fun encodeRecord(record: TranscriptRecord): String {
            val element = json.encodeToJsonElement(TranscriptRecord.serializer(), record.copy(thread = record.thread?.ifEmpty { null })).jsonObject
            if (record.modelPath != null) return json.encodeToString(JsonObject.serializer(), element)
            // The desktop writes `"modelPath": null` where every other absent field is left out.
            val shaped = buildMap<String, JsonElement> {
                for ((key, value) in element) {
                    if (key == "audioFile" || key == "segments") putIfAbsent("modelPath", JsonNull)
                    put(key, value)
                }
            }
            return json.encodeToString(JsonObject.serializer(), JsonObject(shaped))
        }

        /** Field by field like the desktop's `readTranscript`: one bad segment or name never loses the record. */
        fun decodeRecord(raw: String): TranscriptRecord? {
            val parsed = try {
                Json.parseToJsonElement(raw)
            } catch (e: SerializationException) {
                return null
            }
            val record = parsed as? JsonObject ?: return null
            val name = record.string("name") ?: return null
            val segments = record["segments"] as? JsonArray ?: return null
            return TranscriptRecord(
                version = record.number("version")?.toInt() ?: 1,
                name = name,
                sourcePath = record.string("sourcePath") ?: "",
                createdAt = record.string("createdAt") ?: EPOCH_ISO,
                language = record.string("language"),
                modelPath = record.string("modelPath"),
                audioFile = record.string("audioFile")?.takeIf { it.isNotEmpty() }?.split('/', '\\')?.last(),
                segments = segments.mapNotNull { segment ->
                    val fields = segment as? JsonObject ?: return@mapNotNull null
                    val text = fields.string("text") ?: return@mapNotNull null
                    Segment(
                        start = fields.number("start")?.toLong() ?: 0,
                        stop = fields.number("stop")?.toLong() ?: 0,
                        text = text,
                        speaker = fields.number("speaker")?.toInt(),
                    )
                },
                speakerNames = (record["speakerNames"] as? JsonObject)
                    ?.mapNotNull { (key, value) ->
                        val speaker = key.jsTrim().toIntOrNull()?.takeIf { it >= 0 }
                        val chosen = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.jsTrim()
                        if (speaker == null || chosen.isNullOrEmpty()) null else speaker to chosen
                    }
                    // JS objects list integer keys in ascending order, whatever order the file had.
                    ?.sortedBy { it.first }
                    ?.associate { (speaker, chosen) -> speaker.toString() to chosen }
                    ?.ifEmpty { null },
                summary = record.string("summary"),
                thread = (record["thread"] as? JsonArray)
                    ?.mapNotNull { entry ->
                        val fields = entry as? JsonObject ?: return@mapNotNull null
                        val question = fields.string("question") ?: return@mapNotNull null
                        val answer = fields.string("answer") ?: return@mapNotNull null
                        AiThreadEntry(question, answer)
                    }
                    ?.ifEmpty { null },
                meeting = (record["meeting"] as? JsonObject)?.let { fields ->
                    val title = fields.string("title") ?: return@let null
                    val start = fields.string("start") ?: return@let null
                    val end = fields.string("end") ?: return@let null
                    val attendees = (fields["attendees"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        ?.ifEmpty { null }
                    Meeting(title, start, end, fields.string("calendar"), attendees)
                },
            )
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    }
}
