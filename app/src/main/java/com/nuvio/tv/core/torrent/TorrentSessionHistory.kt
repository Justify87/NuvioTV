package com.nuvio.tv.core.torrent

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** In-memory live telemetry; opt-in, bounded, device-local session history. */
data class TorrentRateSample(
    val timestampMs: Long,
    val downloadBytesPerSecond: Long,
    val uploadBytesPerSecond: Long,
    val peers: Int
)
data class TorrentSessionStats(
    val title: String,
    val startedAtMs: Long,
    val durationMs: Long = 0L,
    val resolutionHint: String = "Unbekannt",
    val averageDownloadBps: Long = 0L,
    val peakDownloadBps: Long = 0L,
    val averageUploadBps: Long = 0L,
    val peakPeers: Int = 0,
    val peakSeeds: Int = 0,
    val downloadedBytes: Long = 0L,
    val bufferEvents: Int = 0,
    val samples: Int = 0,
    val failed: Boolean = false
) {
    val rating: String get() = torrentSessionRating(this)
}

/** This is a subjective summary of observed session metrics, not a torrent-wide rating. */
fun torrentSessionRating(s: TorrentSessionStats): String = when {
    s.failed -> "Fehlgeschlagen"
    s.bufferEvents >= 3 -> "Instabil"
    s.samples == 0 -> "Keine Messdaten"
    s.averageDownloadBps >= 4L * 1024 * 1024 && s.peakPeers >= 5 && s.bufferEvents == 0 -> "Sehr gut"
    s.averageDownloadBps >= 1500L * 1024 && s.peakPeers >= 2 && s.bufferEvents < 2 -> "Gut"
    s.averageDownloadBps >= 400L * 1024 && s.bufferEvents < 2 -> "Mäßig"
    else -> "Instabil"
}
fun torrentResolutionHint(name: String): String =
    Regex("(?i)(?:^|[^a-z0-9])(2160p|4k|1080p|720p|480p)(?:$|[^a-z0-9])")
        .find(name)?.groupValues?.get(1)?.uppercase() ?: "Unbekannt"

@Singleton
class TorrentSessionHistory @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("torrent_session_history", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _history = MutableStateFlow(readHistory())
    val history: StateFlow<List<TorrentSessionStats>> = _history.asStateFlow()
    private val _live = MutableStateFlow<TorrentSessionStats?>(null)
    val live: StateFlow<TorrentSessionStats?> = _live.asStateFlow()
    private val _samples = MutableStateFlow<List<TorrentRateSample>>(emptyList())
    val samples: StateFlow<List<TorrentRateSample>> = _samples.asStateFlow()
    private var startElapsed = 0L
    private var sampleElapsed = 0L
    private var downloadSum = 0L
    private var uploadSum = 0L

    @Synchronized fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        _enabled.value = value
    }

    @Synchronized fun begin(filename: String?) {
        finish()
        startElapsed = SystemClock.elapsedRealtime()
        _samples.value = emptyList()
        sampleElapsed = 0L
        downloadSum = 0L
        uploadSum = 0L
        val name = filename?.trim()?.take(110)?.ifEmpty { null } ?: "Unbenannte Torrent-Sitzung"
        _live.value = TorrentSessionStats(
            title = name, startedAtMs = System.currentTimeMillis(),
            resolutionHint = torrentResolutionHint(name)
        )
    }

    /** Call may be frequent; sampling is capped at once per second to avoid UI churn. */
    @Synchronized fun sample(
        downloadBps: Long, uploadBps: Long, peers: Int, seeds: Int, downloadedBytes: Long
    ) {
        val current = _live.value ?: return
        val now = SystemClock.elapsedRealtime()
        if (sampleElapsed > 0L && now - sampleElapsed < 1_000L) return
        sampleElapsed = now
        _samples.value = (_samples.value + TorrentRateSample(
            System.currentTimeMillis(), downloadBps.coerceAtLeast(0L),
            uploadBps.coerceAtLeast(0L), peers.coerceAtLeast(0)
        )).takeLast(60)
        downloadSum += downloadBps.coerceAtLeast(0)
        uploadSum += uploadBps.coerceAtLeast(0)
        val count = current.samples + 1
        _live.value = current.copy(
            durationMs = (now - startElapsed).coerceAtLeast(0),
            samples = count,
            averageDownloadBps = downloadSum / count,
            averageUploadBps = uploadSum / count,
            peakDownloadBps = maxOf(current.peakDownloadBps, downloadBps),
            peakPeers = maxOf(current.peakPeers, peers),
            peakSeeds = maxOf(current.peakSeeds, seeds),
            downloadedBytes = maxOf(current.downloadedBytes, downloadedBytes)
        )
    }

    @Synchronized fun noteBuffering() {
        val current = _live.value ?: return
        _live.value = current.copy(bufferEvents = current.bufferEvents + 1)
    }

    @Synchronized fun finish(failed: Boolean = false) {
        val current = _live.value ?: return
        _live.value = null
        _samples.value = emptyList()
        val complete = current.copy(
            durationMs = (SystemClock.elapsedRealtime() - startElapsed).coerceAtLeast(0L),
            failed = failed
        )
        if (!_enabled.value) return
        val next = (listOf(complete) + _history.value).take(40)
        _history.value = next
        saveHistory(next)
    }

    @Synchronized fun clear() {
        _history.value = emptyList()
        prefs.edit().remove("entries").apply()
    }

    private fun saveHistory(sessions: List<TorrentSessionStats>) {
        val array = JSONArray()
        sessions.forEach { s ->
            array.put(JSONObject().apply {
                put("title", s.title); put("time", s.startedAtMs); put("duration", s.durationMs)
                put("resolution", s.resolutionHint); put("avgDown", s.averageDownloadBps)
                put("peakDown", s.peakDownloadBps); put("avgUp", s.averageUploadBps)
                put("peers", s.peakPeers); put("seeds", s.peakSeeds)
                put("bytes", s.downloadedBytes); put("buffers", s.bufferEvents)
                put("samples", s.samples); put("failed", s.failed)
            })
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }

    private fun readHistory(): List<TorrentSessionStats> = runCatching {
        val array = JSONArray(prefs.getString("entries", "[]") ?: "[]")
        (0 until minOf(array.length(), 40)).map { i ->
            val s = array.getJSONObject(i)
            TorrentSessionStats(
                title = s.optString("title", "").take(110),
                startedAtMs = s.optLong("time"), durationMs = s.optLong("duration"),
                resolutionHint = s.optString("resolution", "Unbekannt"),
                averageDownloadBps = s.optLong("avgDown"), peakDownloadBps = s.optLong("peakDown"),
                averageUploadBps = s.optLong("avgUp"), peakPeers = s.optInt("peers"),
                peakSeeds = s.optInt("seeds"), downloadedBytes = s.optLong("bytes"),
                bufferEvents = s.optInt("buffers"), samples = s.optInt("samples"),
                failed = s.optBoolean("failed")
            )
        }
    }.getOrDefault(emptyList())
}
