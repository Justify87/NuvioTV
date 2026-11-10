package com.nuvio.tv.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PendingRepositoryChangeStoreTest {
    private fun change(id: String) = RepositoryConfigServer.PendingRepoChange(
        id = id,
        proposedUrls = emptyList()
    )

    @Test
    fun `confirmed change remains readable until retention expires`() {
        var now = 0L
        val store = PendingRepositoryChangeStore(clockMillis = { now }, completedRetentionMillis = 1_000)
        store.add(change("confirmed"))
        store.confirm("confirmed")

        assertEquals(RepositoryConfigServer.ChangeStatus.CONFIRMED, store.status("confirmed"))
        now = 999
        assertEquals(RepositoryConfigServer.ChangeStatus.CONFIRMED, store.status("confirmed"))
        now = 1_000
        assertNull(store.status("confirmed"))
    }

    @Test
    fun `rejected change remains readable until retention expires`() {
        var now = 0L
        val store = PendingRepositoryChangeStore(clockMillis = { now }, completedRetentionMillis = 1_000)
        store.add(change("rejected"))
        store.reject("rejected")

        assertEquals(RepositoryConfigServer.ChangeStatus.REJECTED, store.status("rejected"))
        now = 999
        assertEquals(RepositoryConfigServer.ChangeStatus.REJECTED, store.status("rejected"))
        now = 1_000
        assertNull(store.status("rejected"))
    }

    @Test
    fun `pending request survives time until the TV decides`() {
        var now = 0L
        val store = PendingRepositoryChangeStore(clockMillis = { now }, completedRetentionMillis = 500)
        store.add(change("waiting"))

        now = 60 * 60_000L
        assertEquals(RepositoryConfigServer.ChangeStatus.PENDING, store.status("waiting"))
        store.confirm("waiting")
        assertEquals(RepositoryConfigServer.ChangeStatus.CONFIRMED, store.status("waiting"))
        now += 499
        assertEquals(RepositoryConfigServer.ChangeStatus.CONFIRMED, store.status("waiting"))
        now += 1
        assertNull(store.status("waiting"))
    }

    @Test
    fun `new request rejects older pending request without hiding its status`() {
        val store = PendingRepositoryChangeStore()
        store.add(change("old"))
        store.rejectPending()
        store.add(change("new"))

        assertEquals(RepositoryConfigServer.ChangeStatus.REJECTED, store.status("old"))
        assertEquals(RepositoryConfigServer.ChangeStatus.PENDING, store.status("new"))
    }

    @Test
    fun `repeated requests keep completed history bounded and newest request pending`() {
        val store = PendingRepositoryChangeStore(maxEntries = 64)
        repeat(500) { store.add(change("change-$it")) }

        assertTrue(store.size() <= 64)
        assertNull(store.status("change-0"))
        assertEquals(RepositoryConfigServer.ChangeStatus.REJECTED, store.status("change-498"))
        assertEquals(RepositoryConfigServer.ChangeStatus.PENDING, store.status("change-499"))
    }

    @Test
    fun `parallel TV decisions and web status polls keep history bounded`() {
        val store = PendingRepositoryChangeStore(maxEntries = 64)
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val tasks = (0 until 200).map { index ->
                executor.submit {
                    start.await()
                    val id = "parallel-$index"
                    store.add(change(id))
                    if (index % 2 == 0) store.confirm(id) else store.reject(id)
                    store.status(id)
                }
            }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }

            assertTrue(store.size() <= 64)
            assertTrue((0 until 200).count {
                store.status("parallel-$it") == RepositoryConfigServer.ChangeStatus.PENDING
            } <= 1)
        } finally {
            executor.shutdownNow()
        }
    }
}
