package app.dibe.android.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.GregorianCalendar

class TranscriptStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val record = TranscriptRecord(
        name = "Meeting: Q3/Q4",
        sourcePath = "",
        createdAt = "2026-11-09T08:15:30.123Z",
        segments = listOf(Segment(0, 712, "שלום", 0), Segment(712, 1530, "עוד")),
    )

    @Test
    fun readsAndWritesExactlyWhatTheDesktopDoes() {
        // Raw desktop files and the bytes the desktop's own update functions wrote back (generated from the TS).
        val cases = Json.parseToJsonElement(javaClass.classLoader!!.getResource("data/records.json")!!.readText()) as JsonArray
        for (case in cases) {
            val fields = case.jsonObject
            val decoded = TranscriptStore.decodeRecord(fields["raw"]!!.jsonPrimitive.content)!!
            val summary = fields["summary"]!!.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            val changed = if (summary != null) decoded.copy(summary = summary) else decoded.copy(thread = emptyList())
            assertEquals(fields["written"]!!.jsonPrimitive.content, TranscriptStore.encodeRecord(changed))
        }
    }

    @Test
    fun corruptOrForeignFilesReadAsNull() {
        assertNull(TranscriptStore.decodeRecord("{not json"))
        assertNull(TranscriptStore.decodeRecord("[]"))
        assertNull(TranscriptStore.decodeRecord("""{"name": 3, "segments": []}"""))
        assertNull(TranscriptStore.decodeRecord("""{"name": "x"}"""))
    }

    @Test
    fun namesAndStampsFollowTheDesktop() {
        assertEquals("Meeting- Q3-Q4", TranscriptStore.toFileStem("Meeting: Q3/Q4"))
        assertEquals("recording", TranscriptStore.toFileStem("recording.m4a"))
        assertEquals("transcript", TranscriptStore.toFileStem("..."))
        assertEquals("a b", TranscriptStore.toFileStem("﻿ a  \t b \u0001"))
        assertEquals(120, TranscriptStore.toFileStem("x".repeat(300)).length)

        val (name, millis) = TranscriptStore.parseStamp("פגישה-2-20261109-101530")
        assertEquals("פגישה-2", name)
        assertEquals(GregorianCalendar(2026, 10, 9, 10, 15, 30).timeInMillis, millis)
        assertEquals("no stamp" to 0L, TranscriptStore.parseStamp("no stamp"))
        assertEquals("-20261109-101530" to GregorianCalendar(2026, 10, 9, 10, 15, 30).timeInMillis, TranscriptStore.parseStamp("-20261109-101530"))
        assertEquals("20261109-101530", TranscriptStore.stamp(GregorianCalendar(2026, 10, 9, 10, 15, 30).timeInMillis))
        assertEquals("2026-11-09T08:15:30.123Z", isoTimestamp(TranscriptStore.parseIso("2026-11-09T08:15:30.123Z")!!))
    }

    @Test
    fun savesListsReadsRenamesUpdatesAndDeletes() = runTest {
        val root = File(temp.root, "transcripts")
        val store = TranscriptStore(root)
        val staged = temp.newFile("rec-123.m4a").apply { writeText("audio") }

        val entry = store.save(record, staged)
        val stamp = TranscriptStore.stamp(TranscriptStore.parseIso(record.createdAt)!!)
        assertEquals("Meeting- Q3-Q4-$stamp", entry.id)
        assertEquals("Meeting- Q3-Q4", entry.name)
        assertFalse(staged.exists())
        val audio = store.audioFile(entry.id)!!
        assertEquals("audio.m4a", audio.name)
        assertEquals("audio", audio.readText())
        assertEquals(listOf(entry), store.entries.value)

        val saved = store.read(entry.id)!!
        assertEquals("audio.m4a", saved.audioFile)
        assertEquals(audio.absolutePath, saved.sourcePath)
        assertEquals(record.segments, saved.segments)
        assertEquals("Meeting: Q3/Q4", saved.name)

        // A second save at the same second gets its own folder.
        val second = store.save(record.copy(sourcePath = "content://x"), null)
        assertEquals("Meeting- Q3-Q4-2-$stamp", second.id)
        assertEquals("content://x", store.read(second.id)!!.sourcePath)
        assertNull(store.audioFile(second.id))
        assertEquals(2, store.entries.value.size)

        val renamed = store.rename(entry.id, "שם חדש")
        assertEquals("שם חדש-$stamp", renamed)
        assertNull(store.read(entry.id))
        val afterRename = store.read(renamed)!!
        assertEquals("שם חדש", afterRename.name)
        assertEquals(File(File(root, renamed), "audio.m4a").absolutePath, afterRename.sourcePath)
        assertEquals("audio", store.audioFile(renamed)!!.readText())
        assertEquals(renamed, store.rename(renamed, "שם חדש"))

        val updated = store.update(renamed) { it.copy(summary = "סיכום", thread = listOf(AiThreadEntry("q", "a"))) }
        assertEquals(updated, store.read(renamed))
        assertFalse(File(root, renamed).list()!!.any { it.contains(".tmp-") })

        store.delete(renamed)
        assertFalse(File(root, renamed).exists())
        assertEquals(listOf(second.id), store.entries.value.map { it.id })
        store.delete("../escape")
        assertTrue(temp.root.exists())
    }

    @Test
    fun corruptRecordsAreSkippedNotFatal() = runTest {
        val root = temp.newFolder("t")
        val store = TranscriptStore(root)
        File(root, "broken-20260101-000000").mkdirs()
        File(root, "broken-20260101-000000/transcript.vibe.json").writeText("{oops")
        File(root, "not-a-project").mkdirs()
        store.refresh()
        assertEquals(listOf("broken-20260101-000000"), store.entries.value.map { it.id })
        assertNull(store.read("broken-20260101-000000"))
        assertNull(store.read("missing"))
        try {
            store.update("broken-20260101-000000") { it }
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
        }
    }
}
