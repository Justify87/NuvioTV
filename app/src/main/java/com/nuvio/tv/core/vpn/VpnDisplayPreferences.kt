package com.nuvio.tv.core.vpn

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnDisplayMode(val label: String) { OFF("Aus"), PROBLEMS_ONLY("Nur bei Störung"), ALWAYS("Immer") }
enum class VpnIndicatorPosition(val label: String) { TOP_LEFT("Oben links"), TOP_RIGHT("Oben rechts"), BOTTOM_RIGHT("Unten rechts") }
enum class VpnIndicatorSize(val label: String) { MINI("Mini"), SMALL("Klein"), NORMAL("Normal") }
data class VpnDisplaySettings(
    val showHomeIndicator: Boolean = true,
    val mode: VpnDisplayMode = VpnDisplayMode.PROBLEMS_ONLY,
    val position: VpnIndicatorPosition = VpnIndicatorPosition.TOP_RIGHT,
    val size: VpnIndicatorSize = VpnIndicatorSize.MINI
)
@Singleton
class VpnDisplayPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("vpn_display", Context.MODE_PRIVATE)
    private fun <T : Enum<T>> read(values: Array<T>, key: String, fallback: T) =
        values.firstOrNull { it.name == prefs.getString(key, null) } ?: fallback
    private val current = MutableStateFlow(VpnDisplaySettings(
        showHomeIndicator = prefs.getBoolean("home", true),
        mode = read(VpnDisplayMode.entries.toTypedArray(), "mode", VpnDisplayMode.PROBLEMS_ONLY),
        position = read(VpnIndicatorPosition.entries.toTypedArray(), "position", VpnIndicatorPosition.TOP_RIGHT),
        size = read(VpnIndicatorSize.entries.toTypedArray(), "size", VpnIndicatorSize.MINI)
    ))
    val state: StateFlow<VpnDisplaySettings> = current.asStateFlow()
    fun setHomeIndicator(enabled: Boolean) {
        prefs.edit().putBoolean("home", enabled).apply()
        current.value = current.value.copy(showHomeIndicator = enabled)
    }
    fun cycleMode() {
        val items = VpnDisplayMode.entries
        val next = items[(items.indexOf(current.value.mode) + 1) % items.size]
        prefs.edit().putString("mode", next.name).apply()
        current.value = current.value.copy(mode = next)
    }
    fun cyclePosition() {
        val items = VpnIndicatorPosition.entries
        val next = items[(items.indexOf(current.value.position) + 1) % items.size]
        prefs.edit().putString("position", next.name).apply()
        current.value = current.value.copy(position = next)
    }
    fun cycleSize() {
        val items = VpnIndicatorSize.entries
        val next = items[(items.indexOf(current.value.size) + 1) % items.size]
        prefs.edit().putString("size", next.name).apply()
        current.value = current.value.copy(size = next)
    }
}
