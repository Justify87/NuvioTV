package com.nuvio.tv.core.server

/** Keeps TV approval requests available briefly for web status polling. */
internal class PendingAddonChangeStore(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val completedRetentionMillis: Long = 5 * 60_000L,
    private val maxEntries: Int = 64
) {
    private data class Entry(
        val change: PendingAddonChange,
        var completedAtMillis: Long? = null
    )

    private val entries = LinkedHashMap<String, Entry>()

    init {
        require(completedRetentionMillis > 0 && maxEntries > 0)
    }

    @Synchronized
    fun rejectPending() {
        val now = clockMillis()
        prune(now)
        rejectPendingAt(now)
    }

    @Synchronized
    fun add(change: PendingAddonChange) {
        val now = clockMillis()
        prune(now)
        // A newer request supersedes any older TV confirmation request.
        rejectPendingAt(now)
        entries[change.id] = Entry(change)
        while (entries.size > maxEntries) {
            val oldestCompleted = entries.entries.firstOrNull { it.value.completedAtMillis != null }
                ?: break
            entries.remove(oldestCompleted.key)
        }
    }

    @Synchronized
    fun confirm(id: String) = finish(id, AddonChangeStatus.CONFIRMED)

    @Synchronized
    fun reject(id: String) = finish(id, AddonChangeStatus.REJECTED)

    @Synchronized
    fun status(id: String): AddonChangeStatus? {
        prune(clockMillis())
        return entries[id]?.change?.status
    }

    @Synchronized
    internal fun size(): Int {
        prune(clockMillis())
        return entries.size
    }

    private fun finish(id: String, status: AddonChangeStatus) {
        val now = clockMillis()
        prune(now)
        entries[id]?.let {
            it.change.status = status
            it.completedAtMillis = now
        }
    }

    private fun rejectPendingAt(now: Long) {
        entries.values.forEach { entry ->
            if (entry.completedAtMillis == null) {
                entry.change.status = AddonChangeStatus.REJECTED
                entry.completedAtMillis = now
            }
        }
    }

    private fun prune(now: Long) {
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val completedAt = iterator.next().completedAtMillis
            if (completedAt != null && now - completedAt >= completedRetentionMillis) {
                iterator.remove()
            }
        }
    }
}
