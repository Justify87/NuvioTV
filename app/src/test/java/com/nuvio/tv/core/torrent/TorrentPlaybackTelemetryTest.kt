package com.nuvio.tv.core.torrent

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TorrentPlaybackTelemetryTest {
    private var elapsed = 1_000L
    private val prefs = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val context = mockk<Context>()
    private lateinit var history: TorrentSessionHistory

    @Before fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { elapsed }
        every { context.getSharedPreferences("torrent_session_history", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getBoolean("enabled", false) } returns true
        every { prefs.getString("entries", null) } returns "[]"
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        history = TorrentSessionHistory(context)
    }

    @After fun tearDown() { unmockkStatic(SystemClock::class) }

    @Test fun trueFirstFrameIsMonotonicDeduplicatedAndNotInvented() {
        val id = history.begin("Sample.4k.mkv")
        assertNull(history.live.value?.firstFrameMs)
        history.markFirstFrame(id, elapsedMs = 900L) // invalid monotonic event
        assertNull(history.live.value?.firstFrameMs)
        history.markFirstFrame(id, elapsedMs = 1_620L)
        history.markFirstFrame(id, elapsedMs = 1_900L)
        assertEquals(620L, history.live.value?.firstFrameMs)
        assertEquals(0, history.live.value?.rebufferCount)
        history.markPlaybackFinished(id)
        history.finish(sessionId = id)
        assertEquals(TorrentSessionOutcome.FINISHED, history.history.value.single().outcome)
    }

    @Test fun zeroOneAndMultipleRebuffersOnlyAfterFirstFrame() {
        val id = history.begin("Sample.mkv")
        history.onRebufferStart(id, 1_100L) // initial buffering ignored
        history.onRebufferEnd(id, 1_300L)
        assertNull(history.live.value?.rebufferCount)
        history.markFirstFrame(id, 1_400L)
        assertEquals(0, history.live.value?.rebufferCount)
        history.onRebufferStart(id, 2_000L)
        history.onRebufferStart(id, 2_050L) // repeated state, not a second stall
        history.onRebufferEnd(id, 2_900L)
        history.onRebufferEnd(id, 3_000L)
        assertEquals(1, history.live.value?.rebufferCount)
        assertEquals(900L, history.live.value?.rebufferTotalMs)
        history.onRebufferStart(id, 4_000L)
        history.onRebufferEnd(id, 4_400L)
        assertEquals(2, history.live.value?.rebufferCount)
        assertEquals(1_300L, history.live.value?.rebufferTotalMs)
    }

    @Test fun seekingPausingAndBackgroundDiscardPendingStall() {
        val id = history.begin("Sample.mkv")
        history.markFirstFrame(id, 1_100L)
        history.onRebufferStart(id, 1_200L)
        history.onRebufferEnd(id, 1_800L, resumed = false)
        history.onRebufferStart(id, 1_900L)
        history.onRebufferEnd(id, 2_900L, resumed = false)
        assertEquals(0, history.live.value?.rebufferCount)
        assertEquals(0L, history.live.value?.rebufferTotalMs)
    }

    @Test fun cancelBeforeFirstFrameDoesNotCountAsSuccess() {
        val id = history.begin("Sample.mkv")
        elapsed = 1_800L
        history.finish(sessionId = id)
        val entry = history.history.value.single()
        assertEquals(TorrentSessionOutcome.CANCELLED_BEFORE_PLAYBACK, entry.outcome)
        assertNull(entry.firstFrameMs)
        assertNull(entry.rebufferTotalMs)
        assertEquals("Nicht ausreichend messbar", assessStreamingSession(entry).label)
    }

    @Test fun staleCallbacksCannotPoisonNewSession() {
        val old = history.begin("First.mkv")
        elapsed = 2_000L
        val fresh = history.begin("Second.mkv")
        history.markFirstFrame(old, 2_300L)
        history.onRebufferStart(old, 2_400L)
        history.updateMediaInfo(old, TorrentMediaProfile(width = 4096, height = 2160))
        history.markPlaybackFailed(old)
        history.finish(sessionId = old, failed = true)
        assertEquals(fresh, history.live.value?.sessionId)
        assertNull(history.live.value?.firstFrameMs)
        assertNull(history.live.value?.mediaProfile)
        assertEquals(TorrentSessionOutcome.UNKNOWN, history.live.value?.outcome)
    }

    @Test fun unfinishedBufferIsFlaggedRatherThanCountedAsComplete() {
        val id = history.begin("Sample.mkv")
        history.markFirstFrame(id, 1_100L)
        history.onRebufferStart(id, 2_000L)
        elapsed = 2_500L
        history.finish(sessionId = id)
        val entry = history.history.value.single()
        assertTrue(entry.rebufferIncomplete)
        assertEquals(0, entry.rebufferCount)
        assertEquals("Messung unvollständig", assessStreamingSession(entry).label)
    }

    @Test fun manualStopAfterFirstFrameIsNotNaturalCompletion() {
        val id = history.begin("Sample.mkv")
        history.markFirstFrame(id, 1_500L)
        elapsed = 2_200L
        history.finish(sessionId = id)
        val saved = history.history.value.single()
        assertEquals(TorrentSessionOutcome.UNKNOWN, saved.outcome)
        assertEquals("Kurze Beobachtung", assessStreamingSession(saved).label)
    }

    @Test fun terminalFailureDominatesNormalStop() {
        val id = history.begin("Sample.mkv")
        history.markFirstFrame(id, 1_100L)
        history.markPlaybackFailed(id)
        elapsed = 2_000L
        history.finish(sessionId = id)
        assertEquals(TorrentSessionOutcome.FAILED, history.history.value.single().outcome)
    }

    @Test fun newJsonIsReadWithoutChangingLegacyOrDroppingOtherEntries() {
        val parsed = parseSavedTorrentSessions(
            """[{"title":"Old","time":42,"buffers":2,"samples":5},
            {"title":"New","time":43,"v":2,"id":"new-local","outcome":"FINISHED",
             "firstFrameMs":340,"rebufferCount":1,"rebufferTotalMs":800,
             "media":{"w":1920,"h":1080,"videoCodec":"HEVC","hdr":["HLG"],
             "audioCodec":"AAC","channels":2,"videoBitrate":7000000}},
            {"title":"Broken metadata","time":44,"v":2,"firstFrameMs":-1,
             "rebufferCount":-7,"media":{"w":-1,"h":999999}}]"""
        )
        assertEquals(3, parsed.size)
        val old = parsed.last()
        assertEquals(0, old.telemetryVersion)
        assertNull(old.rebufferCount)
        assertEquals(2, old.bufferEvents)
        val modern = parsed[1]
        assertEquals(340L, modern.firstFrameMs)
        assertEquals(800L, modern.rebufferTotalMs)
        assertEquals("1920 × 1080", modern.mediaProfile?.resolution)
        assertEquals(7_000_000L, modern.mediaProfile?.videoBitrateBitsPerSecond)
        assertNull(parsed.first().firstFrameMs)
        assertNull(parsed.first().mediaProfile?.width)
    }

    @Test fun noBitrateRatioWithoutRealFormatAndBytesToBitsWhenKnown() {
        val base = TorrentSessionStats(
            title = "Sample", startedAtMs = 1L, telemetryVersion = 2,
            outcome = TorrentSessionOutcome.FINISHED, firstFrameMs = 1_000L,
            durationMs = 120_000L, rebufferCount = 0, rebufferTotalMs = 0L,
            samples = 20, averageDownloadBps = 1_000_000L
        )
        assertNull(assessStreamingSession(base).downloadToVideoBitrateRatio)
        val measured = base.copy(mediaProfile = TorrentMediaProfile(videoBitrateBitsPerSecond = 4_000_000L))
        assertEquals(2.0, assessStreamingSession(measured).downloadToVideoBitrateRatio!!, 0.00001)
    }

    @Test fun filenameNeverOverridesPlayerObservedResolution() {
        val id = history.begin("Public.Sample.2160p.mkv")
        history.updateMediaInfo(id, TorrentMediaProfile(width = 1280, height = 720))
        assertEquals("2160P", history.live.value?.resolutionHint)
        assertEquals("1280 × 720", history.live.value?.mediaProfile?.resolution)
    }
}
