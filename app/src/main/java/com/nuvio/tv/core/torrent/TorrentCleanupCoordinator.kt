package com.nuvio.tv.core.torrent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Runs at most one cleanup worker. Requests arriving during native cleanup are coalesced;
 * callers may stop waiting without cancelling the service-owned worker.
 */
internal class TorrentCleanupCoordinator<T : Any>(
    private val scope: CoroutineScope,
    private val mergePending: (T, T) -> T,
    private val performCleanup: suspend (T) -> Unit
) {
    private val lock = Any()
    private var pending: T? = null
    private var worker: Job? = null
    private var failure: Throwable? = null

    fun request(task: T): Boolean = synchronized(lock) {
        if (failure != null) return@synchronized false
        pending = pending?.let { mergePending(it, task) } ?: task
        if (worker == null) startWorkerLocked()
        true
    }

    suspend fun awaitIdle(timeoutMs: Long) {
        withTimeout(timeoutMs) {
            while (true) {
                val (activeWorker, error) = synchronized(lock) { worker to failure }
                if (error != null) throw IllegalStateException("Torrent cleanup failed", error)
                if (activeWorker == null) return@withTimeout
                activeWorker.join()
            }
        }
    }

    private fun startWorkerLocked() {
        worker = scope.launch(start = CoroutineStart.LAZY) { drain() }.also { it.start() }
    }

    private suspend fun drain() {
        try {
            while (true) {
                val task = synchronized(lock) { pending.also { pending = null } } ?: return
                performCleanup(task)
            }
        } catch (error: Throwable) {
            synchronized(lock) {
                failure = error
                pending = null
            }
            throw error
        } finally {
            synchronized(lock) {
                worker = null
                // A request may have arrived just after the worker observed an empty queue.
                if (pending != null && failure == null && scope.isActive) startWorkerLocked()
            }
        }
    }
}
