package com.nuvio.tv.ui.screens.streamingmonitor

import com.nuvio.tv.core.torrent.TorrentRateSample
import com.nuvio.tv.core.torrent.TorrentSessionStats
import com.nuvio.tv.core.torrent.TorrentState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMonitorOverviewTest {
    private fun session(
        samples: Int = 1,
        durationMs: Long = 15_000,
        failed: Boolean = false,
        buffers: Int = 0
    ) = TorrentSessionStats(
        title = "Example", startedAtMs = 1_000L, durationMs = durationMs,
        samples = samples, failed = failed, bufferEvents = buffers
    )

    private fun streaming(
        download: Long = 0L,
        upload: Long = 0L
    ) = TorrentState.Streaming(
        localUrl = "", downloadSpeed = download, uploadSpeed = upload,
        peers = 4, seeds = 2, bufferProgress = 0.5f, totalProgress = 0.1f
    )

    @Test fun measuredZeroIsNotUnknownAndNegativeRateIsUnknown() {
        val rates = torrentRates(streaming(download = 0, upload = -1))!!
        assertEquals(0L, rates.downloadBytesPerSecond)
        assertNull(rates.uploadBytesPerSecond)
        assertEquals("0 B/s", formatTorrentRate(rates.downloadBytesPerSecond, Locale.GERMANY))
        assertEquals("—", formatTorrentRate(rates.uploadBytesPerSecond, Locale.GERMANY))
        assertEquals("—", formatTorrentRate(-4L, Locale.GERMANY))
    }

    @Test fun unitsAreBinaryAndHugeRatesDoNotOverflow() {
        assertEquals("1,0 KiB/s", formatTorrentRate(1024L, Locale.GERMANY))
        assertEquals("1,5 MiB/s", formatTorrentRate(1_572_864L, Locale.GERMANY))
        assertEquals("8,0 EiB/s", formatTorrentRate(Long.MAX_VALUE, Locale.GERMANY))
    }

    @Test fun emptyOneAndSixtySamplesPreserveMeasurements() {
        assertTrue(recentTorrentSamples(emptyList()).isEmpty())
        val single = TorrentRateSample(100_000, 0, 1, 0)
        assertEquals(listOf(single), recentTorrentSamples(listOf(single)))
        val sixty = (0L until 60L).map { i -> TorrentRateSample(100_000 + 1000 * i, i, 2 * i, 1) }
        assertEquals(sixty, recentTorrentSamples(sixty))
    }

    @Test fun plotDropsInvalidOldValuesAndRetainsTimestampOrder() {
        val recent = TorrentRateSample(200_000, Long.MAX_VALUE, 0, 3)
        val earlier = TorrentRateSample(170_000, 5, 6, 1)
        val tooOld = TorrentRateSample(100_000, 2, 2, 0)
        val negative = TorrentRateSample(198_000, -1, 12, 1)
        assertEquals(
            listOf(earlier, recent),
            recentTorrentSamples(listOf(recent, negative, tooOld, earlier))
        )
    }

    @Test fun metricDenominatorIncludesMeasuredFailuresButNotUnmeasuredStartups() {
        val result = recordedStability(
            listOf(session(samples = 0), session(), session(buffers = 1),
                session(failed = true), session(durationMs = 0))
        )
        assertEquals(3, result.sessionsWithMeasurements)
        assertEquals(1, result.withoutRecordedInterruption)
        assertEquals(33, result.percentage)
        assertNull(recordedStability(emptyList()).percentage)
        assertNull(recordedStability(listOf(session(samples = 0))).percentage)
    }

    @Test fun p2pDisabledAndIdleCannotDisplayStaleLiveData() {
        val output = monitorUiState(
            TorrentState.Idle, session(), listOf(TorrentRateSample(1000, 1024, 0, 1)),
            listOf(session()), recordingEnabled = false, p2pEnabled = false
        )
        assertEquals(TorrentPhase.DISABLED, output.torrentPhase)
        assertNull(output.activeSession)
        assertNull(output.liveRates)
        assertTrue(output.samplePoints.isEmpty())
        assertEquals(1, output.recentCount)
        assertFalse(output.historyRecordingEnabled)
    }

    @Test fun connectingIsNotReportedAsActiveStreamingAndHasNoGuessedRates() {
        val output = monitorUiState(
            TorrentState.Connecting(), session(), emptyList(), emptyList(),
            recordingEnabled = false, p2pEnabled = true
        )
        assertEquals(TorrentPhase.CONNECTING, output.torrentPhase)
        assertNull(output.liveRates)
        assertNull(output.connectedPeers)
        assertFalse(output.hasEngineMeasurement)
    }

    @Test fun streamingBeforeFirstMeasurementDoesNotInventZeroRatesOrPeers() {
        val output = monitorUiState(
            streaming(0L, 0L), session(samples = 0), emptyList(), emptyList(),
            recordingEnabled = false, p2pEnabled = true
        )
        assertEquals(TorrentPhase.STREAMING, output.torrentPhase)
        assertNull(output.liveRates)
        assertNull(output.connectedPeers)
        assertNull(output.connectedSeeds)
        assertFalse(output.hasEngineMeasurement)
    }

    @Test fun streamingShowsActualRatesWhileHistoryRecordingOff() {
        val sample = TorrentRateSample(1_000, 0, 1024, 4)
        val output = monitorUiState(
            streaming(0L, 1024L), session(buffers = 0), listOf(sample), emptyList(),
            recordingEnabled = false, p2pEnabled = true
        )
        assertEquals(TorrentPhase.STREAMING, output.torrentPhase)
        assertEquals(0L, output.liveRates?.downloadBytesPerSecond)
        assertEquals(1024L, output.liveRates?.uploadBytesPerSecond)
        assertEquals(4, output.connectedPeers)
        assertEquals(2, output.connectedSeeds)
        assertEquals(0, output.recordedBufferInterruptions)
        assertEquals(listOf(sample), output.samplePoints)
        assertTrue(output.hasEngineMeasurement)
        assertFalse(output.historyRecordingEnabled)
    }

    @Test fun errorNeverPropagatesRawEngineDetails() {
        val output = monitorUiState(
            TorrentState.Error("private stream location"), session(), emptyList(), emptyList(),
            recordingEnabled = false, p2pEnabled = true
        )
        assertEquals(TorrentPhase.ERROR, output.torrentPhase)
        assertNull(output.activeSession)
        assertNull(output.liveRates)
    }
}
