package com.dualstream.uniter.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.dualstream.uniter.core.AppState
import com.dualstream.uniter.core.CaptureState
import com.dualstream.uniter.core.Logx
import com.dualstream.uniter.core.Prefs
import com.dualstream.uniter.core.Role
import com.dualstream.uniter.service.ReceiverService
import com.dualstream.uniter.service.ServiceContract
import com.dualstream.uniter.service.TransmitterService
import com.dualstream.uniter.transport.BtDiscovery
import com.dualstream.uniter.ui.theme.DualStreamTheme

/**
 * Single-activity UI. The activity owns the two things that need an Activity
 * context: runtime permission requests and the MediaProjection consent dialog.
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var discovery: BtDiscovery

    private var pendingTransmitterPeer: String? = null
    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it }
        if (!permissionsGranted) {
            AppState.notice("The app needs Nearby devices and microphone access to work.")
        } else {
            AppState.notice(null)
            discovery.refreshBonded()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val peer = pendingTransmitterPeer
        pendingTransmitterPeer = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null && peer != null) {
            TransmitterService.start(this, peer, result.resultCode, result.data)
        } else {
            AppState.setCapture(CaptureState.NEEDS_CONSENT)
            AppState.notice("Capture consent was declined, so nothing is being sent.")
        }
    }

    private val localTrackLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.onFailure { Logx.w("ui", "could not persist URI permission", it) }
            prefs.localSourceUri = uri.toString()
            ReceiverService.send(this, ServiceContract.ACTION_APPLY_SETTINGS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        discovery = BtDiscovery(this)
        permissionsGranted = requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        setContent {
            DualStreamTheme {
                AppNavHost(
                    prefs = prefs,
                    discovery = discovery,
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
                    onStartReceiver = { ReceiverService.start(this) },
                    onStopReceiver = { ReceiverService.send(this, ServiceContract.ACTION_STOP) },
                    onStartTransmitter = { peer -> requestProjection(peer) },
                    onStartTestTone = { peer -> TransmitterService.startTestTone(this, peer) },
                    onStopTransmitter = { TransmitterService.send(this, ServiceContract.ACTION_STOP) },
                    onReconnect = {
                        if (prefs.role == Role.RECEIVER) {
                            ReceiverService.send(this, ServiceContract.ACTION_RECONNECT)
                        } else {
                            TransmitterService.send(this, ServiceContract.ACTION_RECONNECT)
                        }
                    },
                    onSettingsChanged = {
                        if (prefs.role == Role.RECEIVER) {
                            ReceiverService.send(this, ServiceContract.ACTION_APPLY_SETTINGS)
                        } else {
                            TransmitterService.send(this, ServiceContract.ACTION_APPLY_SETTINGS)
                        }
                    },
                    onPickLocalTrack = {
                        localTrackLauncher.launch(arrayOf("audio/*"))
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        discovery.release()
        super.onDestroy()
    }

    private fun requestProjection(peerAddress: String) {
        val mpm = ContextCompat.getSystemService(this, MediaProjectionManager::class.java)
        if (mpm == null) {
            AppState.setCapture(CaptureState.UNAVAILABLE)
            return
        }
        pendingTransmitterPeer = peerAddress
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_SCAN
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list.toTypedArray()
    }
}
