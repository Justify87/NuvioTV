package com.nuvio.tv.core.torrent

/** Player-observed track properties. Null means the selected player did not expose a reliable value. */
data class TorrentMediaProfile(
    val width: Int? = null,
    val height: Int? = null,
    val videoCodec: String? = null,
    val hdr: List<String> = emptyList(),
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    /** Selected video Format bitrate (bit/s); never an estimate from file size or title. */
    val videoBitrateBitsPerSecond: Long? = null
) {
    val resolution: String? get() =
        if (width != null && height != null) "${width} × ${height}" else null

    fun sanitized(): TorrentMediaProfile = copy(
        width = width?.takeIf { it in 1..16384 },
        height = height?.takeIf { it in 1..16384 },
        videoCodec = videoCodec?.trim()?.take(48)?.takeIf { it.isNotEmpty() },
        hdr = hdr.filter { it in setOf("DV", "HDR10", "HLG") }.distinct(),
        audioCodec = audioCodec?.trim()?.take(48)?.takeIf { it.isNotEmpty() },
        audioChannels = audioChannels?.takeIf { it in 1..32 },
        videoBitrateBitsPerSecond = videoBitrateBitsPerSecond?.takeIf { it > 0L }
    )
}

enum class TorrentSessionOutcome {
    UNKNOWN, FINISHED, FAILED, CANCELLED_BEFORE_PLAYBACK
}

/** No historical inference: legacy records did not capture accurate first frames or buffer duration. */
data class StreamingSessionAssessment(
    val label: String,
    val explanation: String,
    val downloadToVideoBitrateRatio: Double? = null
)

fun assessStreamingSession(s: TorrentSessionStats): StreamingSessionAssessment {
    if (s.telemetryVersion < 2) return StreamingSessionAssessment(
        "Bisherige Bewertung",
        "Historische Messung: First Frame und Pufferdauer wurden nicht sitzungsbezogen erfasst."
    )
    if (s.outcome == TorrentSessionOutcome.FAILED) return StreamingSessionAssessment(
        "Fehlgeschlagen", "Ein Fehler wurde für diese Wiedergabesitzung gemeldet."
    )
    val firstFrame = s.firstFrameMs
    val count = s.rebufferCount
    val total = s.rebufferTotalMs
    if (firstFrame == null || count == null || total == null) return StreamingSessionAssessment(
        "Nicht ausreichend messbar",
        if (s.outcome == TorrentSessionOutcome.CANCELLED_BEFORE_PLAYBACK) "Vor dem ersten sichtbaren Bild abgebrochen."
        else "Es liegt kein verifiziertes erstes Bild mit Puffertelemetrie vor."
    )
    val ratio = s.mediaProfile?.videoBitrateBitsPerSecond
        ?.takeIf { it > 0 && s.samples > 0 && s.averageDownloadBps > 0 }
        ?.let { bitrate -> s.averageDownloadBps.toDouble() * 8.0 / bitrate.toDouble() }
    val watchMs = (s.durationMs - firstFrame).coerceAtLeast(0L)
    val (label, explanation) = when {
        s.rebufferIncomplete ->
            "Messung unvollständig" to "Eine laufende Unterbrechung endete ohne messbares Wiedereinsetzen."
        count >= 3 || total >= 10_000L ->
            "Mehrfach unterbrochen" to "$count Unterbrechungen nach dem ersten Bild, insgesamt ${total} ms."
        count > 0 ->
            "Unterbrechung beobachtet" to "$count Unterbrechung(en), insgesamt ${total} ms."
        watchMs < 30_000L ->
            "Kurze Beobachtung" to "Nach dem ersten Bild weniger als 30 Sekunden beobachtet."
        else ->
            "Ohne beobachtete Unterbrechung" to "Im beobachteten Zeitraum wurde keine echte Wiedergabeunterbrechung registriert."
    }
    return StreamingSessionAssessment(
        label,
        explanation + " Das ist keine allgemeine Bewertung des Torrents oder Schwarms.",
        ratio
    )
}
