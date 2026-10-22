package com.netmode.switcher

import android.app.AlarmManager
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * The few platform calls that changed after Android 10, in one place. The "modern" build
 * (Android 14+) always takes the first branch; the "a10" build runs down to Android 10.
 */
object Compat {

    val SDK: Int get() = Build.VERSION.SDK_INT

    /** The subscription in [slot], or -1. A static call from Android 14, a lookup before. */
    fun subId(slot: Int): Int = runCatching {
        if (SDK >= 34) SubscriptionManager.getSubscriptionId(slot)
        else App.ctx.getSystemService(SubscriptionManager::class.java)
            .getActiveSubscriptionInfoForSimSlotIndex(slot)?.subscriptionId ?: -1
    }.getOrDefault(-1)

    /** Exact alarms need a grant from Android 12; before, they are always allowed. */
    fun canExact(am: AlarmManager): Boolean = SDK < 31 || am.canScheduleExactAlarms()

    @Suppress("DEPRECATION")
    fun modemCount(tm: TelephonyManager): Int = if (SDK >= 30) tm.supportedModemCount else tm.phoneCount

    /** The name the user gave the device (alias, Android 11+), else its own. */
    fun btName(device: BluetoothDevice): String? = runCatching {
        if (SDK >= 30) device.alias ?: device.name else device.name
    }.getOrNull()

    @Suppress("DEPRECATION")
    fun btDevice(intent: Intent): BluetoothDevice? =
        if (SDK >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    @Suppress("DEPRECATION")
    fun appInfo(pm: PackageManager, pkg: String): ApplicationInfo =
        if (SDK >= 33) pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        else pm.getApplicationInfo(pkg, 0)

    /** "Add the tile" through the system dialog exists from Android 13; before, by hand. */
    val canRequestTile: Boolean get() = SDK >= 33

    /** Before Android 13 a tile is added by hand in the shade's edit mode: say how. */
    fun tilesByHand(ctx: android.content.Context) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setMessage(R.string.tiles_by_hand)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * A runtime permission, counting as granted where it does not exist yet: "nearby devices"
     * (Bluetooth) came with Android 12, notifications with Android 13 — before, both were free.
     */
    fun granted(ctx: android.content.Context, perm: String): Boolean = when {
        perm == android.Manifest.permission.BLUETOOTH_CONNECT && SDK < 31 -> true
        perm == android.Manifest.permission.POST_NOTIFICATIONS && SDK < 33 -> true
        else -> ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    /** The dump with "<iface> - TetheredState" lines: its own service from Android 11. */
    val TETHER_DUMP: String get() = if (SDK >= 30) "dumpsys tethering" else "dumpsys connectivity"
}
