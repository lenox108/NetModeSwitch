package com.netmode.switcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug only: "SIM <slot> was just switched on", without a second SIM to switch. */
class SimLab : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SimWatch.forgetForTest(intent.getIntExtra("slot", 0))
    }
}
