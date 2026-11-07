package com.dualstream.uniter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualstream.uniter.core.AppState
import com.dualstream.uniter.core.CaptureState
import com.dualstream.uniter.core.LinkState
import com.dualstream.uniter.core.MixMode
import com.dualstream.uniter.core.Prefs
import com.dualstream.uniter.core.Role

/**
 * The main screen of section 8: pairing state, headset state, both volumes,
 * mode selection, stream indicators and a reconnect button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    prefs: Prefs,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onStartReceiver: () -> Unit,
    onStopReceiver: () -> Unit,
    onStartTransmitter: (String) -> Unit,
    onStartTestTone: (String) -> Unit,
    onStopTransmitter: () -> Unit,
    onReconnect: () -> Unit,
    onSettingsChanged: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    val link by AppState.link.collectAsStateWithLifecycle()
    val peer by AppState.peerName.collectAsStateWithLifecycle()
    val capture by AppState.capture.collectAsStateWithLifecycle()
    val headset by AppState.headset.collectAsStateWithLifecycle()
    val stats by AppState.stats.collectAsStateWithLifecycle()
    val notice by AppState.notice.collectAsStateWithLifecycle()
    val negotiated by AppState.negotiated.collectAsStateWithLifecycle()
    val transmitting by AppState.transmitting.collectAsStateWithLifecycle()
    val receiving by AppState.receiving.collectAsStateWithLifecycle()

    var role by remember { mutableStateOf(prefs.role) }
    var mode by remember { mutableStateOf(prefs.mixMode) }
    var localVol by remember { mutableStateOf(prefs.localVolume) }
    var remoteVol by remember { mutableStateOf(prefs.remoteVolume) }

    val active = receiving || transmitting ||
        link in setOf(LinkState.LISTENING, LinkState.CONNECTING, LinkState.CONNECTED,
            LinkState.HANDSHAKING, LinkState.RECOVERING)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DualStream Uniter") },
                actions = {
                    TextButton(onClick = onOpenDiagnostics) { Text("Log") }
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!permissionsGranted) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "Nearby devices and microphone access are required.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Box(Modifier.size(8.dp))
                        Button(onClick = onRequestPermissions) { Text("Grant permissions") }
                    }
                }
            }

            notice?.let { text ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text,
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ---------------------------------------------------------- role
            SectionCard("This phone") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = role == Role.RECEIVER,
                        onClick = {
                            if (!active) {
                                role = Role.RECEIVER
                                prefs.role = role
                            }
                        },
                        enabled = !active,
                        label = { Text("A · headset + mixer") }
                    )
                    FilterChip(
                        selected = role == Role.TRANSMITTER,
                        onClick = {
                            if (!active) {
                                role = Role.TRANSMITTER
                                prefs.role = role
                            }
                        },
                        enabled = !active,
                        label = { Text("B · sends audio") }
                    )
                }
                Box(Modifier.size(6.dp))
                Text(
                    if (role == Role.RECEIVER)
                        "The headset is paired with this phone. It accepts the RFCOMM connection and plays both sources."
                    else
                        "This phone captures its own playback and sends it to phone A. Do not connect the headset here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            // ------------------------------------------------------- states
            SectionCard("Status") {
                val (linkText, linkColor) = linkLabel(link)
                StatusRow("Phone link", peer?.let { "$linkText · $it" } ?: linkText, linkColor)

                if (role == Role.RECEIVER) {
                    val (hsText, hsColor) = headsetLabel(headset)
                    StatusRow("Headset", hsText, hsColor)
                }

                val (capText, capColor) = captureLabel(capture)
                StatusRow(
                    if (role == Role.TRANSMITTER) "Capture" else "Phone B capture",
                    capText, capColor
                )

                negotiated?.let {
                    Box(Modifier.size(6.dp))
                    KeyValue("Stream", "${it.codec.label} · ${it.bitrate / 1000} kbit/s · ${it.frameMs} ms")
                }
            }

            // ------------------------------------------------------ meters
            SectionCard("Streams") {
                Text("Local source", style = MaterialTheme.typography.bodySmall)
                LevelBar(stats.localLevel, stats.localLevel > 0.001f)
                Box(Modifier.size(10.dp))
                Text("Remote phone", style = MaterialTheme.typography.bodySmall)
                LevelBar(stats.remoteLevel, stats.remoteLevel > 0.001f)
                Box(Modifier.size(10.dp))
                KeyValue("Buffer", "${stats.bufferMs} ms (${stats.jitterFrames} frames)")
                KeyValue("Round trip", if (stats.rttMs >= 0) "${stats.rttMs} ms" else "—")
                KeyValue("Gaps / drops / lost", "${stats.underruns} / ${stats.dropped} / ${stats.lost}")
                if (mode == MixMode.PRIORITY) {
                    KeyValue("Local ducking", "${(stats.duckGain * 100).toInt()} %")
                }
            }

            // ------------------------------------------------------- mixing
            if (role == Role.RECEIVER) {
                SectionCard("Playback mode") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MixMode.entries.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { m ->
                                    FilterChip(
                                        selected = mode == m,
                                        onClick = {
                                            mode = m
                                            prefs.mixMode = m
                                            onSettingsChanged()
                                        },
                                        label = { Text(m.label) }
                                    )
                                }
                            }
                        }
                    }
                    Box(Modifier.size(6.dp))
                    Text(
                        modeHint(mode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                SectionCard("Volume") {
                    Text("Local source  ${(localVol * 100).toInt()} %",
                        style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = localVol,
                        onValueChange = { localVol = it },
                        onValueChangeFinished = {
                            prefs.localVolume = localVol
                            onSettingsChanged()
                        }
                    )
                    Text("Remote phone  ${(remoteVol * 100).toInt()} %",
                        style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = remoteVol,
                        onValueChange = { remoteVol = it },
                        onValueChangeFinished = {
                            prefs.remoteVolume = remoteVol
                            onSettingsChanged()
                        }
                    )
                }
            }

            // ------------------------------------------------------ controls
            SectionCard("Connection") {
                val trusted = prefs.trustedPeerName ?: prefs.trustedPeerAddress
                KeyValue("Paired phone", trusted ?: "not selected")
                Box(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenDevices) { Text("Devices") }
                    OutlinedButton(
                        onClick = onReconnect,
                        enabled = active
                    ) { Text("Reconnect") }
                }
                Box(Modifier.size(8.dp))
                if (role == Role.RECEIVER) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onStartReceiver,
                            enabled = permissionsGranted && !active
                        ) { Text("Start receiver") }
                        OutlinedButton(onClick = onStopReceiver, enabled = active) { Text("Stop") }
                    }
                } else {
                    val peerAddr = prefs.trustedPeerAddress
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { peerAddr?.let(onStartTransmitter) },
                            enabled = permissionsGranted && !active && peerAddr != null
                        ) { Text("Start sending") }
                        OutlinedButton(onClick = onStopTransmitter, enabled = active) { Text("Stop") }
                    }
                    Box(Modifier.size(6.dp))
                    OutlinedButton(
                        onClick = { peerAddr?.let(onStartTestTone) },
                        enabled = permissionsGranted && !active && peerAddr != null
                    ) { Text("Send test tone (no capture consent)") }
                    Box(Modifier.size(6.dp))
                    Text(
                        "The test tone is the Stage 1 bring-up path: it proves the RFCOMM link and the " +
                            "headset output without MediaProjection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            if (capture == CaptureState.CAPTURING_SILENCE) {
                SectionCard("Why is there no sound?") {
                    Text(
                        "Android only hands over audio from players that allow playback capture. " +
                            "Many streaming apps opt out, and calls and DRM-protected audio can never " +
                            "be captured. The link is up and frames are flowing, but they are silent — " +
                            "that is a platform restriction, not a bug in the link.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Box(Modifier.size(20.dp))
        }
    }
}

private fun modeHint(mode: MixMode): String = when (mode) {
    MixMode.HARD_SPLIT ->
        "Left ear: the track this app plays locally. Right ear: phone B. Without system privileges " +
            "the app cannot steer audio from other apps on this phone, so pick a local track in Settings."
    MixMode.MIXED_STEREO -> "Both sources are summed and played in both ears."
    MixMode.REMOTE_ONLY -> "This phone acts purely as a Bluetooth audio sink for phone B."
    MixMode.PRIORITY -> "The local track is turned down automatically whenever phone B is making sound."
}
