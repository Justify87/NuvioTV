package com.nuvio.tv.core.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TorrentCleanupCoordinatorTest {
    private data class Request(val streamId: String?, val shutdown: Boolean)

    private fun merge(previous: Request, incoming: Request) = Request(
        streamId = previous.streamId ?: incoming.streamId,
        shutdown = previous.shutdown || incoming.shutdown
    )

    @Test
    fun `blocked native cleanup times out a start without cancelling the worker`() = runTest {
        val releaseNativeCall = CompletableDeferred<Unit>()
        val calls = mutableListOf<Request>()
        val coordinator = TorrentCleanupCoordinator<Request>(
            scope = backgroundScope,
            mergePending = ::merge,
            performCleanup = { request ->
                if (request.streamId == "old") releaseNativeCall.await()
                calls += request
            }
        )

        assertTrue(coordinator.request(Request("old", shutdown = false)))
        runCurrent() // Native stop entered and is blocked.
        val starting = async {
            runCatching { coordinator.awaitIdle(timeoutMs = 100) }.exceptionOrNull()
        }
        advanceTimeBy(100)
        runCurrent()

        assertTrue(starting.await() is TimeoutCancellationException)
        assertTrue(calls.isEmpty())

        releaseNativeCall.complete(Unit)
        runCurrent()
        coordinator.awaitIdle(timeoutMs = 100)
        assertEquals(listOf(Request("old", shutdown = false)), calls)
    }

    @Test
    fun `repeated stop and shutdown requests share one worker and retain shutdown`() = runTest {
        val releaseNativeCall = CompletableDeferred<Unit>()
        val calls = mutableListOf<Request>()
        val coordinator = TorrentCleanupCoordinator<Request>(
            scope = backgroundScope,
            mergePending = ::merge,
            performCleanup = { request ->
                if (request.streamId == "old") releaseNativeCall.await()
                calls += request
            }
        )

        coordinator.request(Request("old", shutdown = false))
        runCurrent()
        repeat(20) { coordinator.request(Request(null, shutdown = true)) }
        assertTrue(calls.isEmpty())

        releaseNativeCall.complete(Unit)
        runCurrent()
        coordinator.awaitIdle(timeoutMs = 100)

        assertEquals(
            listOf(Request("old", shutdown = false), Request(null, shutdown = true)),
            calls
        )
    }

    @Test
    fun `rapid stop start stop start waits for native cleanup to finish`() = runTest {
        val releaseOld = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val coordinator = TorrentCleanupCoordinator<Request>(
            scope = backgroundScope,
            mergePending = ::merge,
            performCleanup = { request ->
                if (request.streamId == "first") releaseOld.await()
                calls += request.streamId.orEmpty()
            }
        )

        coordinator.request(Request("first", shutdown = false))
        runCurrent()
        val restartWhileBlocked = async {
            runCatching { coordinator.awaitIdle(timeoutMs = 50) }.isFailure
        }
        advanceTimeBy(50)
        runCurrent()
        assertTrue(restartWhileBlocked.await())
        assertTrue(calls.isEmpty())

        releaseOld.complete(Unit)
        runCurrent()
        coordinator.awaitIdle(timeoutMs = 100)
        coordinator.request(Request("second", shutdown = false))
        runCurrent()
        coordinator.awaitIdle(timeoutMs = 100)
        assertEquals(listOf("first", "second"), calls)
    }

    @Test
    fun `cancelling a waiting start leaves service owned cleanup running`() = runTest {
        val releaseNativeCall = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val coordinator = TorrentCleanupCoordinator<Request>(
            scope = backgroundScope,
            mergePending = ::merge,
            performCleanup = { request ->
                releaseNativeCall.await()
                calls += request.streamId.orEmpty()
            }
        )

        coordinator.request(Request("old", shutdown = false))
        runCurrent()
        val waitingStart = launch { coordinator.awaitIdle(timeoutMs = 10_000) }
        runCurrent()
        waitingStart.cancel()
        runCurrent()
        assertTrue(waitingStart.isCancelled)

        releaseNativeCall.complete(Unit)
        runCurrent()
        coordinator.awaitIdle(timeoutMs = 100)
        assertEquals(listOf("old"), calls)
    }
}
