package com.nuvio.tv.core.sync.androidtv

import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvChannelFeedBuilderTest {
    private val now = Instant.parse("2026-11-13T12:00:00Z").toEpochMilli()

    @Test
    fun `places unfinished upcoming and available content in separate channels`() {
        val feeds = build(
            inProgress = listOf(started("movie")),
            nextUp = listOf(
                next("future", "2026-11-19", false),
                next("available", "2026-11-11", true)
            )
        )
        assertEquals(listOf("movie"), feeds.continueWatching.map { it.progress.contentId })
        assertEquals(listOf("future"), feeds.upcoming.map { it.progress.contentId })
        assertEquals(listOf("available"), feeds.available.map { it.progress.contentId })
        assertEquals(listOf("movie", "available"), feeds.playableForWatchNext().map { it.contentId })
        assertEquals("2026-11-19", feeds.upcoming.single().releaseDate)
        assertEquals("Second episode", feeds.upcoming.single().progress.episodeTitle)
    }

    @Test
    fun `cached unaired episode moves to available at the release date`() {
        val feeds = build(nextUp = listOf(next("released", "2026-11-12", false)))
        assertTrue(feeds.upcoming.isEmpty())
        assertEquals("released", feeds.available.single().progress.contentId)
    }

    @Test
    fun `date only follows Nuvio UTC midnight release semantics`() {
        val midnightUtc = Instant.parse("2026-11-13T00:00:00Z").toEpochMilli()
        val feeds = build(nextUp = listOf(next("today", "2026-11-13", false, midnightUtc)))
        assertEquals("today", feeds.available.single().progress.contentId)
        assertTrue(feeds.upcoming.isEmpty())
    }

    @Test
    fun `precise future timestamp takes precedence over a calendar date`() {
        val future = now + 2L * 86_400_000
        val feeds = build(nextUp = listOf(next("future", "2026-11-13", false, future)))
        assertEquals("future", feeds.upcoming.single().progress.contentId)
        assertTrue(feeds.available.isEmpty())
    }

    @Test
    fun `in progress wins over next up for the same show`() {
        val feeds = build(
            inProgress = listOf(started("show")),
            nextUp = listOf(next("show", "2026-11-19", false))
        )
        assertEquals(1, feeds.continueWatching.size)
        assertTrue(feeds.upcoming.isEmpty())
        assertTrue(feeds.available.isEmpty())
    }

    @Test
    fun `dismissed entries and days cap are respected`() {
        val old = next("old", "2026-11-19", false, lastWatched = now - 4L * 86_400_000)
        val hidden = next("hidden", "2026-11-19", false, lastWatched = now - 60_000)
            .copy(seedSeason = 1, seedEpisode = 1)
        val feeds = TvChannelFeedBuilder.build(
            emptyList(), listOf(old, hidden), daysCap = 1,
            dismissedNextUp = setOf("hidden_s1e1"), useEpisodeThumbnails = true, nowMillis = now
        )
        assertTrue(feeds.upcoming.isEmpty())
    }

    @Test
    fun `upcoming episodes are ordered by nearest release`() {
        val feeds = build(nextUp = listOf(
            next("later", "2026-11-30", false),
            next("sooner", "2026-11-14", false)
        ))
        assertEquals(listOf("sooner", "later"), feeds.upcoming.map { it.progress.contentId })
    }

    private fun build(
        inProgress: List<CachedInProgressItem> = emptyList(),
        nextUp: List<CachedNextUpItem> = emptyList()
    ) = TvChannelFeedBuilder.build(
        inProgress, nextUp, daysCap = 0, dismissedNextUp = emptySet(),
        useEpisodeThumbnails = true, nowMillis = now
    )

    private fun started(id: String) = CachedInProgressItem(
        contentId = id, contentType = "movie", name = id,
        poster = null, backdrop = null, logo = null,
        videoId = "video-$id", season = null, episode = null, episodeTitle = null,
        position = 20_000, duration = 90_000, lastWatched = now - 10_000,
        progressPercent = 22f
    )

    private fun next(
        id: String,
        released: String,
        hasAired: Boolean,
        releaseTimestamp: Long? = null,
        lastWatched: Long = now - 20_000
    ) = CachedNextUpItem(
        contentId = id, contentType = "series", name = id,
        poster = null, backdrop = null, logo = null,
        videoId = "video-$id", season = 1, episode = 2,
        episodeTitle = "Second episode", thumbnail = null,
        released = released, hasAired = hasAired,
        lastWatched = lastWatched, sortTimestamp = lastWatched,
        releaseTimestamp = releaseTimestamp, isReleaseAlert = hasAired
    )
}
