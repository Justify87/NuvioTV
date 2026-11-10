package com.nuvio.tv.core.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardConnectionCancellationTest {
    private enum class Point { BEFORE_BACKEND, DURING_VERIFICATION, AFTER_TUNNEL_STARTED }

    @Test fun cancellationBeforeBackendStartResetsEverything() =
        checkCancellation(Point.BEFORE_BACKEND)

    @Test fun cancellationDuringVerificationStopsAnAlreadyStartedTunnel() =
        checkCancellation(Point.DURING_VERIFICATION)

    @Test fun cancellationAfterTunnelStartStopsMonitoringAndResetsStatistics() =
        checkCancellation(Point.AFTER_TUNNEL_STARTED)

    private fun checkCancellation(point: Point) = runBlocking {
        val safety = TorrentVpnSafety()
        val reachedPoint = CompletableDeferred<Unit>()
        var displayed = WireGuardInfo()
        var tunnelUp = false
        var monitoring = false
        var trafficSamples = 2
        var stopCalls = 0
        var originalCancellationRethrown = false

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                safety.update(TorrentVpnSafety.Status.CONNECTING)
                displayed = WireGuardInfo(status = TorrentVpnSafety.Status.CONNECTING)
                if (point != Point.BEFORE_BACKEND) {
                    tunnelUp = true
                    safety.update(TorrentVpnSafety.Status.TUNNEL_UP)
                    displayed = WireGuardInfo(status = TorrentVpnSafety.Status.TUNNEL_UP)
                }
                if (point == Point.AFTER_TUNNEL_STARTED) {
                    monitoring = true
                    safety.update(TorrentVpnSafety.Status.VERIFIED)
                    displayed = WireGuardInfo(
                        status = TorrentVpnSafety.Status.VERIFIED,
                        exitIp = "198.51.100.1",
                        lastVerifiedAt = 1234L,
                        lastHandshake = 1234L,
                        receivedBytes = 300L,
                        sentBytes = 400L,
                        downloadBytesPerSecond = 10L,
                        uploadBytesPerSecond = 20L
                    )
                }
                reachedPoint.complete(Unit)
                awaitCancellation()
            } catch (cancellation: CancellationException) {
                try {
                    abortWireGuardConnect(cancellation) {
                        assertTrue(teardownWireGuardConnection(
                            safety = safety,
                            resetLocalState = {
                                monitoring = false
                                displayed = WireGuardInfo()
                                trafficSamples = 0
                            },
                            stopBackend = {
                                // A real backend call runs on IO; this suspension proves
                                // the teardown is protected from coroutine cancellation.
                                delay(10)
                                stopCalls++
                                tunnelUp = false
                            }
                        ))
                    }
                } catch (rethrown: CancellationException) {
                    assertSame(cancellation, rethrown)
                    originalCancellationRethrown = true
                    throw rethrown
                }
            }
        }

        reachedPoint.await()
        job.cancel()
        job.join()

        assertTrue(originalCancellationRethrown)
        assertEquals(1, stopCalls)
        assertFalse(tunnelUp)
        assertFalse(monitoring)
        assertEquals(TorrentVpnSafety.Status.DISCONNECTED, safety.status.value)
        assertEquals(WireGuardInfo(), displayed)
        assertEquals(0, trafficSamples)
    }
}
