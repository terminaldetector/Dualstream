package com.dualstream.uniter.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dualstream.uniter.core.InputSource
import com.dualstream.uniter.core.Prefs
import com.dualstream.uniter.transport.BtDiscovery

enum class Screen { MAIN, DEVICES, SETTINGS, DIAGNOSTICS }

/**
 * Minimal screen switch. A navigation library would add a dependency for three
 * destinations, and the back behaviour here is trivial.
 */
@Composable
fun AppNavHost(
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
    onPickLocalTrack: () -> Unit
) {
    var screen by remember { mutableStateOf(Screen.MAIN) }

    when (screen) {
        Screen.MAIN -> MainScreen(
            prefs = prefs,
            discovery = discovery,
            permissionsGranted = permissionsGranted,
            onRequestPermissions = onRequestPermissions,
            onRequestDiscoverable = onRequestDiscoverable,
            onRequestDndAccess = onRequestDndAccess,
            onStartReceiver = onStartReceiver,
            onStopReceiver = onStopReceiver,
            onStartTransmitter = onStartTransmitter,
            onStopTransmitter = onStopTransmitter,
            onReconnect = onReconnect,
            onSettingsChanged = onSettingsChanged,
            onPickLocalTrack = onPickLocalTrack,
            onOpenDevices = { screen = Screen.DEVICES },
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenDiagnostics = { screen = Screen.DIAGNOSTICS }
        )

        Screen.DEVICES -> DeviceScreen(
            prefs = prefs,
            discovery = discovery,
            onRequestDiscoverable = onRequestDiscoverable,
            onBack = { screen = Screen.MAIN }
        )

        Screen.SETTINGS -> SettingsScreen(
            prefs = prefs,
            onBack = { screen = Screen.MAIN },
            onSettingsChanged = onSettingsChanged,
            onPickLocalTrack = onPickLocalTrack
        )

        Screen.DIAGNOSTICS -> DiagnosticsScreen(
            onBack = { screen = Screen.MAIN }
        )
    }
}
