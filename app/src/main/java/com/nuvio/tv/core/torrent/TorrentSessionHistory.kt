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
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

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
    val failed: Boolean = false,
    val sessionId: String = "",
    /** Version 0 means old count-only records, never measured playback. */
    val telemetryVersion: Int = 0,
    val outcome: TorrentSessionOutcome = TorrentSessionOutcome.UNKNOWN,
    /** Elapsed time from torrent begin() to a real rendered video frame. */
    val firstFrameMs: Long? = null,
    val rebufferCount: Int? = null,
    val rebufferTotalMs: Long? = null,
    val rebufferIncomplete: Boolean = false,
    val mediaProfile: TorrentMediaProfile? = null
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

/** Pure, lenient migration: malformed entries cannot discard other valid sessions. Never rewrites prefs on load. */
internal fun parseSavedTorrentSessions(raw: String?): List<TorrentSessionStats> = runCatching {
    val root = Json.parseToJsonElement(raw ?: "[]") as? JsonArray ?: return@runCatching emptyList()
    root.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        fun string(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull
        fun long(key: String): Long = (obj[key] as? JsonPrimitive)?.longOrNull ?: 0L
        fun int(key: String): Int = (obj[key] as? JsonPrimitive)?.intOrNull ?: 0
        fun bool(key: String): Boolean = (obj[key] as? JsonPrimitive)?.booleanOrNull ?: false
        val time = long("time")
        if (time <= 0L) return@mapNotNull null
        TorrentSessionStats(
            title = string("title")?.take(110).orEmpty().ifBlank { "Unbenannte Torrent-Sitzung" },
            startedAtMs = time,
            durationMs = long("duration").coerceAtLeast(0L),
            resolutionHint = string("resolution")?.take(40) ?: "Unbekannt",
            averageDownloadBps = long("avgDown").coerceAtLeast(0L),
            peakDownloadBps = long("peakDown").coerceAtLeast(0L),
            averageUploadBps = long("avgUp").coerceAtLeast(0L),
            peakPeers = int("peers").coerceAtLeast(0),
            peakSeeds = int("seeds").coerceAtLeast(0),
            downloadedBytes = long("bytes").coerceAtLeast(0L),
            bufferEvents = int("buffers").coerceAtLeast(0),
            samples = int("samples").coerceAtLeast(0),
            failed = bool("failed"),
            sessionId = string("id")?.take(80).orEmpty(),
            telemetryVersion = int("v").takeIf { it == 2 } ?: 0,
            outcome = string("outcome")?.let { raw ->
                TorrentSessionOutcome.entries.firstOrNull { it.name == raw }
            } ?: TorrentSessionOutcome.UNKNOWN,
            firstFrameMs = (obj["firstFrameMs"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0L },
            rebufferCount = (obj["rebufferCount"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 },
            rebufferTotalMs = (obj["rebufferTotalMs"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0L },
            rebufferIncomplete = bool("rebufferIncomplete"),
            mediaProfile = (obj["media"] as? JsonObject)?.let { media ->
                fun positiveInt(key: String) = (media[key] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
                fun positiveLong(key: String) = (media[key] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
                fun label(key: String) = (media[key] as? JsonPrimitive)?.contentOrNull?.take(48)
                TorrentMediaProfile(
                    width = positiveInt("w"), height = positiveInt("h"),
                    videoCodec = label("videoCodec"),
                    hdr = (media["hdr"] as? JsonArray)?.mapNotNull {
                        (it as? JsonPrimitive)?.contentOrNull
                    }.orEmpty(),
                    audioCodec = label("audioCodec"), audioChannels = positiveInt("channels"),
                    videoBitrateBitsPerSecond = positiveLong("videoBitrate")
                ).sanitized()
            }
        )
    }.sortedByDescending { it.startedAtMs }.take(40)
}.getOrDefault(emptyList())

internal fun boundedSavedSessions(sessions: List<TorrentSessionStats>): List<TorrentSessionStats> =
    sessions.sortedByDescending { it.startedAtMs }.take(40)

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
    private var rebufferStartedElapsed: Long? = null

    @Synchronized fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        _enabled.value = value
    }

    @Synchronized fun begin(filename: String?): String {
        finish()
        startElapsed = SystemClock.elapsedRealtime()
        _samples.value = emptyList()
        sampleElapsed = 0L
        downloadSum = 0L
        uploadSum = 0L
        rebufferStartedElapsed = null
        val name = filename?.trim()?.take(110)?.ifEmpty { null } ?: "Unbenannte Torrent-Sitzung"
        _live.value = TorrentSessionStats(
            title = name, startedAtMs = System.currentTimeMillis(),
            resolutionHint = torrentResolutionHint(name),
            sessionId = UUID.randomUUID().toString(),
            telemetryVersion = 2
        )
        return _live.value!!.sessionId
    }

    /**
     * Accept only player callbacks bound to this short-lived session generation.
     * The start point is torrent-service begin(), not player prepare().
     * Duplicate first frames and stale callbacks are harmless.
     */
    @Synchronized fun markFirstFrame(sessionId: String, elapsedMs: Long = SystemClock.elapsedRealtime()) {
        val current = _live.value?.takeIf { it.sessionId == sessionId } ?: return
        if (current.firstFrameMs != null || elapsedMs < startElapsed) return
        _live.value = current.copy(
            firstFrameMs = elapsedMs - startElapsed,
            rebufferCount = 0, rebufferTotalMs = 0L
        )
    }

    @Synchronized fun updateMediaInfo(sessionId: String, observed: TorrentMediaProfile) {
        val current = _live.value?.takeIf { it.sessionId == sessionId } ?: return
        val fresh = observed.sanitized()
        val previous = current.mediaProfile
        val merged = fresh.copy(
            width = fresh.width ?: previous?.width, height = fresh.height ?: previous?.height,
            videoCodec = fresh.videoCodec ?: previous?.videoCodec,
            hdr = if (fresh.hdr.isEmpty()) previous?.hdr.orEmpty() else fresh.hdr,
            audioCodec = fresh.audioCodec ?: previous?.audioCodec,
            audioChannels = fresh.audioChannels ?: previous?.audioChannels,
            videoBitrateBitsPerSecond = fresh.videoBitrateBitsPerSecond ?: previous?.videoBitrateBitsPerSecond
        )
        if (merged != previous) _live.value = current.copy(mediaProfile = merged)
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

    @Synchronized fun finish(
        failed: Boolean = false,
        sessionId: String? = null,
        outcome: TorrentSessionOutcome? = null
    ) {
        val current = _live.value ?: return
        if (sessionId != null && current.sessionId != sessionId) return
        val chosen = when {
            failed || current.outcome == TorrentSessionOutcome.FAILED -> TorrentSessionOutcome.FAILED
            outcome != null -> outcome
            current.firstFrameMs == null -> TorrentSessionOutcome.CANCELLED_BEFORE_PLAYBACK
            else -> TorrentSessionOutcome.FINISHED
        }
        val incomplete = rebufferStartedElapsed != null
        rebufferStartedElapsed = null
        _live.value = null
        _samples.value = emptyList()
        val complete = current.copy(
            durationMs = (SystemClock.elapsedRealtime() - startElapsed).coerceAtLeast(0L),
            failed = chosen == TorrentSessionOutcome.FAILED,
            outcome = chosen,
            rebufferIncomplete = current.rebufferIncomplete || incomplete
        )
        if (!_enabled.value) return
        val next = boundedSavedSessions(listOf(complete) + _history.value)
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
                if (s.sessionId.isNotEmpty()) put("id", s.sessionId)
                if (s.telemetryVersion >= 2) {
                    put("v", 2)
                    put("outcome", s.outcome.name)
                    s.firstFrameMs?.let { put("firstFrameMs", it) }
                    s.rebufferCount?.let { put("rebufferCount", it) }
                    s.rebufferTotalMs?.let { put("rebufferTotalMs", it) }
                    if (s.rebufferIncomplete) put("rebufferIncomplete", true)
                    s.mediaProfile?.sanitized()?.let { m ->
                        put("media", JSONObject().apply {
                            m.width?.let { put("w", it) }; m.height?.let { put("h", it) }
                            m.videoCodec?.let { put("videoCodec", it) }
                            put("hdr", JSONArray(m.hdr))
                            m.audioCodec?.let { put("audioCodec", it) }
                            m.audioChannels?.let { put("channels", it) }
                            m.videoBitrateBitsPerSecond?.let { put("videoBitrate", it) }
                        })
                    }
                }
            })
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }

    private fun readHistory(): List<TorrentSessionStats> =
        parseSavedTorrentSessions(prefs.getString("entries", null))
}
