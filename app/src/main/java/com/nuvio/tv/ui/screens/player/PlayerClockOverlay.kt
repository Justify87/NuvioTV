package com.nuvio.tv.ui.screens.player

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Date
import kotlinx.coroutines.delay

@Composable
internal fun PlayerClockOverlay(
    currentPosition: Long,
    duration: Long,
    playbackSpeed: Float,
    isLive: Boolean = false
) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    val timeFormatter = remember(context) { DateFormat.getTimeFormat(context) }

    LaunchedEffect(Unit) {
        while (true) {
            val current = System.currentTimeMillis()
            nowMs = current
            val delayMs = (1_000L - (current % 1_000L)).coerceAtLeast(250L)
            delay(delayMs)
        }
    }

    val effectiveSpeed = playbackSpeed.takeIf { it > 0f } ?: 1f
    val remainingMediaMs = (duration - currentPosition).coerceAtLeast(0L)
    val remainingMs = kotlin.math.ceil(remainingMediaMs.toDouble() / effectiveSpeed.toDouble()).toLong()
    val endTimeText = if (duration > 0L) {
        timeFormatter.format(Date(nowMs + remainingMs))
    } else {
        "--:--"
    }

    Column(
        modifier = Modifier
            .padding(horizontal = NuvioTheme.spacing.xxs, vertical = NuvioTheme.spacing.xxs),
        horizontalAlignment = Alignment.End
    ) {
        Text(
            text = timeFormatter.format(Date(nowMs)),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = Color.White.copy(alpha = 0.96f)
        )
        if (!isLive) {
            Text(
                text = stringResource(R.string.player_ends_at, endTimeText),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp),
                color = Color.White.copy(alpha = 0.78f)
            )
        }
    }
}
