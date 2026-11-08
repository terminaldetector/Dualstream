package com.dualstream.uniter.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.dualstream.uniter.core.Logx
import com.dualstream.uniter.protocol.FrameReader
import com.dualstream.uniter.protocol.FrameWriter
import com.dualstream.uniter.protocol.Protocol
import java.io.Closeable
import java.io.IOException

/**
 * One established RFCOMM connection, with framing on top.
 *
 * The socket is a Bluetooth Classic RFCOMM channel obtained through the public
 * Android API and secured by system pairing, as required by section 3. No
 * reflection-based `createRfcommSocket` fallback is used: an unpaired or
 * insecure channel is not acceptable for this product.
 */
class BtLink(
    val socket: BluetoothSocket,
    val remoteName: String,
    val remoteAddress: String
) : Closeable {

    val reader = FrameReader(socket.inputStream)
    val writer = FrameWriter(socket.outputStream)

    @Volatile
    var closed = false
        private set

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
        Logx.i("link", "closed link to $remoteName")
    }
}

/**
 * Receiver side (phone A): advertises the service record and waits for phone B.
 */
class RfcommAcceptor(private val adapter: BluetoothAdapter) : Closeable {

    private var server: BluetoothServerSocket? = null

    @Volatile
    private var cancelled = false

    /**
     * Blocks until a peer connects or the acceptor is closed.
     * @return the accepted link, or null if cancelled
     */
    @SuppressLint("MissingPermission")  // BLUETOOTH_CONNECT verified by the caller
    @Throws(IOException::class)
    fun accept(): BtLink? {
        if (cancelled) return null
        val ss = server ?: adapter.listenUsingRfcommWithServiceRecord(
            Protocol.SERVICE_NAME, Protocol.SERVICE_UUID
        ).also {
            server = it
            Logx.i("accept", "listening on ${Protocol.SERVICE_UUID}")
        }
        val sock = try {
            ss.accept()
        } catch (e: IOException) {
            if (cancelled) return null
            throw e
        }
        // One connection at a time: close the server record so a second phone
        // cannot interleave frames into the same decoder.
        runCatching { ss.close() }
        server = null

        val dev = sock.remoteDevice
        val name = safeName(dev)
        Logx.i("accept", "accepted $name / ${dev.address}")
        return BtLink(sock, name, dev.address)
    }

    override fun close() {
        cancelled = true
        runCatching { server?.close() }
        server = null
    }
}

/**
 * Transmitter side (phone B): connects to the service record on phone A.
 */
class RfcommConnector(
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter? = null
) {

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun connect(): BtLink {
        // Discovery and connecting contend for the radio, and Android's own
        // documentation says to cancel discovery first. Leaving a scan running
        // is a classic cause of connect failing with "read failed, socket might
        // closed or timeout, read ret: -1" — which is exactly what the field
        // log showed on every reconnect attempt.
        runCatching {
            if (adapter?.isDiscovering == true) {
                Logx.i("connect", "cancelling discovery before connecting")
                adapter.cancelDiscovery()
            }
        }.onFailure { Logx.w("connect", "cancelDiscovery failed", it) }

        val sock = device.createRfcommSocketToServiceRecord(Protocol.SERVICE_UUID)
        try {
            sock.connect()
        } catch (e: IOException) {
            runCatching { sock.close() }
            throw e
        }
        val name = safeName(device)
        Logx.i("connect", "connected to $name / ${device.address}")
        return BtLink(sock, name, device.address)
    }
}

@SuppressLint("MissingPermission")
internal fun safeName(device: BluetoothDevice): String =
    try {
        device.name ?: device.address
    } catch (t: SecurityException) {
        device.address
    }
