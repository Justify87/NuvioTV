package com.nuvio.tv.core.sync.androidtv

import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.core.util.parseEpisodeReleaseInstant
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import com.nuvio.tv.domain.model.WatchProgress
import java.time.Instant
import java.time.ZoneId

/** Immutable projection of Nuvio's existing CW cache into three Android TV channels. */
data class TvChannelFeeds(
    /** Original single, mixed and playable-only Continue Watching feed. */
    val legacy: List<TvChannelEntry>,
    val continueWatching: List<TvChannelEntry>,
    val upcoming: List<TvChannelEntry>,
    val available: List<TvChannelEntry>
) {
    /** Preserve Watch Next ordering and filtering from Nuvio's original mixed channel. */
    fun playableForWatchNext(): List<WatchProgress> = legacy.map { it.progress }
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
        val classic = buildLegacy(inProgress, nextUp, cutoff, dismissedNextUp, useEpisodeThumbnails)
        val started = inProgress
            .asSequence()
            .filter { cutoff == null || it.lastWatched >= cutoff }
            .sortedByDescending { it.lastWatched }
            .distinctBy { it.contentId }
            .map { it.asStartedEntry(useEpisodeThumbnails) }
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
            legacy = classic,
            continueWatching = started,
            upcoming = unaired
                .sortedWith(compareBy<CachedNextUpItem> { it.releaseSortTime() }.thenByDescending { it.lastWatched })
                .map { it.asEntry(useEpisodeThumbnails) },
            available = aired
                .sortedByDescending { it.sortTimestamp }
                .map { it.asEntry(useEpisodeThumbnails) }
        )
    }

    /**
     * Exact selection and order of pre-feature AndroidTvChannelSyncService.buildChannelItems():
     * 1) in-progress and only cached hasAired next-up, 2) age/dismissal filters,
     * 3) started series take priority, 4) a single mixed descending sort,
     * 5) one card per contentId. Metadata additions do not affect selection.
     */
    private fun buildLegacy(
        inProgress: List<CachedInProgressItem>,
        nextUp: List<CachedNextUpItem>,
        cutoff: Long?,
        dismissed: Set<String>,
        thumbnails: Boolean
    ): List<TvChannelEntry> {
        val filteredStarted = inProgress.filter { cutoff == null || it.lastWatched >= cutoff }
        val filteredNext = nextUp
            .filter { it.hasAired }
            .filter { cutoff == null || it.lastWatched >= cutoff }
            .filter { dismissKey(it) !in dismissed }
        val startedIds = filteredStarted.mapTo(mutableSetOf()) { it.contentId }
        val deduplicatedNext = filteredNext.filter { it.contentId !in startedIds }

        data class SortableEntry(val entry: TvChannelEntry, val sortKey: Long)
        val started = filteredStarted.map {
            SortableEntry(it.asStartedEntry(thumbnails), it.lastWatched)
        }
        val next = deduplicatedNext.map {
            SortableEntry(it.asEntry(thumbnails), it.sortTimestamp)
        }
        return (started + next)
            .sortedByDescending { it.sortKey }
            .map { it.entry }
            .distinctBy { it.progress.contentId }
    }

    private fun CachedInProgressItem.asStartedEntry(useEpisodeThumbnails: Boolean): TvChannelEntry =
        TvChannelEntry(
            progress = asProgress(useEpisodeThumbnails),
            episodeDescription = episodeDescription
        )

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
        // Reuse Nuvio's own release parser: date-only values become UTC midnight;
        // timestamped releases retain the supplied instant.
        val at = releaseTimestamp ?: parseEpisodeReleaseInstant(released)?.toEpochMilli()
        return at != null && at <= nowMillis
    }

    private fun CachedNextUpItem.releaseSortTime(): Long {
        releaseTimestamp?.let { return it }
        return parseEpisodeReleaseInstant(released)?.toEpochMilli() ?: Long.MAX_VALUE
    }

    private fun CachedNextUpItem.asEntry(useEpisodeThumbnails: Boolean): TvChannelEntry {
        val date = parseEpisodeReleaseLocalDate(released)?.toString()
            ?: releaseTimestamp?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString()
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
