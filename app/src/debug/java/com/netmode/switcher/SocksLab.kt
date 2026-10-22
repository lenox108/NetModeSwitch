package com.netmode.switcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Debug only. Default: the SOCKS server inside the app on the default network. With "process":
 * the real separate process (LocalSocks.start) on the default network, as the route starts it.
 */
class SocksLab : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val net = context.getSystemService(ConnectivityManager::class.java).activeNetwork ?: return
        if (intent.getBooleanExtra("process", false)) {
            App.scope.launch {
                val e = LocalSocks.start(net)
                Log.i("netmode", "SOCKSLAB process " + (e?.let { "port=${it.port} user=${it.user} pass=${it.pass}" } ?: "failed"))
            }
            return
        }
        val server = SocksServer("labuser", "labpass", { net }) { line, _ -> Log.i("netmode", "SOCKSLAB $line") }
        val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        Log.i("netmode", "SOCKSLAB port=${s.localPort} user=labuser pass=labpass")
        Thread { server.serve(s) }.start()
    }
}
