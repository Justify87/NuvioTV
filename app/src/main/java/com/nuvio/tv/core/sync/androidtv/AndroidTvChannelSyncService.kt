package com.nuvio.tv.core.sync.androidtv

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.poster.patternForScreen
import com.nuvio.tv.core.poster.withCustomPosterArt
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TvChannelSync"
private const val DEBOUNCE_MS = 2_000L

/**
 * Preserves Nuvio's original mixed Continue Watching Android TV preview channel and
 * additionally publishes three optional categorized channels from the same enrichment
 * cache. The Home pipeline remains the source of watch/episode state. System Watch
 * Next retains the legacy mixed order; upcoming episodes open the detail screen.
 */
@Singleton
class AndroidTvChannelSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: AndroidTvChannelManager,
    private val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val tvRecommendationManager: TvRecommendationManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    // The launcher channel is only visible while the app is in the background. Reconciling on
    // every in-playback cache bump (~10s) thrashes the TvProvider and stops launchers like
    // Projectivy from repainting. We instead reconcile once when the app goes to background
    // (user returns to the launcher) — see [onForegroundChanged].
    @Volatile private var appInForeground = false

    /** Called from the host Activity's onStart/onStop. On background we reconcile once so the
     *  channel reflects the latest watch progress exactly as the launcher regains foreground. */
    fun onForegroundChanged(foreground: Boolean) {
        val wasForeground = appInForeground
        appInForeground = foreground
        if (wasForeground && !foreground) {
            scope.launch { reconcileFromCache() }
        }
    }

    @OptIn(FlowPreview::class)
    fun start() {
        if (!manager.isSupported()) {
            Log.d(TAG, "Non-leanback device; channel sync skipped")
            return
        }
        TvChannelRefreshJobService.schedulePeriodic(context)

        scope.launch {
            // Observe cache snapshot updates and settings changes to trigger reconciliation.
            // snapshotVersion bumps every time the CW pipeline writes new data to disk cache.
            // We drop the initial value (0) to avoid reconciling with stale disk cache before
            // the pipeline has had a chance to produce fresh data.
            combine(
                cwEnrichmentCache.snapshotVersion
                    .dropWhile { it == 0 },
                traktSettingsDataStore.continueWatchingDaysCap,
                traktSettingsDataStore.dismissedNextUpKeys,
                layoutPreferenceDataStore.useEpisodeThumbnailsInCw
            ) { _, daysCap, dismissed, useEpisodeThumbnails ->
                ChannelSettingsSnapshot(daysCap, dismissed, useEpisodeThumbnails)
            }
                .debounce(DEBOUNCE_MS)
                .collect { settings ->
                    // Skip while the app is foregrounded — the launcher channel isn't visible
                    // then, and reconciling on every ~10s in-playback cache bump thrashes the
                    // provider (and stops launchers like Projectivy from repainting). The
                    // background transition (onForegroundChanged) + periodic job cover it.
                    if (appInForeground) return@collect
                    reconcileFromCache(settings)
                }
        }
    }

    /**
     * Reads the CW enrichment cache and reconciles the launcher channel.
     * Called both from the live observer and from [TvChannelRefreshJobService].
     */
    suspend fun reconcileFromCache(settings: ChannelSettingsSnapshot? = null) {
        syncMutex.withLock {
        val resolvedSettings = settings ?: run {
            val daysCap = traktSettingsDataStore.continueWatchingDaysCap.first()
            val dismissed = traktSettingsDataStore.dismissedNextUpKeys.first()
            val useEpisodeThumbnails = layoutPreferenceDataStore.useEpisodeThumbnailsInCw.first()
            ChannelSettingsSnapshot(daysCap, dismissed, useEpisodeThumbnails)
        }

        val inProgressItems = runCatching { cwEnrichmentCache.getInProgressSnapshot() }
            .getOrDefault(emptyList())
        val nextUpItems = runCatching { cwEnrichmentCache.getNextUpSnapshot() }
            .getOrDefault(emptyList())

        val posterPattern = patternForScreen(
            layoutPreferenceDataStore.customPosterUrlPattern.first(),
            CustomPosterScreen.TV_RECOMMENDATIONS,
            layoutPreferenceDataStore.customPosterEnabledScreens.first()
        )
        val rawFeeds = TvChannelFeedBuilder.build(
            inProgress = inProgressItems,
            nextUp = nextUpItems,
            daysCap = resolvedSettings.daysCap,
            dismissedNextUp = resolvedSettings.dismissedNextUp,
            useEpisodeThumbnails = resolvedSettings.useEpisodeThumbnails
        )
        fun decorate(items: List<TvChannelEntry>): List<TvChannelEntry> {
            val decorated = items.map { it.progress }.withCustomPosterArt(posterPattern)
            return items.zip(decorated) { item, progress -> item.copy(progress = progress) }
        }
        val feeds = TvChannelFeeds(
            legacy = decorate(rawFeeds.legacy),
            continueWatching = decorate(rawFeeds.continueWatching),
            upcoming = decorate(rawFeeds.upcoming),
            available = decorate(rawFeeds.available)
        )
        val channelItems = feeds.playableForWatchNext()

        Log.d(
            TAG,
            "Reconciling from cache: ${feeds.legacy.size} classic, " +
                "${feeds.continueWatching.size} started, " +
                "${feeds.upcoming.size} upcoming, ${feeds.available.size} available " +
                "(${inProgressItems.size} in-progress, ${nextUpItems.size} next-up raw)"
        )
        manager.reconcileChannels(feeds)

        runCatching {
            tvRecommendationManager.updateWatchNext(channelItems)
        }
        }
    }

    data class ChannelSettingsSnapshot(
        val daysCap: Int,
        val dismissedNextUp: Set<String>,
        val useEpisodeThumbnails: Boolean
    )
}
