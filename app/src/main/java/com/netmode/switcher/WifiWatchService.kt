package com.netmode.switcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Holds a real NetworkCallback, which is the only way to learn about a Wi-Fi *disconnect*.
 *
 * In MODE_WATCH_LOSS it is started on connecting to a network whose rule has a disconnect
 * action and stops itself once that disconnect is handled — so the notification is visible
 * only while it is genuinely needed. MODE_ALWAYS is the opt-in fallback for when OxygenOS
 * starts dropping the notification-free path.
 */
class WifiWatchService : Service() {

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var mode: String = MODE_WATCH_LOSS

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A connect event asks for WATCH_LOSS; that must never downgrade a persistent watcher,
        // which would then stop itself at the first disconnect.
        val requested = intent?.getStringExtra(EXTRA_MODE) ?: MODE_WATCH_LOSS
        if (mode != MODE_ALWAYS) mode = requested
        startForeground(NOTIFICATION_ID, buildNotification())
        if (callback == null) registerCallback()
        return if (mode == MODE_ALWAYS) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        callback?.let { runCatching { connectivity().unregisterNetworkCallback(it) } }
        callback = null
        super.onDestroy()
    }

    private fun registerCallback() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                App.scope.launch { WifiRules.onConnected(applicationContext) }
            }

            override fun onLost(network: Network) {
                App.scope.launch {
                    val handled = WifiRules.onLost()
                    if (handled && mode == MODE_WATCH_LOSS) stopSelf()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { connectivity().registerNetworkCallback(request, cb) }
            .onSuccess { callback = cb }
    }

    private fun connectivity() = getSystemService(ConnectivityManager::class.java)

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_wifi), NotificationManager.IMPORTANCE_MIN)
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.watching_wifi))
            .setContentText(Prefs.lastSsid ?: getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_tile)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val MODE_WATCH_LOSS = "watch_loss"
        const val MODE_ALWAYS = "always"

        private const val TAG = "netmode"
        private const val EXTRA_MODE = "mode"
        private const val CHANNEL = "wifi_watch"
        private const val NOTIFICATION_ID = 1

        /**
         * Returns false when Android refused the start. That happens for a background start
         * unless the app is on the battery allowlist, and it is the difference between the
         * disconnect rule working and silently doing nothing — so the caller reports it.
         */
        fun start(context: Context, mode: String): Boolean {
            val intent = Intent(context, WifiWatchService::class.java).putExtra(EXTRA_MODE, mode)
            return runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "foreground start refused", it) }
                .isSuccess
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WifiWatchService::class.java)) }
        }
    }
}
