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
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.dualstream.uniter.core.Logx
import com.dualstream.uniter.protocol.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A device entry for the pairing screen. */
data class PeerDevice(
    val name: String,
    val address: String,
    val bonded: Boolean,
    /** True while the system is pairing with this device. */
    val bonding: Boolean = false,
    /**
     * Whether this device advertises the DualStream service record.
     * Null means "not asked yet": the answer needs an SDP query, which costs a
     * round trip to the device, so it is only fetched on request.
     */
    val runsDualStream: Boolean? = null
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

    /**
     * Whether this phone is currently visible to other devices.
     *
     * This is the step whose absence makes the whole product look broken: an
     * RFCOMM server socket does not make the adapter discoverable, so until
     * this is true an unpaired phone B cannot see phone A at all, however long
     * it scans.
     */
    private val _discoverable = MutableStateFlow(false)
    val discoverable: StateFlow<Boolean> = _discoverable.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun refreshDiscoverable() {
        val a = adapter
        _discoverable.value = try {
            a != null && a.isEnabled &&
                a.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * The system dialog that asks the user to make this phone visible. Android
     * deliberately gives apps no way to do this silently, so the caller has to
     * launch this and let the user agree.
     *
     * @param seconds requested visibility window; the platform caps it, and
     * many builds ignore values above 300
     */
    fun discoverableIntent(seconds: Int = 300): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, seconds)

    /**
     * Asks [address] which Bluetooth services it offers, so the UI can mark the
     * phone that is actually running this app instead of leaving the user to
     * guess from a list of names.
     *
     * The answer arrives asynchronously in [ACTION_UUID]; this only starts the
     * query. This is the closest a normal app can get to being "recognisable"
     * to the other side: our own service record in SDP.
     */
    @SuppressLint("MissingPermission")
    fun probeServices(address: String): Boolean {
        val dev = deviceFor(address) ?: return false
        if (!hasPermissions()) return false
        return try {
            registerReceiver()
            dev.fetchUuidsWithSdp()
        } catch (t: Throwable) {
            Logx.w("discovery", "fetchUuidsWithSdp failed for $address", t)
            false
        }
    }

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
        refreshDiscoverable()
        registerReceiver()
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
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val dev: BluetoothDevice? = extraDevice(intent)
                        val state = intent.getIntExtra(
                            BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE
                        )
                        if (dev != null) {
                            Logx.i("discovery", "bond state of ${dev.address} -> $state")
                            update(dev.address) {
                                it.copy(
                                    bonded = state == BluetoothDevice.BOND_BONDED,
                                    bonding = state == BluetoothDevice.BOND_BONDING
                                )
                            }
                        }
                        refreshBonded()
                    }

                    BluetoothAdapter.ACTION_SCAN_MODE_CHANGED -> {
                        refreshDiscoverable()
                        Logx.i(
                            "discovery",
                            "scan mode changed, discoverable=${_discoverable.value}"
                        )
                    }

                    BluetoothDevice.ACTION_UUID -> {
                        val dev: BluetoothDevice? = extraDevice(intent)
                        @Suppress("DEPRECATION")
                        val uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                        val found = uuids
                            ?.filterIsInstance<ParcelUuid>()
                            ?.any { it.uuid == Protocol.SERVICE_UUID } == true
                        if (dev != null) {
                            Logx.i(
                                "discovery",
                                "SDP of ${dev.address}: DualStream service ${if (found) "present" else "absent"}"
                            )
                            update(dev.address) { it.copy(runsDualStream = found) }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
            addAction(BluetoothDevice.ACTION_UUID)
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

    /** Patches one entry in the list, keeping everything else as it was. */
    private fun update(address: String, block: (PeerDevice) -> PeerDevice) {
        _devices.value = _devices.value.map {
            if (it.address == address) block(it) else it
        }
    }

    private fun merge(a: List<PeerDevice>, b: List<PeerDevice>): List<PeerDevice> {
        val byAddress = LinkedHashMap<String, PeerDevice>()
        for (d in a) byAddress[d.address] = d
        for (d in b) {
            val prev = byAddress[d.address]
            byAddress[d.address] = if (prev == null) d else prev.copy(
                name = if (prev.name == prev.address) d.name else prev.name,
                bonded = prev.bonded || d.bonded,
                bonding = d.bonding || prev.bonding,
                // An SDP answer is expensive to get; never drop it on a rescan.
                runsDualStream = prev.runsDualStream ?: d.runsDualStream
            )
        }
        return byAddress.values.sortedWith(
            compareByDescending<PeerDevice> { it.bonded }.thenBy { it.name.lowercase() }
        )
    }
}
