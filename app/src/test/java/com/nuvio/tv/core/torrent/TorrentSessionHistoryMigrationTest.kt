package com.nuvio.tv.core.torrent

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentSessionHistoryMigrationTest {
    private fun json(vararg timestamps: Long): String =
        timestamps.joinToString(prefix = "[", postfix = "]") { time ->
            """{"title":"Public test media","time":$time,"duration":1000,"samples":1}"""
        }

    @Test fun emptySingleFortyAndFortyOneStoredEntries() {
        assertTrue(parseSavedTorrentSessions("[]").isEmpty())
        assertEquals(1, parseSavedTorrentSessions(json(1000L)).size)
        assertEquals(40, parseSavedTorrentSessions(json(*(1L..40L).toList().toLongArray())).size)
        val saved = parseSavedTorrentSessions(json(*(1L..41L).toList().toLongArray()))
        assertEquals(40, saved.size)
        assertEquals(41L, saved.first().startedAtMs)
        assertEquals(2L, saved.last().startedAtMs)
    }

    @Test fun reverseChronologicalOrderDoesNotMutateInput() {
        val saved = parseSavedTorrentSessions(json(3, 1, 5, 2))
        assertEquals(listOf(5L, 3L, 2L, 1L), saved.map { it.startedAtMs })
        val original = listOf(
            TorrentSessionStats("one", 1),
            TorrentSessionStats("two", 2)
        )
        assertEquals(listOf(2L, 1L), boundedSavedSessions(original).map { it.startedAtMs })
        assertEquals(1L, original.first().startedAtMs)
    }

    @Test fun legacyFieldsReceiveDefaultsWithoutInventingMeasurements() {
        val entry = parseSavedTorrentSessions("""[{"title":"Legacy","time":1234}]""").single()
        assertEquals("Legacy", entry.title)
        assertEquals(0L, entry.durationMs)
        assertEquals(0, entry.samples)
        assertEquals(0L, entry.averageDownloadBps)
        assertEquals("Unbekannt", entry.resolutionHint)
        assertEquals("", entry.sessionId)
        assertFalse(entry.failed)
    }

    @Test fun optionalIdAndExistingFieldsSurviveParsing() {
        val entry = parseSavedTorrentSessions(
            """[{"id":"local-id","title":"Example","time":9999,"duration":4000,"resolution":"1080P","avgDown":200,"peakDown":400,"avgUp":40,"peers":3,"seeds":2,"bytes":800,"buffers":1,"samples":4,"failed":true}]"""
        ).single()
        assertEquals("local-id", entry.sessionId)
        assertEquals("1080P", entry.resolutionHint)
        assertEquals(400L, entry.peakDownloadBps)
        assertEquals(1, entry.bufferEvents)
        assertEquals(4, entry.samples)
        assertTrue(entry.failed)
    }

    @Test fun corruptTopLevelAndMalformedRecordsAreTolerated() {
        assertTrue(parseSavedTorrentSessions("{bad json").isEmpty())
        assertTrue(parseSavedTorrentSessions("""{"not":"an array"}""").isEmpty())
        val result = parseSavedTorrentSessions(
            """[null,42,{},{"title":"valid","time":700},{"title":false,"time":"oops"}]"""
        )
        assertEquals(1, result.size)
        assertEquals(700L, result.single().startedAtMs)
    }

    @Test fun optInDefaultsOffAndClearDoesNotChangeRecordingPreference() {
        val prefs = mockk<SharedPreferences>()
        val edit = mockk<SharedPreferences.Editor>(relaxed = true)
        val context = mockk<Context>()
        every { context.getSharedPreferences("torrent_session_history", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getBoolean("enabled", false) } returns false
        every { prefs.getString("entries", null) } returns json(100)
        every { prefs.edit() } returns edit
        every { edit.putBoolean(any(), any()) } returns edit
        every { edit.remove(any()) } returns edit
        val history = TorrentSessionHistory(context)

        assertFalse(history.enabled.value)
        assertEquals(1, history.history.value.size)
        history.setEnabled(true)
        assertTrue(history.enabled.value)
        history.clear()
        assertTrue(history.enabled.value)
        assertTrue(history.history.value.isEmpty())
        verify(exactly = 1) { edit.putBoolean("enabled", true) }
        verify(exactly = 1) { edit.remove("entries") }
    }
}
