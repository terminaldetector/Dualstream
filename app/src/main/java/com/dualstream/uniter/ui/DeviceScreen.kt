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
import com.dualstream.uniter.transport.BtDiscovery

/**
 * Device search, pairing and the choice of the trusted peer (section 3,
 * items 1-3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    prefs: Prefs,
    discovery: BtDiscovery,
    onBack: () -> Unit
) {
    val devices by discovery.devices.collectAsStateWithLifecycle()
    val scanning by discovery.scanning.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(prefs.trustedPeerAddress) }

    LaunchedEffect(Unit) { discovery.refreshBonded() }

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
                "Pick the other phone. Pairing is done by Android itself; this app only remembers " +
                    "the device and reconnects to it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Box(Modifier.size(10.dp))

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
                            Box(Modifier.size(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!dev.bonded) {
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
                            if (!dev.bonded) {
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
