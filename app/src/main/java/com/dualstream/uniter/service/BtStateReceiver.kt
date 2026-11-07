package com.dualstream.uniter.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dualstream.uniter.core.AppState
import com.dualstream.uniter.core.HeadsetState
import com.dualstream.uniter.core.LinkState
import com.dualstream.uniter.core.Logx

/**
 * Reacts to the adapter being switched off and to the headset coming and going
 * (section 9: "обработка выключения Bluetooth", "обработка отключения
 * гарнитуры").
 *
 * The receiver only publishes state; the services own recovery, because they
 * are the ones holding sockets and audio buffers.
 */
class BtStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF
                )
                when (state) {
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                        Logx.i("btstate", "adapter off")
                        AppState.setLink(LinkState.BLUETOOTH_OFF)
                        AppState.setHeadset(HeadsetState.DISCONNECTED)
                        AppState.notice("Bluetooth was turned off. The bridge is paused.")
                    }
                    BluetoothAdapter.STATE_ON -> {
                        Logx.i("btstate", "adapter on")
                        AppState.notice(null)
                        // The service loops poll the adapter, so they pick this up by
                        // themselves; nudging them keeps recovery prompt.
                        AppState.setLink(LinkState.RECOVERING)
                    }
                }
            }

            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" -> {
                val state = intent.getIntExtra(
                    BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED
                )
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        AppState.setHeadset(HeadsetState.CONNECTED_A2DP)
                        Logx.i("btstate", "A2DP connected")
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        AppState.setHeadset(HeadsetState.DISCONNECTED)
                        Logx.i("btstate", "A2DP disconnected")
                    }
                }
            }

            android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                Logx.i("btstate", "ACL disconnected")
        }
    }
}
