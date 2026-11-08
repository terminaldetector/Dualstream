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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualstream.uniter.core.AppState
import com.dualstream.uniter.core.CaptureState
import com.dualstream.uniter.core.InputSource
import com.dualstream.uniter.core.LinkState
import com.dualstream.uniter.core.MixMode
import com.dualstream.uniter.core.Prefs
import com.dualstream.uniter.core.Role
import com.dualstream.uniter.audio.MixerControls
import com.dualstream.uniter.audio.SystemVolume
import com.dualstream.uniter.transport.BtDiscovery
import com.dualstream.uniter.transport.ConnectionReadiness
import com.dualstream.uniter.ui.theme.StatusColors

/**
 * The main screen of section 8: pairing state, headset state, both volumes,
 * mode selection, stream indicators and a reconnect button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    prefs: Prefs,
    discovery: BtDiscovery,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestDiscoverable: () -> Unit,
    onRequestDndAccess: () -> Unit,
    onStartReceiver: () -> Unit,
    onStopReceiver: () -> Unit,
    onStartTransmitter: (String, InputSource) -> Unit,
    onStopTransmitter: () -> Unit,
    onReconnect: () -> Unit,
    onSettingsChanged: () -> Unit,
    onPickLocalTrack: () -> Unit,
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
    var inputSource by remember { mutableStateOf(prefs.inputSource) }
    var muteSpeaker by remember { mutableStateOf(prefs.muteLocalSpeaker) }

    val context = LocalContext.current
    val systemVolume = remember {
        SystemVolume(
            androidx.core.content.ContextCompat.getSystemService(
                context, android.media.AudioManager::class.java
            )
        )
    }
    var masterVolume by remember { mutableStateOf(systemVolume.fraction) }
    var micAsLocal by remember { mutableStateOf(prefs.micAsLocalSource) }

    // The engine reports what is really feeding the local channel, which can be
    // this phone's app audio and is therefore not derivable from settings.
    val liveLocalSource by AppState.localSource.collectAsStateWithLifecycle()
    val hasLocalSource = liveLocalSource != null ||
        MixerControls.hasLocalSource(prefs.localSourceUri, micAsLocal)

    val discoverable by discovery.discoverable.collectAsStateWithLifecycle()
    val knownDevices by discovery.devices.collectAsStateWithLifecycle()

    // Re-read on every recomposition driven by the flows above, so pairing done
    // in the system settings shows up here without a restart.
    val peerAddress = prefs.trustedPeerAddress
    val peerBonded = remember(peerAddress, knownDevices, discoverable) {
        peerAddress != null && discovery.isBonded(peerAddress)
    }
    val readiness = ConnectionReadiness.evaluate(
        role = role,
        bluetoothOn = discovery.isBluetoothOn,
        hasPermissions = permissionsGranted,
        discoverable = discoverable,
        peerSelected = peerAddress != null,
        bondedToPeer = peerBonded
    )

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

            // Capturing this phone's apps is only useful if their own path to
            // the headset can be silenced, and muting a stream needs this.
            if (role == Role.RECEIVER && !SystemVolume.canMute(context)) {
                SectionCard("Other apps on this phone") {
                    Text(
                        "To put any app from this phone in one ear only, its own sound has to " +
                            "be kept out of the headset. That means muting the media volume, " +
                            "and Android counts a mute as a Do Not Disturb change, so it needs " +
                            "that access. Without it the local ear can only play a track or " +
                            "the microphone.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Box(Modifier.size(8.dp))
                    Button(onClick = onRequestDndAccess) {
                        Text("Grant Do Not Disturb access")
                    }
                }
            }

            // --------------------------------------------------- visibility
            if (role == Role.RECEIVER) {
                SectionCard("Visibility") {
                    StatusRow(
                        "This phone",
                        if (discoverable) "Visible to other devices" else "Not visible",
                        if (discoverable) StatusColors.good else StatusColors.warn
                    )
                    Box(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onRequestDiscoverable,
                            enabled = permissionsGranted && discovery.isBluetoothOn
                        ) { Text(if (discoverable) "Extend visibility" else "Make visible") }
                    }
                    Box(Modifier.size(6.dp))
                    Text(
                        if (peerBonded)
                            "Phone B is already paired, so it can connect straight to this phone " +
                                "and visibility is no longer needed."
                        else
                            "Accepting a connection does not make this phone visible. Until you turn " +
                                "visibility on, phone B cannot find it in a Bluetooth search at all, " +
                                "however long it looks — which is why nothing connects. Turn it on " +
                                "here, pair from phone B, and after that this step is no longer needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            ConnectionReadiness.summary(readiness, role)?.let { next ->
                SectionCard("Next step") {
                    Text(next, style = MaterialTheme.typography.bodyMedium)
                }
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

            // ------------------------------------------------- local source on A
            if (role == Role.RECEIVER) {
                SectionCard("This phone's microphone") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Use the built-in microphone as the local source",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = micAsLocal,
                            enabled = permissionsGranted,
                            onCheckedChange = {
                                micAsLocal = it
                                prefs.micAsLocalSource = it
                                onSettingsChanged()
                            }
                        )
                    }
                    Box(Modifier.size(6.dp))
                    Text(
                        "With phone B sending its own microphone, each phone records its own " +
                            "hardware. Recording is pinned to the built-in microphone on purpose: " +
                            "taking the headset's microphone would switch it to hands-free and kill " +
                            "the A2DP stream, and two phones cannot share one headset microphone — " +
                            "the system would cut the input on both.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

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
                    if (MixerControls.modeNeedsLocalSource(mode) && !hasLocalSource) {
                        Box(Modifier.size(6.dp))
                        StatusRow("Local source", "none selected", StatusColors.warn)
                        Text(
                            "${mode.label} needs a source this app owns for the other ear. " +
                                "Until one is chosen, phone B is played in both ears instead of " +
                                "leaving one side silent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Box(Modifier.size(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onPickLocalTrack) { Text("Pick a track") }
                            OutlinedButton(
                                enabled = permissionsGranted,
                                onClick = {
                                    micAsLocal = true
                                    prefs.micAsLocalSource = true
                                    onSettingsChanged()
                                }
                            ) { Text("Use microphone") }
                        }
                    }
                }

                SectionCard("Volume") {
                    val localLive = MixerControls.localVolumeEffective(hasLocalSource, mode)
                    Text(
                        if (localLive) "Local source  ${(localVol * 100).toInt()} %"
                        else "Local source — nothing to apply it to",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (localLive) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.secondary
                    )
                    Slider(
                        value = localVol,
                        enabled = localLive,
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

                    MixerControls.explain(mode, hasLocalSource)?.let { why ->
                        Box(Modifier.size(6.dp))
                        Text(
                            why,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                SectionCard("Headset volume") {
                    if (systemVolume.unavailable) {
                        Text(
                            "This device reports no media volume to control.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            "System media volume  ${(masterVolume * 100).toInt()} %",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = masterVolume,
                            onValueChange = { masterVolume = it },
                            onValueChangeFinished = {
                                if (!systemVolume.setFraction(masterVolume)) {
                                    AppState.notice(
                                        "Android refused the volume change. Do Not Disturb may " +
                                            "own the media stream on this phone."
                                    )
                                }
                                masterVolume = systemVolume.fraction
                            }
                        )
                        Box(Modifier.size(6.dp))
                        Text(
                            MixerControls.SYSTEM_VOLUME_NOTE,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            // ------------------------------------------------- source on B
            if (role == Role.TRANSMITTER) {
                SectionCard("What to send") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        InputSource.entries.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { src ->
                                    FilterChip(
                                        selected = inputSource == src,
                                        enabled = !active,
                                        onClick = {
                                            inputSource = src
                                            prefs.inputSource = src
                                        },
                                        label = { Text(src.label) }
                                    )
                                }
                            }
                        }
                    }
                    Box(Modifier.size(6.dp))
                    Text(
                        sourceHint(inputSource),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                SectionCard("This phone's speaker") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Silence it while sending",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = muteSpeaker,
                            enabled = !active,
                            onCheckedChange = {
                                muteSpeaker = it
                                prefs.muteLocalSpeaker = it
                            }
                        )
                    }
                    Box(Modifier.size(6.dp))
                    Text(
                        "Capturing app audio taps the mix; it does not take the sound away from " +
                            "this phone, so what is captured also keeps playing out of its own " +
                            "speaker. Android gives an app no way to redirect or mute another " +
                            "app's output, so the only lever is this phone's media volume, which " +
                            "this turns to zero while sending and restores afterwards.\n\n" +
                            "Whether the other phone still hears the audio with this on depends " +
                            "on where the capture tap sits relative to the volume on your device. " +
                            "Check it on your hardware before relying on it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
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
                            onClick = { peerAddr?.let { onStartTransmitter(it, inputSource) } },
                            enabled = permissionsGranted && !active && peerAddr != null && peerBonded
                        ) { Text("Start sending") }
                        OutlinedButton(onClick = onStopTransmitter, enabled = active) { Text("Stop") }
                    }
                }
            }

            if (capture == CaptureState.CAPTURING_SILENCE &&
                inputSource != InputSource.BUILTIN_MIC
            ) {
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

private fun sourceHint(source: InputSource): String = when (source) {
    InputSource.PLAYBACK_CAPTURE ->
        "What other apps on this phone are playing, as far as Android allows it. Apps that " +
            "forbid playback capture come through as silence, and that is reported as silence."
    InputSource.BUILTIN_MIC ->
        "This phone's built-in microphone. Recording is pinned to the built-in device, never to " +
            "the headset's microphone, so phone A can run its own microphone at the same time " +
            "without the system cutting either of them."
    InputSource.PLAYBACK_AND_MIC ->
        "App audio and the built-in microphone, summed before encoding. Each half's silence is " +
            "tracked separately, so a microphone that Android has muted cannot hide behind the " +
            "app audio."
    InputSource.TEST_TONE ->
        "A generated tone through the direct PCM input. The Stage 1 bring-up path: it proves the " +
            "RFCOMM link and the headset output with no capture consent and no microphone."
}

private fun modeHint(mode: MixMode): String = when (mode) {
    MixMode.HARD_SPLIT ->
        "Left ear: this phone's own source — a local track, or its built-in microphone. Right ear: " +
            "phone B. With both phones on their microphones this puts one microphone in each ear. " +
            "Without system privileges the app cannot steer audio from other apps on this phone, so " +
            "the left channel has to be a source the app owns."
    MixMode.MIXED_STEREO -> "Both sources are summed and played in both ears."
    MixMode.REMOTE_ONLY -> "This phone acts purely as a Bluetooth audio sink for phone B."
    MixMode.PRIORITY -> "The local track is turned down automatically whenever phone B is making sound."
}
