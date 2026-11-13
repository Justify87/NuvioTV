package com.nuvio.tv.core.sync.androidtv

import com.nuvio.tv.R

/**
 * Four independent Android TV preview channels: CONTINUE is the exact legacy mixed
 * channel (same provider ID/DataStore key). IN_PROGRESS/UPCOMING/AVAILABLE are opt-in
 * additional channels; installing the update never replaces the original channel.
 */
enum class TvChannelKind(val providerSuffix: String, val titleRes: Int) {
    CONTINUE("", R.string.tv_channel_continue_watching),
    IN_PROGRESS(":in-progress", R.string.tv_channel_in_progress),
    UPCOMING(":upcoming", R.string.tv_channel_upcoming),
    AVAILABLE(":available", R.string.tv_channel_available)
}

/** Additional fields for the Android TV provider. The in-app CW cache remains the source of truth. */
data class TvChannelEntry(
    val progress: com.nuvio.tv.domain.model.WatchProgress,
    val episodeDescription: String? = null,
    val releaseDate: String? = null,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false
)
