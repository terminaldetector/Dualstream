package com.dualstream.uniter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualstream.uniter.core.AppState
import com.dualstream.uniter.core.Logx
import kotlinx.coroutines.delay

/**
 * Live counters and the in-memory log ring. This exists because Stage 4 of the
 * plan is field testing on real hardware, where no debugger is attached.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val stats by AppState.stats.collectAsStateWithLifecycle()
    val lines = remember { mutableStateOf(Logx.snapshot()) }

    LaunchedEffect(Unit) {
        while (true) {
            lines.value = Logx.snapshot().asReversed()
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = { IconButton(onClick = onBack) { Text("‹") } },
                actions = {
                    TextButton(onClick = { AppState.resetStats() }) { Text("Reset") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp)
        ) {
            SectionCard("Counters") {
                KeyValue("Frames sent", stats.framesSent.toString())
                KeyValue("Frames received", stats.framesReceived.toString())
                KeyValue("Bytes sent", stats.bytesSent.toString())
                KeyValue("Bytes received", stats.bytesReceived.toString())
                KeyValue("Audio gaps (underruns)", stats.underruns.toString())
                KeyValue("Dropped to cut latency", stats.dropped.toString())
                KeyValue("Lost per sequence", stats.lost.toString())
                KeyValue("Buffer", "${stats.bufferMs} ms")
                KeyValue(
                    "Measured send rate",
                    if (stats.sendBitsPerSecond > 0)
                        "${stats.sendBitsPerSecond / 1000} kbit/s · ${stats.packetsPerSecond} pkt/s"
                    else "—"
                )
                KeyValue(
                    "Target bitrate",
                    if (stats.targetBitrate > 0) "${stats.targetBitrate / 1000} kbit/s" else "—"
                )
                KeyValue(
                    "Audio per second of clock",
                    if (stats.realtimeRatio > 0f) "x%.2f".format(stats.realtimeRatio) else "—"
                )
                KeyValue("Dropped to stay in budget", stats.droppedToBudget.toString())
                KeyValue("Players active on this phone", stats.activePlayers.toString())
                stats.bitrateReason?.let { KeyValue("Last rate change", it) }
                KeyValue("Round trip", if (stats.rttMs >= 0) "${stats.rttMs} ms" else "—")
                val uptime = if (stats.connectedSinceMs > 0)
                    (System.currentTimeMillis() - stats.connectedSinceMs) / 1000 else 0
                KeyValue("Connected for", "${uptime}s")
            }
            Box(Modifier.size(10.dp))
            Text("Log", style = MaterialTheme.typography.labelMedium)
            Box(Modifier.size(4.dp))
            LazyColumn {
                items(lines.value) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
