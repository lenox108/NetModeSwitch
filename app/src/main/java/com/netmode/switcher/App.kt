package com.netmode.switcher

import android.app.Application
import android.content.Context
import com.google.android.material.color.DynamicColors
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
        // Picks up the OS accent colour so the app looks native on OxygenOS.
        DynamicColors.applyToActivitiesIfAvailable(this)
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        // Must be set before the first Shell.getShell() call.
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setTimeout(20)
        )

        // OxygenOS drops the PendingIntent network callback when it cleans the app up, so it
        // is re-registered on every process start. The call is a single binder round trip and
        // returns immediately when there is nothing to watch.
        scope.launch {
            runCatching { Migration.run() }
            WifiMonitor.syncAll()
            runCatching { Widgets.watch() }
            runCatching { RuleTiles.watch() }
            // Also clears rules a previous mode left behind, e.g. after a crash or reinstall.
            runCatching { TetherRoute.apply() }
            // Catches a toggle made while the process was not running.
            if (AppFreezer.hasDataRules()) runCatching { DataWatch.check() }
        }
    }

    companion object {
        lateinit var ctx: Context
            private set

        /**
         * Work outlives the component that started it on purpose: a QS tile is destroyed the
         * moment the shade closes, and it must not take a pending root rebind down with it.
         */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
