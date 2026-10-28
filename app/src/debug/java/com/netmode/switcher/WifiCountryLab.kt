package com.netmode.switcher

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Emulator-only transport audit. Runs the production client with this process's real UID. */
object WifiCountryCliLab {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val client = WifiCountryClient { command ->
            val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { out }
            out
        }
        try {
            val state = when (args.firstOrNull()) {
                "set" -> client.change(requireNotNull(args.getOrNull(1)))
                "auto" -> client.change(null)
                else -> client.read()
            }
            println(JSONObject().put("country", state.country).put("driver", state.driver)
                .put("overrideKnown", state.overrideKnown).put("override", state.override ?: JSONObject.NULL)
                .put("supported", state.supported).put("root", state.root))
        } catch (e: Exception) {
            println(JSONObject().put("error", e.message))
        }
    }
}

/** Opens the real screen. Optional read-only paint fixture never changes Wi-Fi. */
class WifiCountryLab : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("fixture", false)) {
            val callback = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (activity !is WifiCountryActivity) return
                    application.unregisterActivityLifecycleCallbacks(this)
                    val handler = Handler(Looper.getMainLooper())
                    val busy = WifiCountryActivity::class.java.getDeclaredField("busy").apply { isAccessible = true }
                    val paint = object : Runnable {
                        override fun run() {
                            if (activity.isDestroyed) return
                            if (busy.getBoolean(activity)) { handler.postDelayed(this, 300); return }
                            val value = WifiCountryState("JP", "US", true, "JP", true, true)
                            WifiCountryActivity::class.java.getDeclaredMethod("paint", WifiCountryState::class.java)
                                .apply { isAccessible = true }.invoke(activity, value)
                            activity.findViewById<android.widget.TextView>(R.id.result).text =
                                "Пример состояния: Android — JP, драйвер — US. Wi-Fi не изменялся."
                        }
                    }
                    handler.postDelayed(paint, 500)
                }
                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityPaused(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            }
            application.registerActivityLifecycleCallbacks(callback)
        }
        startActivity(Intent(this, WifiCountryActivity::class.java))
        finish()
    }
}
