package com.nuvio.tv.core.sync.androidtv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.nuvio.tv.MainActivity
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.WatchProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private const val TAG = "TvChannelSync"

@Singleton
class AndroidTvChannelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: TvChannelPreferences,
) {
    private val syncedProgramFingerprints = ConcurrentHashMap<String, ChannelProgramFingerprint>()
    private val reconciliationMutex = Mutex()

    private data class ChannelProgramFingerprint(
        val title: String?,
        val position: Int?,
        val duration: Int?,
        val imageUri: String?,
        val logoUri: String?,
        val sortOrder: Int,
        val season: Int?,
        val episode: Int?,
        val lastEngagementTime: Long,
        val description: String?,
        val episodeDescription: String?,
        val releaseDate: String?,
        val launchMode: String?
    )

    fun isSupported(): Boolean = try {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    } catch (_: Exception) {
        false
    }

    /**
     * Returns the channel id, creating or reusing the channel as needed.
     * Returns null on non-leanback devices or on failure.
     */
    suspend fun ensureChannel(kind: TvChannelKind = TvChannelKind.CONTINUE): Long? = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext null
        runCatching {
            val stored = if (kind == TvChannelKind.CONTINUE) prefs.getChannelId() else prefs.getChannelId(kind)
            if (stored != null) {
                val cursor = context.contentResolver.query(
                    TvContractCompat.buildChannelUri(stored),
                    arrayOf(TvContractCompat.Channels._ID),
                    null, null, null
                )
                cursor?.use { if (it.moveToFirst()) return@runCatching stored }
                Log.d(TAG, "Stored channel $stored gone; recreating")
                prefs.clearChannelId(kind)
            }

            val orphan = context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(
                    TvContractCompat.Channels._ID,
                    TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null, null, null
            )?.use { c ->
                val idIdx = c.getColumnIndex(TvContractCompat.Channels._ID)
                val providerIdx = c.getColumnIndex(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)
                if (idIdx < 0 || providerIdx < 0) return@use null
                while (c.moveToNext()) {
                    val providerId = c.getString(providerIdx)
                    if (providerId == providerKey(kind)) {
                        return@use c.getLong(idIdx)
                    }
                }
                null
            }
            if (orphan != null) {
                Log.d(TAG, "Reusing orphaned channel $orphan")
                saveChannelId(kind, orphan)
                writeChannelLogo(orphan)
                return@runCatching orphan
            }

            val appLinkUri = Uri.parse(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }.toUri(Intent.URI_INTENT_SCHEME)
            )
            val channel = Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(context.getString(kind.titleRes))
                .setAppLinkIntentUri(appLinkUri)
                .setInternalProviderId(providerKey(kind))
                .build()

            val inserted = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI,
                channel.toContentValues()
            ) ?: return@runCatching null

            val id = ContentUris.parseId(inserted)
            saveChannelId(kind, id)
            writeChannelLogo(id)
            // Avoid three consecutive platform approval prompts on first startup.
            // Extra channels are selectable in the TV launcher's channel settings.
            if (kind == TvChannelKind.CONTINUE) TvContractCompat.requestChannelBrowsable(context, id)
            Log.d(TAG, "Created channel id=$id")
            id
        }.onFailure { Log.w(TAG, "ensureChannel failed", it) }.getOrNull()
    }

    private fun providerKey(kind: TvChannelKind): String = context.packageName + kind.providerSuffix

    private suspend fun saveChannelId(kind: TvChannelKind, id: Long) {
        if (kind == TvChannelKind.CONTINUE) prefs.setChannelId(id) else prefs.setChannelId(kind, id)
    }

    /**
     * Syncs [items] to the Continue Watching channel: upserts present items and
     * removes rows that are no longer in the list (completed or dismissed).
     */
    suspend fun reconcile(items: List<WatchProgress>) = reconciliationMutex.withLock {
        reconcileKind(TvChannelKind.CONTINUE, items.map { TvChannelEntry(it) })
    }

    suspend fun reconcileChannels(feeds: TvChannelFeeds) = reconciliationMutex.withLock {
        reconcileKind(TvChannelKind.CONTINUE, feeds.continueWatching)
        reconcileKind(TvChannelKind.UPCOMING, feeds.upcoming)
        reconcileKind(TvChannelKind.AVAILABLE, feeds.available)
    }

    private suspend fun reconcileKind(kind: TvChannelKind, items: List<TvChannelEntry>) = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        runCatching {
            val channelId = ensureChannel(kind) ?: return@runCatching
            val existing = queryExistingPrograms(channelId)
            val desiredKeys = items.map { progressKey(it.progress) }.toSet()

            for ((key, rowIds) in existing) {
                if (key !in desiredKeys) {
                    rowIds.forEach { rowId ->
                        context.contentResolver.delete(
                            TvContractCompat.buildPreviewProgramUri(rowId), null, null
                        )
                        Log.d(TAG, "Removed program key=$key rowId=$rowId")
                    }
                    syncedProgramFingerprints.remove("$channelId:$key")
                }
            }

            items.forEachIndexed { index, entry ->
                val progress = entry.progress
                val key = progressKey(progress)
                val rowIds = existing[key]

                val (imageUri, _) = when {
                    !progress.backdrop.isNullOrBlank() -> progress.backdrop to TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
                    !progress.poster.isNullOrBlank() -> progress.poster to TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
                    else -> null to null
                }
                val positionMs = if (progress.position > 0) {
                    progress.position.toInt()
                } else {
                    (progress.progressPercent?.let { it / 100f * progress.duration }?.toLong() ?: 0L).toInt()
                }

                val newFingerprint = ChannelProgramFingerprint(
                    title = progress.name,
                    position = positionMs,
                    duration = progress.duration.toInt(),
                    imageUri = imageUri,
                    logoUri = progress.logo,
                    sortOrder = index,
                    season = progress.season,
                    episode = progress.episode,
                    lastEngagementTime = progress.lastWatched,
                    description = descriptionFor(entry, kind),
                    episodeDescription = entry.episodeDescription,
                    releaseDate = entry.releaseDate,
                    launchMode = launchModeFor(entry, kind)
                )
                val fingerprintKey = "$channelId:$key"
                val oldFingerprint = syncedProgramFingerprints[fingerprintKey]

                if (!rowIds.isNullOrEmpty() && oldFingerprint != null &&
                    oldFingerprint.title == newFingerprint.title &&
                    oldFingerprint.imageUri == newFingerprint.imageUri &&
                    oldFingerprint.logoUri == newFingerprint.logoUri &&
                    oldFingerprint.sortOrder == newFingerprint.sortOrder &&
                    oldFingerprint.season == newFingerprint.season &&
                    oldFingerprint.episode == newFingerprint.episode &&
                    oldFingerprint.duration == newFingerprint.duration &&
                    oldFingerprint.description == newFingerprint.description &&
                    oldFingerprint.episodeDescription == newFingerprint.episodeDescription &&
                    oldFingerprint.releaseDate == newFingerprint.releaseDate &&
                    oldFingerprint.launchMode == newFingerprint.launchMode &&
                    oldFingerprint.lastEngagementTime == newFingerprint.lastEngagementTime &&
                    abs((oldFingerprint.position ?: 0) - (newFingerprint.position ?: 0)) < 2_000
                ) {
                    // Item already in sync, skip database update to prevent waking up launcher
                    return@forEachIndexed
                }

                val values = buildProgramValues(entry, kind, channelId, index, key)
                if (!rowIds.isNullOrEmpty()) {
                    val primaryRowId = rowIds.first()
                    // UPDATE the existing program row in place — keeping its row ID stable.
                    context.contentResolver.update(
                        TvContractCompat.buildPreviewProgramUri(primaryRowId), values, null, null
                    )
                    // If there are duplicate rows for the same key, delete them to clean up the database.
                    if (rowIds.size > 1) {
                        rowIds.drop(1).forEach { extraRowId ->
                            context.contentResolver.delete(
                                TvContractCompat.buildPreviewProgramUri(extraRowId), null, null
                            )
                            Log.d(TAG, "Removed duplicate program key=$key rowId=$extraRowId")
                        }
                    }
                } else {
                    context.contentResolver.insert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI, values
                    )
                }
                syncedProgramFingerprints[fingerprintKey] = newFingerprint
                Log.d(TAG, "${if (!rowIds.isNullOrEmpty()) "Updated" else "Inserted"} program key=$key pos=${values.getAsInteger("last_playback_position_millis")} dur=${values.getAsInteger("duration_millis")} pct=${progress.progressPercent}")
            }
        }.onFailure { Log.w(TAG, "reconcile failed", it) }
    }

    /** Removes all preview programs from our channel (used on sign-out / history clear). */
    suspend fun clearAll() = reconciliationMutex.withLock {
        clearAllLocked()
    }

    private suspend fun clearAllLocked() = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        runCatching {
            val channelIds = listOfNotNull(
                prefs.getChannelId(),
                prefs.getChannelId(TvChannelKind.UPCOMING),
                prefs.getChannelId(TvChannelKind.AVAILABLE)
            ).distinct()
            var deletedCount = 0
            channelIds.forEach { channelId ->
                queryExistingPrograms(channelId).values.flatten().forEach { rowId ->
                    context.contentResolver.delete(
                        TvContractCompat.buildPreviewProgramUri(rowId), null, null
                    )
                    deletedCount++
                }
            }
            syncedProgramFingerprints.clear()
            Log.d(TAG, "Cleared $deletedCount programs for channel $channelId")
        }.onFailure { Log.w(TAG, "clearAll failed", it) }
    }

    // ---

    private fun queryExistingPrograms(channelId: Long): Map<String, List<Long>> {
        val projection = arrayOf(
            TvContractCompat.PreviewPrograms._ID,
            TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID
        )
        val result = mutableMapOf<String, MutableList<Long>>()
        // Fire OS rejects any selection clause on preview_program URIs with SecurityException;
        // the provider auto-scopes to the calling package, so we filter channelId in memory.
        context.contentResolver.query(
            TvContractCompat.PreviewPrograms.CONTENT_URI,
            projection,
            null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms._ID)
            val channelIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID)
            val keyIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            while (c.moveToNext()) {
                if (c.getLong(channelIdx) != channelId) continue
                val key = c.getString(keyIdx) ?: continue
                result.getOrPut(key) { mutableListOf() }.add(c.getLong(idIdx))
            }
        }
        return result
    }

    private fun buildProgramValues(
        entry: TvChannelEntry,
        kind: TvChannelKind,
        channelId: Long,
        sortOrder: Int,
        key: String
    ): ContentValues {
        val progress = entry.progress
        val launchMode = launchModeFor(entry, kind)
        val intentUri = Uri.parse(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("contentId", progress.contentId)
                putExtra("contentType", progress.contentType)
                putExtra("videoId", progress.videoId)
                putExtra("name", progress.name)
                putExtra("poster", progress.poster)
                putExtra("backdrop", progress.backdrop)
                putExtra("logo", progress.logo)
                progress.season?.let { putExtra("season", it) }
                progress.episode?.let { putExtra("episode", it) }
                progress.episodeTitle?.let { putExtra("episodeTitle", it) }
                putExtra("launchMode", launchMode)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }.toUri(Intent.URI_INTENT_SCHEME)
        )

        val type = if (progress.contentType.equals("movie", ignoreCase = true))
            TvContractCompat.PreviewPrograms.TYPE_MOVIE
        else
            TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE

        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(type)
            .setTitle(progress.name)
            .setIntentUri(intentUri)
            .setInternalProviderId(key)
            .setWeight(Int.MAX_VALUE - sortOrder)
        descriptionFor(entry, kind)?.let { builder.setDescription(it) }
        entry.episodeDescription?.takeIf { it.isNotBlank() }?.let { builder.setLongDescription(it) }
        entry.releaseDate?.let { builder.setReleaseDate(it) }

        // Backdrop/poster fills the tile via posterArt; logo goes to the dedicated logo column
        // so the launcher renders it as a small badge overlay on focus.
        val (imageUri, aspectRatio) = when {
            !progress.backdrop.isNullOrBlank() ->
                progress.backdrop to TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
            !progress.poster.isNullOrBlank() ->
                progress.poster to TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
            else -> null to null
        }
        imageUri?.let { builder.setPosterArtUri(Uri.parse(it)).setPosterArtAspectRatio(aspectRatio!!) }
        progress.logo?.let { builder.setLogoUri(Uri.parse(it)) }

        if (progress.duration > 0) {
            builder.setDurationMillis(progress.duration.toInt())
            val positionMs = if (progress.position > 0) {
                progress.position.toInt()
            } else {
                (progress.progressPercent?.let { it / 100f * progress.duration }?.toLong() ?: 0L).toInt()
            }
            builder.setLastPlaybackPositionMillis(positionMs)
        } else if (progress.progressPercent != null && progress.progressPercent > 0f) {
            // No real duration known (e.g. Simkl/Trakt sync), but we have a percent.
            // Use synthetic values so the launcher can render a progress bar.
            val syntheticDuration = 100_000
            val syntheticPosition = (progress.progressPercent / 100f * syntheticDuration).toInt()
            builder.setDurationMillis(syntheticDuration)
            builder.setLastPlaybackPositionMillis(syntheticPosition)
        }

        if (type == TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE) {
            progress.season?.let { builder.setSeasonNumber(it) }
            progress.episode?.let { builder.setEpisodeNumber(it) }
            progress.episodeTitle?.let { builder.setEpisodeTitle(it) }
        }

        return builder.build().toContentValues().also {
            // COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS drives launcher ordering;
            // the Builder method was added after tvprovider 1.0.0, so set directly.
            it.put("last_engagement_time_utc_millis", progress.lastWatched)
            // Explicitly clear poster art when no image is available, so UPDATE operations
            // don't leave stale artwork from previous reconcile cycles.
            if (entry.releaseDate == null) it.putNull("release_date")
            if (entry.episodeDescription.isNullOrBlank()) it.putNull("long_description")
            if (descriptionFor(entry, kind) == null) it.putNull("short_description")
            if (imageUri == null) {
                it.putNull(TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI)
            }
            if (progress.logo.isNullOrBlank()) {
                it.putNull(TvContractCompat.PreviewPrograms.COLUMN_LOGO_URI)
            }
            // Clear duration/position when unknown so stale values (e.g. previous 1hr fallback)
            // don't persist across UPDATE cycles.
            if (progress.duration <= 0 && (progress.progressPercent == null || progress.progressPercent <= 0f)) {
                it.putNull("duration_millis")
                it.putNull("last_playback_position_millis")
            }
        }
    }

    private fun launchModeFor(entry: TvChannelEntry, kind: TvChannelKind): String =
        if (kind == TvChannelKind.UPCOMING || entry.progress.videoId.isBlank()) "detail" else "stream"

    private fun descriptionFor(entry: TvChannelEntry, kind: TvChannelKind): String? = when (kind) {
        TvChannelKind.CONTINUE -> null
        TvChannelKind.AVAILABLE -> context.getString(
            when {
                entry.isReleaseAlert && entry.isNewSeasonRelease -> R.string.cw_new_season
                entry.isReleaseAlert -> R.string.cw_new_episode
                else -> R.string.cw_next_up
            }
        )
        TvChannelKind.UPCOMING -> {
            val release = entry.releaseDate
            val days = release?.let {
                runCatching {
                    java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.LocalDate.now(), java.time.LocalDate.parse(it)
                    )
                }.getOrNull()
            }
            if (days != null && days >= 0) {
                if (days == 0L) {
                    context.getString(R.string.tv_channel_releases_today)
                } else {
                    val count = days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    context.resources.getQuantityString(R.plurals.tv_channel_days_to_release, count, count)
                }
            } else {
                context.getString(R.string.cw_upcoming)
            }
        }
    }

    private fun writeChannelLogo(channelId: Long) {
        runCatching {
            val bitmap = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
                ?: return
            context.contentResolver.openOutputStream(
                TvContractCompat.buildChannelLogoUri(channelId)
            )?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { Log.w(TAG, "writeChannelLogo failed", it) }
    }

    /**
     * Mirrors WatchProgressPreferences.createKey() so channel and storage agree on identity.
     */
    private fun progressKey(progress: WatchProgress): String =
        if (progress.season != null && progress.episode != null)
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        else
            progress.contentId
}
