package com.nuvio.tv.core.sync.androidtv

import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.WatchProgress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Immutable projection of Nuvio's existing CW cache into three Android TV channels. */
data class TvChannelFeeds(
    val continueWatching: List<TvChannelEntry>,
    val upcoming: List<TvChannelEntry>,
    val available: List<TvChannelEntry>
) {
    /** System Watch Next keeps the original playable-only behavior. */
    fun playableForWatchNext(): List<WatchProgress> =
        (continueWatching + available)
            .sortedByDescending { it.progress.lastWatched }
            .map { it.progress }
            .distinctBy { it.contentId }
}

/** No network access or separate library state: use the exact same cached items as Home. */
object TvChannelFeedBuilder {
    fun build(
        inProgress: List<CachedInProgressItem>,
        nextUp: List<CachedNextUpItem>,
        daysCap: Int,
        dismissedNextUp: Set<String>,
        useEpisodeThumbnails: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): TvChannelFeeds {
        val cutoff = if (daysCap == TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL) {
            null
        } else {
            nowMillis - daysCap.toLong() * 24L * 60L * 60L * 1000L
        }
        val started = inProgress
            .asSequence()
            .filter { cutoff == null || it.lastWatched >= cutoff }
            .sortedByDescending { it.lastWatched }
            .distinctBy { it.contentId }
            .map { item ->
                TvChannelEntry(
                    progress = item.asProgress(useEpisodeThumbnails),
                    episodeDescription = item.episodeDescription
                )
            }
            .toList()
        val startedIds = started.mapTo(mutableSetOf()) { it.progress.contentId }
        val next = nextUp
            .asSequence()
            .filter { cutoff == null || it.lastWatched >= cutoff }
            .filter { dismissKey(it) !in dismissedNextUp }
            .filter { it.contentId !in startedIds }
            .distinctBy { it.contentId }
            .toList()
        val (aired, unaired) = next.partition { it.isAvailable(nowMillis) }
        return TvChannelFeeds(
            continueWatching = started,
            upcoming = unaired
                .sortedWith(compareBy<CachedNextUpItem> { it.releaseSortTime() }.thenByDescending { it.lastWatched })
                .map { it.asEntry(useEpisodeThumbnails) },
            available = aired
                .sortedByDescending { it.sortTimestamp }
                .map { it.asEntry(useEpisodeThumbnails) }
        )
    }

    private fun dismissKey(item: CachedNextUpItem): String = buildString {
        append(item.contentId)
        if (item.seedSeason != null) {
            append("_s")
            append(item.seedSeason)
            if (item.seedEpisode != null) {
                append("e")
                append(item.seedEpisode)
            }
        }
    }

    private fun CachedNextUpItem.isAvailable(nowMillis: Long): Boolean {
        if (hasAired) return true
        releaseTimestamp?.let { return it <= nowMillis }
        val date = released?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return false
        val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        // A date without an exact broadcast time is ambiguous on release day.
        // Prefer a one-day delay over exposing an unplayable stream early.
        return date.isBefore(today)
    }

    private fun CachedNextUpItem.releaseSortTime(): Long {
        releaseTimestamp?.let { return it }
        return released?.take(10)?.let {
            runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
        } ?: Long.MAX_VALUE
    }

    private fun CachedNextUpItem.asEntry(useEpisodeThumbnails: Boolean): TvChannelEntry {
        val date = released?.take(10)?.takeIf {
            runCatching { LocalDate.parse(it) }.isSuccess
        } ?: releaseTimestamp?.let {
            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()
        }
        return TvChannelEntry(
            progress = WatchProgress(
                contentId = contentId,
                contentType = contentType,
                name = name,
                poster = poster,
                backdrop = if (useEpisodeThumbnails) thumbnail ?: backdrop else backdrop ?: thumbnail,
                logo = logo,
                videoId = videoId,
                season = season,
                episode = episode,
                episodeTitle = episodeTitle,
                position = 0L,
                duration = 0L,
                lastWatched = sortTimestamp
            ),
            episodeDescription = episodeDescription,
            releaseDate = date,
            isReleaseAlert = isReleaseAlert,
            isNewSeasonRelease = isNewSeasonRelease
        )
    }

    private fun CachedInProgressItem.asProgress(useEpisodeThumbnails: Boolean): WatchProgress =
        WatchProgress(
            contentId = contentId,
            contentType = contentType,
            name = name,
            poster = poster,
            backdrop = if (useEpisodeThumbnails) episodeThumbnail ?: backdrop else backdrop ?: episodeThumbnail,
            logo = logo,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = episodeTitle,
            position = position,
            duration = duration,
            lastWatched = lastWatched,
            progressPercent = progressPercent
        )
}
