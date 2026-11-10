package com.nuvio.tv.ui.screens.streamingmonitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.torrent.TorrentRateSample
import com.nuvio.tv.ui.theme.NuvioTheme

internal val MonitorDownloadColor = Color(0xFF53D6E8)
internal val MonitorUploadColor = Color(0xFFA7E36D)

/**
 * Fixed-width 60-second time window ending at the last REAL sample.
 * Gaps remain gaps (no fabricated zero measurements); x positions use timestamps.
 * Only the service's bounded in-memory measurements are read.
 */
@Composable
internal fun StreamingMonitorRateChart(points: List<TorrentRateSample>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(stringResource(R.string.sm_download), color = MonitorDownloadColor,
                style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.sm_upload), color = MonitorUploadColor,
                style = MaterialTheme.typography.bodyMedium)
        }
        if (points.isEmpty()) {
            Text(stringResource(R.string.sm_no_samples),
                color = NuvioTheme.colors.TextSecondary,
                style = MaterialTheme.typography.bodyLarge)
            return@Column
        }

        val peak = remember(points) {
            points.fold(1L) { max, point ->
                maxOf(max, point.downloadBytesPerSecond, point.uploadBytesPerSecond)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.sm_chart_max, formatTorrentRate(peak)),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary)
            Text(stringResource(R.string.sm_chart_sample_count, points.size),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary)
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(156.dp)) {
            val right = size.width
            val bottom = size.height - 2.dp.toPx()
            val top = 2.dp.toPx()
            val chartHeight = (bottom - top).coerceAtLeast(1f)
            val lastTime = points.last().timestampMs
            val firstTime = (lastTime - 60_000L).coerceAtLeast(0L)

            for (fraction in 0..2) {
                val y = top + chartHeight * fraction / 2f
                drawLine(
                    color = Color.White.copy(alpha = 0.15f),
                    start = Offset(0f, y), end = Offset(right, y),
                    strokeWidth = 1.dp.toPx()
                )
            }
            fun position(point: TorrentRateSample, value: Long): Offset {
                val elapsed = (point.timestampMs - firstTime).toDouble().coerceAtLeast(0.0)
                val x = (elapsed / 60_000.0).toFloat().coerceIn(0f, 1f) * right
                val ratio = (value.toDouble() / peak.toDouble()).toFloat().coerceIn(0f, 1f)
                return Offset(x, bottom - ratio * chartHeight)
            }
            fun plot(color: Color, read: (TorrentRateSample) -> Long) {
                if (points.size == 1) {
                    drawCircle(color, radius = 3.dp.toPx(), center = position(points[0], read(points[0])))
                } else {
                    for (i in 1 until points.size) {
                        drawLine(
                            color = color,
                            start = position(points[i - 1], read(points[i - 1])),
                            end = position(points[i], read(points[i])),
                            strokeWidth = 2.5.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
            plot(MonitorDownloadColor) { it.downloadBytesPerSecond }
            plot(MonitorUploadColor) { it.uploadBytesPerSecond }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.sm_chart_60_seconds_ago),
                color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.sm_chart_30_seconds_ago),
                color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.sm_chart_last_sample),
                color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
