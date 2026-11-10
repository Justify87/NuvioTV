package com.nuvio.tv.ui.screens.streamingmonitor

import com.nuvio.tv.core.torrent.TorrentSessionStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMonitorHistoryTest {
    private fun session(time: Long, id: String = "") = TorrentSessionStats(
        title = "Public demo", startedAtMs = time,
        durationMs = 65_000, sessionId = id
    )

    @Test fun emptyListIsEmptyAndOneLiveItemAppearsOnlyOnce() {
        assertTrue(streamingHistoryEntries(emptyList(), null).isEmpty())
        val live = session(123, "current")
        val items = streamingHistoryEntries(listOf(live), live)
        assertEquals(1, items.size)
        assertTrue(items.single().isLive)
    }

    @Test fun sortedSavedListIsBoundedAndKeysAreDistinct() {
        val many = (1L..41L).map { session(it) }
        val entries = streamingHistoryEntries(many, null)
        assertEquals(40, entries.size)
        assertEquals(41L, entries.first().session.startedAtMs)
        assertEquals(2L, entries.last().session.startedAtMs)
        assertEquals(40, entries.map { it.key }.toSet().size)
        assertFalse(entries.any { it.isLive })
    }

    @Test fun newIdsRemainStableAcrossAdditionalSavedSessions() {
        val stable = session(5, "saved-5")
        val first = streamingHistoryEntries(listOf(stable), null).single().key
        val next = streamingHistoryEntries(listOf(session(6, "saved-6"), stable), null)
        assertEquals(first, next.last().key)
    }

    @Test fun legacyDuplicateRowsHaveDistinctKeys() {
        val repeated = session(1)
        val rows = streamingHistoryEntries(listOf(repeated, repeated), null)
        assertEquals(2, rows.map { it.key }.toSet().size)
    }

    @Test fun durationAndDataUnitsAreExplicit() {
        assertEquals("1:05", formatTorrentDuration(65_000))
        assertEquals("1:01:05", formatTorrentDuration(3_665_000))
        assertEquals("—", formatTorrentDuration(-1))
        assertTrue(formatTorrentBytes(1_048_576).contains("MiB"))
    }
}
