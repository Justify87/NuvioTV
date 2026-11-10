package com.nuvio.tv.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PendingAddonChangeStoreTest {
    private fun change(id: String) = PendingAddonChange(id = id, proposedUrls = emptyList())

    @Test
    fun `confirmed change remains readable until retention ends`() {
        var now = 0L
        val store = PendingAddonChangeStore(clockMillis = { now }, completedRetentionMillis = 1_000)
        store.add(change("confirmed"))
        store.confirm("confirmed")

        assertEquals(AddonChangeStatus.CONFIRMED, store.status("confirmed"))
        now = 999
        assertEquals(AddonChangeStatus.CONFIRMED, store.status("confirmed"))
        now = 1_000
        assertNull(store.status("confirmed"))
    }

    @Test
    fun `rejected change remains readable until retention ends`() {
        var now = 0L
        val store = PendingAddonChangeStore(clockMillis = { now }, completedRetentionMillis = 1_000)
        store.add(change("rejected"))
        store.reject("rejected")

        assertEquals(AddonChangeStatus.REJECTED, store.status("rejected"))
        now = 999
        assertEquals(AddonChangeStatus.REJECTED, store.status("rejected"))
        now = 1_000
        assertNull(store.status("rejected"))
    }

    @Test
    fun `unanswered request survives time until the TV can confirm it`() {
        var now = 0L
        val store = PendingAddonChangeStore(clockMillis = { now }, completedRetentionMillis = 500)
        store.add(change("waiting"))

        now = 60 * 60_000L
        assertEquals(AddonChangeStatus.PENDING, store.status("waiting"))
        store.confirm("waiting")
        assertEquals(AddonChangeStatus.CONFIRMED, store.status("waiting"))
        now += 499
        assertEquals(AddonChangeStatus.CONFIRMED, store.status("waiting"))
        now += 1
        assertNull(store.status("waiting"))
    }

    @Test
    fun `new change rejects prior pending change but preserves its status`() {
        val store = PendingAddonChangeStore()
        store.add(change("old"))
        store.add(change("new"))

        assertEquals(AddonChangeStatus.REJECTED, store.status("old"))
        assertEquals(AddonChangeStatus.PENDING, store.status("new"))
    }

    @Test
    fun `many new changes keep the registry bounded and the latest pending`() {
        val store = PendingAddonChangeStore(maxEntries = 64)
        repeat(500) { store.add(change("change-$it")) }

        assertTrue(store.size() <= 64)
        assertNull(store.status("change-0"))
        assertEquals(AddonChangeStatus.REJECTED, store.status("change-498"))
        assertEquals(AddonChangeStatus.PENDING, store.status("change-499"))
    }

    @Test
    fun `parallel changes and status polls remain consistent and bounded`() {
        val store = PendingAddonChangeStore(maxEntries = 64)
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
                store.status("parallel-$it") == AddonChangeStatus.PENDING
            } <= 1)
        } finally {
            executor.shutdownNow()
        }
    }
}
