package com.dualstream.uniter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualstream.uniter.core.Prefs
import com.dualstream.uniter.core.Role
import com.dualstream.uniter.transport.BtDiscovery
import com.dualstream.uniter.ui.theme.StatusColors

/**
 * Device search, pairing and the choice of the trusted peer (section 3,
 * items 1-3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    prefs: Prefs,
    discovery: BtDiscovery,
    onRequestDiscoverable: () -> Unit,
    onBack: () -> Unit
) {
    val devices by discovery.devices.collectAsStateWithLifecycle()
    val scanning by discovery.scanning.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(prefs.trustedPeerAddress) }
    val discoverable by discovery.discoverable.collectAsStateWithLifecycle()
    val role = remember { prefs.role }

    LaunchedEffect(Unit) { discovery.refreshBonded() }

    // A device that has just finished pairing is almost certainly the one the
    // user wants, so select it rather than making them tap again.
    LaunchedEffect(devices) {
        if (selected == null) {
            devices.firstOrNull { it.bonded && it.runsDualStream == true }?.let { dev ->
                prefs.trustedPeerAddress = dev.address
                prefs.trustedPeerName = dev.name
                selected = dev.address
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices") },
                navigationIcon = { IconButton(onClick = onBack) { Text("‹") } },
                actions = {
                    if (scanning) {
                        TextButton(onClick = { discovery.stopScan() }) { Text("Stop") }
                    } else {
                        TextButton(onClick = { discovery.startScan() }) { Text("Scan") }
                    }
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
            Text(
                "Pairing is done by Android itself; this app only remembers the device and " +
                    "reconnects to it. The order matters:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Box(Modifier.size(6.dp))
            Text(
                "1. On phone A press Make visible.\n" +
                    "2. On phone B press Scan and pair with phone A.\n" +
                    "3. On phone B choose it here with Use this phone.",
                style = MaterialTheme.typography.bodySmall
            )
            Box(Modifier.size(10.dp))

            // Same reasoning as on the main screen: a listening socket is not
            // visibility, and without visibility the scan below finds nothing.
            if (role == Role.RECEIVER) {
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (discoverable)
                                "This phone is visible to other devices."
                            else
                                "This phone is not visible. Phone B cannot find it in a search " +
                                    "until you make it visible.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Box(Modifier.size(8.dp))
                        Button(onClick = onRequestDiscoverable) {
                            Text(if (discoverable) "Extend visibility" else "Make visible")
                        }
                    }
                }
                Box(Modifier.size(10.dp))
            } else {
                Text(
                    "Phone A must be made visible first — on phone A, in this same screen. " +
                        "Otherwise it will not appear in the list below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Box(Modifier.size(10.dp))
            }

            if (!discovery.isBluetoothOn) {
                Card {
                    Text(
                        "Bluetooth is off. Turn it on in the system settings.",
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Box(Modifier.size(10.dp))
            }

            if (scanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Box(Modifier.size(8.dp))
                    Text("Searching…", style = MaterialTheme.typography.bodySmall)
                }
                Box(Modifier.size(10.dp))
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices, key = { it.address }) { dev ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(dev.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                dev.address,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Box(Modifier.size(6.dp))
                            when (dev.runsDualStream) {
                                true -> StatusRow(
                                    "DualStream", "running on this device", StatusColors.good
                                )
                                false -> StatusRow(
                                    "DualStream", "not advertised by this device", StatusColors.warn
                                )
                                null -> StatusRow(
                                    "DualStream", "not checked", MaterialTheme.colorScheme.secondary
                                )
                            }
                            Box(Modifier.size(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (dev.bonding) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            Modifier.size(16.dp), strokeWidth = 2.dp
                                        )
                                        Box(Modifier.size(8.dp))
                                        Text("Pairing…", style = MaterialTheme.typography.bodySmall)
                                    }
                                } else if (!dev.bonded) {
                                    OutlinedButton(onClick = {
                                        discovery.requestPairing(dev.address)
                                    }) { Text("Pair") }
                                }
                                OutlinedButton(
                                    enabled = dev.bonded,
                                    onClick = {
                                        prefs.trustedPeerAddress = dev.address
                                        prefs.trustedPeerName = dev.name
                                        selected = dev.address
                                    }
                                ) {
                                    Text(if (selected == dev.address) "Selected ✓" else "Use this phone")
                                }
                            }
                            Box(Modifier.size(6.dp))
                            OutlinedButton(onClick = { discovery.probeServices(dev.address) }) {
                                Text("Check for DualStream")
                            }
                            if (!dev.bonded && !dev.bonding) {
                                Box(Modifier.size(6.dp))
                                Text(
                                    "Pair first: the RFCOMM channel is opened as a secure socket, which " +
                                        "requires a system bond.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
