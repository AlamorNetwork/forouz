package com.v2ray.ang.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.extension.toTrafficString

/** Actual Xray deltas, not a synthetic speed test. No line is drawn across missing samples. */
@Composable
internal fun MainTraffic(connection: MainConnectionState, modifier: Modifier = Modifier, showSummary: Boolean = true) {
    val snapshot = connection.snapshot
    val colors = MaterialTheme.colorScheme
    if (snapshot?.running != true || !snapshot.trafficAvailable || !snapshot.sampleReady) {
        val text = when {
            snapshot?.running != true -> R.string.lab_traffic_waiting
            !snapshot.trafficAvailable -> R.string.lab_traffic_unavailable
            else -> R.string.lab_traffic_waiting
        }
        Text(stringResource(text), modifier, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        return
    }
    val samples = connection.samples
    val peak = samples.maxOfOrNull { maxOf(it.downloadRate, it.uploadRate) }?.coerceAtLeast(1) ?: 1L
    val description = stringResource(R.string.lab_chart_description, rateText(snapshot.downloadRate), rateText(snapshot.uploadRate))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showSummary) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.lab_download), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(rateText(snapshot.downloadRate), style = MaterialTheme.typography.titleLarge, color = colors.tertiary)
                Text(stringResource(R.string.lab_total_bytes, snapshot.downloaded.toTrafficString()),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.lab_upload), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(rateText(snapshot.uploadRate), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.lab_total_bytes, snapshot.uploaded.toTrafficString()),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(80.dp).semantics { contentDescription = description }) {
            for (i in 0..2) {
                val y = size.height * i / 2f
                drawLine(colors.outlineVariant.copy(alpha = 0.4f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            for (download in listOf(true, false)) {
                val path = Path()
                var previousTime: Long? = null
                samples.forEach { sample ->
                    val x = ((sample.sampledAt - snapshot.sampledAt + MainConnectionState.WINDOW_MS).toFloat() /
                        MainConnectionState.WINDOW_MS).coerceIn(0f, 1f) * size.width
                    val rate = if (download) sample.downloadRate else sample.uploadRate
                    val y = size.height * (1f - (rate.toDouble() / peak).toFloat().coerceIn(0f, 1f))
                    if (previousTime == null || sample.sampledAt - previousTime > 6_000L) path.moveTo(x, y)
                    else path.lineTo(x, y)
                    previousTime = sample.sampledAt
                }
                drawPath(path, if (download) colors.tertiary else colors.onSurfaceVariant,
                    style = Stroke(2.dp.toPx(), pathEffect = if (download) null else PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
            }
        }
        Text(stringResource(R.string.lab_traffic_window, rateText(peak)),
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        Text(stringResource(R.string.lab_traffic_scope), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}
