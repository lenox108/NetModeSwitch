package com.netmode.switcher

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * Bluetooth connect / disconnect of any device. ACL broadcasts are exempt from the implicit
 * broadcast limits, so a manifest receiver gets them with the app not running and no
 * service kept alive; they need BLUETOOTH_CONNECT, granted when a device is picked.
 */
class BtReceiver : BroadcastReceiver() {

    @android.annotation.SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val device = Compat.btDevice(intent) ?: return
        val address = device.address ?: return
        val name = Compat.btName(device)
        val pending = goAsync()
        App.scope.launch {
            try {
                BtDevices.remember(address, name)
                val key = address.uppercase()
                Prefs.btConnected = if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) Prefs.btConnected + key
                else Prefs.btConnected - key
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> AppFreezer.onBtConnected(address, name)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> AppFreezer.onBtDisconnected(address, name)
                }
                AlarmScheduler.rearm()
            } finally {
                pending.finish()
            }
        }
    }
}
