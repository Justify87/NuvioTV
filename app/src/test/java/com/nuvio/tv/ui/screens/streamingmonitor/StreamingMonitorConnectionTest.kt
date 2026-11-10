package com.nuvio.tv.ui.screens.streamingmonitor

import com.nuvio.tv.core.torrent.TorrentCacheSize
import com.nuvio.tv.core.torrent.TorrentCacheState
import com.nuvio.tv.core.torrent.TorrentSettingsData
import com.nuvio.tv.core.vpn.TorrentVpnSafety
import com.nuvio.tv.core.vpn.VpnHealth
import com.nuvio.tv.core.vpn.VpnTrafficSample
import com.nuvio.tv.core.vpn.WireGuardInfo
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMonitorConnectionTest {
    private val now = 2_000_000L
    private val settings = TorrentSettingsData(p2pEnabled = true)
    private val observed = listOf(
        VpnTrafficSample(now - 5_000, 4_096, 1_024),
        VpnTrafficSample(now, 0, 0)
    )

    private fun info(
        status: TorrentVpnSafety.Status = TorrentVpnSafety.Status.VERIFIED,
        verifiedAt: Long = now - 1_000
    ) = WireGuardInfo(
        status = status,
        profile = "Example VPN",
        exitCity = "Example City",
        exitCountry = "Test Country",
        lastVerifiedAt = verifiedAt,
        lastHandshake = now - 8_000,
        downloadBytesPerSecond = 0,
        uploadBytesPerSecond = 0,
        receivedBytes = 4_194_304,
        sentBytes = 1_048_576
    )

    @Test fun freshVerificationKeepsLocationAndRealMeasuredZero() {
        val result = connectionUiState(info(), observed, TorrentCacheState(), settings, now)
        assertEquals(VpnHealth.CONNECTED, result.health)
        assertTrue(result.canRefresh)
        assertEquals("Example City, Test Country", result.verifiedLocation)
        assertEquals(0L, result.downloadBytesPerSecond)
        assertEquals(0L, result.uploadBytesPerSecond)
        assertEquals(4_194_304L, result.receivedBytes)
        assertEquals(2, result.samples.size)
        assertEquals(now - 8_000L, result.lastHandshake)
    }

    @Test fun tunnelUpIsNotVerifiedEgressOrRefreshPermission() {
        val state = connectionUiState(
            info(TorrentVpnSafety.Status.TUNNEL_UP), observed,
            TorrentCacheState(), settings, now
        )
        assertEquals(VpnHealth.CHECKING, state.health)
        assertFalse(state.canRefresh)
        assertNull(state.verifiedLocation)
        assertNull(state.downloadBytesPerSecond)
        assertNull(state.receivedBytes)
        assertTrue(state.samples.isEmpty())
    }

    @Test fun expiredVerificationIsCheckingButManagerStillAllowsRecheck() {
        val state = connectionUiState(info(verifiedAt = now - 91_000L),
            observed, TorrentCacheState(), settings, now)
        assertEquals(VpnHealth.CHECKING, state.health)
        assertTrue(state.canRefresh)
        assertNull(state.verifiedLocation)
    }

    @Test fun errorAndDisconnectDiscardOldTrafficAndPrivateDiagnostics() {
        for ((status, expected) in listOf(
            TorrentVpnSafety.Status.ERROR to VpnHealth.ERROR,
            TorrentVpnSafety.Status.DISCONNECTED to VpnHealth.DISCONNECTED
        )) {
            val value = info(status).copy(
                exitIp = "private-address",
                endpoint = "private-endpoint",
                error = "private-secret"
            )
            val state = connectionUiState(value, observed, TorrentCacheState(), settings, now)
            assertEquals(expected, state.health)
            assertFalse(state.canRefresh)
            assertTrue(state.samples.isEmpty())
            assertNull(state.verifiedLocation)
            assertFalse(state.toString().contains("private-"))
        }
    }

    @Test fun noSamplesMeansUnknownButActualZeroSampleIsMeasured() {
        val empty = connectionUiState(info(), emptyList(), TorrentCacheState(), settings, now)
        assertNull(empty.downloadBytesPerSecond)
        assertNull(empty.receivedBytes)
        val zero = connectionUiState(info(), listOf(VpnTrafficSample(now, 0, 0)),
            TorrentCacheState(), settings, now)
        assertEquals(0L, zero.downloadBytesPerSecond)
        assertEquals(0L, zero.uploadBytesPerSecond)
    }

    @Test fun trafficFiltersOldNegativeAndFutureAndKeepsTwelveInOrder() {
        val list = (0..14).map { index ->
            VpnTrafficSample(now - index * 5_000L, index.toLong(), 0)
        } + listOf(
            VpnTrafficSample(now + 1_000, 100, 100),
            VpnTrafficSample(now - 2_000, -1, 2),
            VpnTrafficSample(now - 65_000, 1, 1)
        )
        val points = recentVpnSamples(list.reversed(), now)
        assertEquals(12, points.size)
        assertEquals(now - 55_000L, points.first().timestampMs)
        assertEquals(now, points.last().timestampMs)
        assertTrue(points.zipWithNext().all { it.first.timestampMs <= it.second.timestampMs })
    }

    @Test fun cacheUnknownAndClearingAreDistinctFromZeroAndLimit() {
        val unknown = connectionUiState(info(), emptyList(),
            TorrentCacheState(usedBytes = 0L, hasMeasurement = false, isClearing = true),
            settings.copy(p2pEnabled = false, cacheSize = TorrentCacheSize.GB_5), now)
        assertFalse(unknown.cache.hasMeasurement)
        assertTrue(unknown.cache.isClearing)
        assertFalse(unknown.p2pEnabled!!)
        assertEquals(TorrentCacheSize.GB_5.bytes, unknown.cacheLimitBytes)
        val measured = connectionUiState(info(), emptyList(),
            TorrentCacheState(usedBytes = 1_000L, protectedBytes = 400L, hasMeasurement = true),
            settings, now)
        assertTrue(measured.cache.hasMeasurement)
        assertEquals(1_000L, measured.cache.usedBytes)
        assertEquals(400L, measured.cache.protectedBytes)
    }

    @Test fun byteFormatsAreBinaryAndUnknownIsNotZero() {
        assertEquals("—", formatVpnBytes(null, Locale.US))
        assertEquals("0 B", formatVpnBytes(0, Locale.US))
        assertEquals("1023 B", formatVpnBytes(1023, Locale.US))
        assertEquals("1.0 KiB", formatVpnBytes(1024, Locale.US))
        assertEquals("1.0 MiB", formatVpnBytes(1_048_576, Locale.US))
    }
}
