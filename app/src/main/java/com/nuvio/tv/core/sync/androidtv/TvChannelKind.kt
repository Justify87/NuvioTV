package com.nuvio.tv.core.sync.androidtv

import com.nuvio.tv.R

/**
 * Android TV preview channels. CONTINUE preserves the original channel's provider ID and
 * DataStore key, so upgrades keep the user's existing launcher channel selection.
 */
enum class TvChannelKind(val providerSuffix: String, val titleRes: Int) {
    CONTINUE("", R.string.tv_channel_continue_watching),
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
