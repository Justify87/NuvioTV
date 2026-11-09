package com.nuvio.tv.core.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class TorrentSessionRatingTest {
    @Test fun resolutionIsOnlyAFileNameHint() {
        assertEquals("1080P", torrentResolutionHint("Episode.S02E05.1080p.WEB-DL.mkv"))
        assertEquals("Unbekannt", torrentResolutionHint("Episode.mkv"))
    }
    @Test fun failedSessionsCannotGetGoodRating() {
        assertEquals("Fehlgeschlagen", torrentSessionRating(
            TorrentSessionStats(title = "test", startedAtMs = 1L, failed = true,
                averageDownloadBps = 20L * 1024 * 1024, peakPeers = 100, samples = 10)))
    }
    @Test fun stallsWeakenFastTorrent() {
        assertEquals("Instabil", torrentSessionRating(
            TorrentSessionStats(title = "test", startedAtMs = 1L, bufferEvents = 4,
                averageDownloadBps = 20L * 1024 * 1024, peakPeers = 100, samples = 10)))
    }
    @Test fun healthySessionCanBeVeryGood() {
        assertEquals("Sehr gut", torrentSessionRating(
            TorrentSessionStats(title = "test", startedAtMs = 1L,
                averageDownloadBps = 8L * 1024 * 1024, peakPeers = 10, samples = 10)))
    }
}
