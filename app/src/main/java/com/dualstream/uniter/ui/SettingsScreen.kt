package com.dualstream.uniter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dualstream.uniter.codec.CodecFactory
import com.dualstream.uniter.core.CodecId
import com.dualstream.uniter.core.Prefs
import com.dualstream.uniter.service.ReceiverService

/** Section 8 "Настройки". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: Prefs,
    onBack: () -> Unit,
    onSettingsChanged: () -> Unit,
    onPickLocalTrack: () -> Unit
) {
    val bitrate = remember { mutableStateOf(prefs.bitrate) }
    val frameMs = remember { mutableStateOf(prefs.frameMs) }
    val codec = remember { mutableStateOf(prefs.preferredCodec) }
    val duck = remember { mutableStateOf(prefs.duckDepth) }
    val balance = remember { mutableStateOf(prefs.balance) }
    val autoReconnect = remember { mutableStateOf(prefs.autoReconnect) }
    val powerSave = remember { mutableStateOf(prefs.powerSave) }
    val verbose = remember { mutableStateOf(prefs.verboseLog) }
    val localUri = remember { mutableStateOf(prefs.localSourceUri) }
    val micGain = remember { mutableStateOf(prefs.micGain) }
    val micFx = remember { mutableStateOf(prefs.micProcessing) }

    val opusAvailable = remember { CodecFactory.opusEncodeSupported() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("‹") }
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
            SectionCard("Transmission quality") {
                Text("Codec", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CodecId.entries.forEach { c ->
                        FilterChip(
                            selected = codec.value == c,
                            enabled = c != CodecId.OPUS || opusAvailable,
                            onClick = {
                                codec.value = c
                                prefs.preferredCodec = c
                                onSettingsChanged()
                            },
                            label = { Text(c.label) }
                        )
                    }
                }
                if (!opusAvailable) {
                    Box(Modifier.size(6.dp))
                    Text(
                        "This device exposes no Opus encoder, so ADPCM is used instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Box(Modifier.size(10.dp))
                Text("Bitrate: ${bitrate.value / 1000} kbit/s", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(24_000, 32_000, 48_000, 64_000).forEach { b ->
                        FilterChip(
                            selected = bitrate.value == b,
                            onClick = {
                                bitrate.value = b
                                prefs.bitrate = b
                                onSettingsChanged()
                            },
                            label = { Text("${b / 1000}") }
                        )
                    }
                }

                Box(Modifier.size(10.dp))
                Text("Frame size", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 20).forEach { f ->
                        FilterChip(
                            selected = frameMs.value == f,
                            onClick = {
                                frameMs.value = f
                                prefs.frameMs = f
                                onSettingsChanged()
                            },
                            label = { Text("$f ms") }
                        )
                    }
                }
                Box(Modifier.size(6.dp))
                Text(
                    "Shorter frames lower the theoretical delay but add protocol overhead per frame. " +
                        "The real end-to-end delay has to be measured on the actual pair of phones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            SectionCard("Remote source priority") {
                Text(
                    "Ducking depth: ${(duck.value * 100).toInt()} %",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = duck.value,
                    onValueChange = { duck.value = it },
                    onValueChangeFinished = {
                        prefs.duckDepth = duck.value
                        onSettingsChanged()
                    }
                )
                Text(
                    "How far the local track is turned down in Priority mode while phone B is audible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            SectionCard("Channel balance") {
                Text(
                    when {
                        balance.value < -0.05f -> "Left  ${(-balance.value * 100).toInt()} %"
                        balance.value > 0.05f -> "Right ${(balance.value * 100).toInt()} %"
                        else -> "Centre"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = balance.value,
                    valueRange = -1f..1f,
                    onValueChange = { balance.value = it },
                    onValueChangeFinished = {
                        prefs.balance = balance.value
                        onSettingsChanged()
                    }
                )
            }

            SectionCard("Microphone") {
                Text(
                    "Input gain: ${"%.1f".format(micGain.value)}×",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = micGain.value,
                    valueRange = 0.25f..4f,
                    onValueChange = { micGain.value = it },
                    onValueChangeFinished = {
                        prefs.micGain = micGain.value
                        onSettingsChanged()
                    }
                )
                ToggleRow("Echo and noise processing", micFx.value) {
                    micFx.value = it
                    prefs.micProcessing = it
                    onSettingsChanged()
                }
                Text(
                    "Attaches the platform echo canceller, noise suppressor and automatic gain " +
                        "control when the device offers them. Takes effect the next time the " +
                        "microphone is opened.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Box(Modifier.size(8.dp))
                Text(
                    "Recording is always pinned to the built-in microphone. Letting Android pick " +
                        "could put it on the headset's microphone, which switches that headset to " +
                        "the hands-free profile and takes the A2DP stream down — and a headset " +
                        "microphone can only ever belong to one phone, so the other one would be " +
                        "cut off. Nothing here uses Bluetooth SCO or the call audio mode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            SectionCard("Local managed source") {
                Text(
                    localUri.value?.let {
                        if (it == ReceiverService.LOCAL_TONE) "Built-in test tone" else it
                    } ?: "none",
                    style = MaterialTheme.typography.bodySmall
                )
                Box(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickLocalTrack) { Text("Pick a track") }
                    OutlinedButton(onClick = {
                        prefs.localSourceUri = ReceiverService.LOCAL_TONE
                        localUri.value = ReceiverService.LOCAL_TONE
                        onSettingsChanged()
                    }) { Text("Test tone") }
                    OutlinedButton(onClick = {
                        prefs.localSourceUri = null
                        localUri.value = null
                        onSettingsChanged()
                    }) { Text("Clear") }
                }
                Box(Modifier.size(6.dp))
                Text(
                    "Hard Split and Priority need a source this app owns. Android does not let a " +
                        "normal app capture and re-route audio that other apps are playing on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            SectionCard("Connection") {
                ToggleRow("Automatic reconnection", autoReconnect.value) {
                    autoReconnect.value = it
                    prefs.autoReconnect = it
                }
                ToggleRow("Power-saving mode", powerSave.value) {
                    powerSave.value = it
                    prefs.powerSave = it
                    onSettingsChanged()
                }
                Text(
                    "Power saving lowers the bitrate to 24 kbit/s and forces 20 ms frames, which " +
                        "reduces radio airtime and wakeups at the cost of latency.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            SectionCard("Diagnostics") {
                ToggleRow("Verbose logging", verbose.value) {
                    verbose.value = it
                    prefs.verboseLog = it
                }
            }

            Box(Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
