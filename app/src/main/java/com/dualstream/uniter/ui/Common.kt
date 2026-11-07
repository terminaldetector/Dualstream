package com.dualstream.uniter.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dualstream.uniter.core.CaptureState
import com.dualstream.uniter.core.HeadsetState
import com.dualstream.uniter.core.LinkState
import com.dualstream.uniter.ui.theme.StatusColors

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold
            )
            Box(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun StatusRow(label: String, value: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(10.dp).clip(CircleShape).background(color)
        )
        Box(Modifier.size(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun KeyValue(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** A simple level meter for the stream indicators of section 8. */
@Composable
fun LevelBar(level: Float, active: Boolean, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        label = "level"
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (active) StatusColors.good else MaterialTheme.colorScheme.secondary)
        )
    }
}

fun linkLabel(state: LinkState): Pair<String, Color> = when (state) {
    LinkState.IDLE -> "Idle" to Color.Gray
    LinkState.LISTENING -> "Waiting for phone B" to StatusColors.warn
    LinkState.CONNECTING -> "Connecting" to StatusColors.warn
    LinkState.HANDSHAKING -> "Negotiating" to StatusColors.warn
    LinkState.CONNECTED -> "Connected" to StatusColors.good
    LinkState.RECOVERING -> "Reconnecting" to StatusColors.warn
    LinkState.BLUETOOTH_OFF -> "Bluetooth is off" to StatusColors.bad
    LinkState.NO_PERMISSION -> "Permission missing" to StatusColors.bad
    LinkState.FAILED -> "Failed" to StatusColors.bad
}

fun captureLabel(state: CaptureState): Pair<String, Color> = when (state) {
    CaptureState.IDLE -> "Not capturing" to Color.Gray
    CaptureState.NEEDS_CONSENT -> "Consent required" to StatusColors.warn
    CaptureState.CAPTURING -> "Capturing audio" to StatusColors.good
    CaptureState.CAPTURING_SILENCE ->
        "Running, but the app in the foreground forbids capture" to StatusColors.warn
    CaptureState.UNAVAILABLE -> "Capture unavailable" to StatusColors.bad
    CaptureState.DIRECT_PCM -> "Direct PCM (test signal)" to StatusColors.accent
    CaptureState.MIC -> "Built-in microphone" to StatusColors.good
    CaptureState.MIC_AND_PLAYBACK -> "App audio + built-in microphone" to StatusColors.good
    CaptureState.SILENCED_BY_POLICY ->
        "Muted by Android: another app holds the microphone" to StatusColors.bad
    CaptureState.WRONG_INPUT_ROUTE ->
        "Recording from the wrong input" to StatusColors.bad
}

fun headsetLabel(state: HeadsetState): Pair<String, Color> = when (state) {
    HeadsetState.UNKNOWN -> "Unknown" to Color.Gray
    HeadsetState.DISCONNECTED -> "No Bluetooth headset" to StatusColors.warn
    HeadsetState.CONNECTED_A2DP -> "Headset connected (A2DP)" to StatusColors.good
    HeadsetState.CONNECTED_DEGRADED -> "Headset connected, link degraded" to StatusColors.warn
}
