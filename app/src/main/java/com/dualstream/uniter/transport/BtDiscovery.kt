package com.dualstream.uniter.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.dualstream.uniter.core.Logx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A device entry for the pairing screen. */
data class PeerDevice(
    val name: String,
    val address: String,
    val bonded: Boolean
)

/**
 * Device search and pairing (section 3, items 1-3).
 *
 * Pairing itself is handed to the system: [requestPairing] calls
 * `createBond()`, which shows the platform's own consent dialog. The app never
 * tries to drive pairing through private APIs.
 */
class BtDiscovery(private val context: Context) {

    private val manager: BluetoothManager? =
        ContextCompat.getSystemService(context, BluetoothManager::class.java)

    val adapter: BluetoothAdapter? get() = manager?.adapter

    private val _devices = MutableStateFlow<List<PeerDevice>>(emptyList())
    val devices: StateFlow<List<PeerDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    /** Permissions this build needs for discovery and RFCOMM on this API level. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun refreshBonded() {
        val a = adapter ?: return
        if (!hasPermissions()) return
        val bonded = try {
            a.bondedDevices.orEmpty().map {
                PeerDevice(safeName(it), it.address, bonded = true)
            }
        } catch (t: SecurityException) {
            Logx.w("discovery", "bondedDevices denied", t)
            emptyList()
        }
        _devices.value = merge(bonded, _devices.value.filter { !it.bonded })
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val a = adapter ?: return
        if (!hasPermissions()) {
            Logx.w("discovery", "scan without permissions, ignored")
            return
        }
        if (!a.isEnabled) {
            Logx.w("discovery", "scan while Bluetooth is off, ignored")
            return
        }
        refreshBonded()
        registerReceiver()
        runCatching {
            if (a.isDiscovering) a.cancelDiscovery()
            a.startDiscovery()
            _scanning.value = true
            Logx.i("discovery", "discovery started")
        }.onFailure { Logx.w("discovery", "startDiscovery failed", it) }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        runCatching { if (adapter?.isDiscovering == true) adapter?.cancelDiscovery() }
        _scanning.value = false
        unregisterReceiver()
    }

    /** Asks the system to pair with [address]. Returns false if it could not be started. */
    @SuppressLint("MissingPermission")
    fun requestPairing(address: String): Boolean {
        val dev = deviceFor(address) ?: return false
        return try {
            if (dev.bondState == BluetoothDevice.BOND_BONDED) true else dev.createBond()
        } catch (t: Throwable) {
            Logx.w("discovery", "createBond failed", t)
            false
        }
    }

    fun deviceFor(address: String): BluetoothDevice? = try {
        adapter?.getRemoteDevice(address)
    } catch (t: IllegalArgumentException) {
        Logx.w("discovery", "bad address $address", t)
        null
    }

    @SuppressLint("MissingPermission")
    fun isBonded(address: String): Boolean =
        deviceFor(address)?.let {
            try {
                it.bondState == BluetoothDevice.BOND_BONDED
            } catch (t: SecurityException) {
                false
            }
        } ?: false

    /**
     * Reads the device out of a discovery broadcast. The untyped
     * `getParcelableExtra(String)` is deprecated from API 33, and the typed
     * overload only exists from API 33, so both paths are needed.
     */
    @Suppress("DEPRECATION")
    private fun extraDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun registerReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val dev: BluetoothDevice? = extraDevice(intent)
                        if (dev != null) {
                            val entry = PeerDevice(
                                safeName(dev), dev.address,
                                bonded = runCatching {
                                    dev.bondState == BluetoothDevice.BOND_BONDED
                                }.getOrDefault(false)
                            )
                            _devices.value = merge(_devices.value, listOf(entry))
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        _scanning.value = false
                        Logx.i("discovery", "discovery finished, ${_devices.value.size} devices")
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> refreshBonded()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_EXPORTED)
        receiver = r
    }

    private fun unregisterReceiver() {
        val r = receiver ?: return
        receiver = null
        runCatching { context.unregisterReceiver(r) }
    }

    fun release() {
        stopScan()
    }

    private fun merge(a: List<PeerDevice>, b: List<PeerDevice>): List<PeerDevice> {
        val byAddress = LinkedHashMap<String, PeerDevice>()
        for (d in a) byAddress[d.address] = d
        for (d in b) {
            val prev = byAddress[d.address]
            byAddress[d.address] = if (prev != null && prev.bonded) prev else d
        }
        return byAddress.values.sortedWith(
            compareByDescending<PeerDevice> { it.bonded }.thenBy { it.name.lowercase() }
        )
    }
}
