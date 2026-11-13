package com.nuvio.tv.core.sync.androidtv

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tvChannelDataStore by preferencesDataStore(
    name = "tv_channel_prefs",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

@Singleton
class TvChannelPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val channelIdKey = longPreferencesKey("continue_watching_channel_id")
    private val upcomingChannelIdKey = longPreferencesKey("upcoming_channel_id")
    private val availableChannelIdKey = longPreferencesKey("available_channel_id")

    private fun keyFor(kind: TvChannelKind) = when (kind) {
        TvChannelKind.CONTINUE -> channelIdKey
        TvChannelKind.UPCOMING -> upcomingChannelIdKey
        TvChannelKind.AVAILABLE -> availableChannelIdKey
    }

    suspend fun getChannelId(kind: TvChannelKind): Long? =
        context.tvChannelDataStore.data.map { it[keyFor(kind)] }.first()

    suspend fun setChannelId(kind: TvChannelKind, id: Long) {
        context.tvChannelDataStore.edit { it[keyFor(kind)] = id }
    }

    suspend fun clearChannelId(kind: TvChannelKind) {
        context.tvChannelDataStore.edit { it.remove(keyFor(kind)) }
    }

    suspend fun getChannelId(): Long? =
        context.tvChannelDataStore.data.map { it[channelIdKey] }.first()

    suspend fun setChannelId(id: Long) {
        context.tvChannelDataStore.edit { it[channelIdKey] = id }
    }

    suspend fun clearChannelId() {
        context.tvChannelDataStore.edit { it.remove(channelIdKey) }
    }
}
