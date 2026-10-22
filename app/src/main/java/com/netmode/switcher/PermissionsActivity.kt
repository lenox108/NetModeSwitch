package com.netmode.switcher

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.netmode.switcher.databinding.ActivityPermissionsBinding
import com.netmode.switcher.databinding.RowPermBinding
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/** Every permission the app uses, its state, and a tap that asks for exactly that one. */
class PermissionsActivity : AppCompatActivity() {

    private lateinit var ui: ActivityPermissionsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityPermissionsBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    enum class Perm(val title: Int, val hint: Int) {
        ACCESS(R.string.perm_access, R.string.perm_access_hint),
        ALARMS(R.string.perm_alarms, R.string.perm_alarms_hint),
        BATTERY(R.string.perm_battery, R.string.perm_battery_hint),
        NEARBY(R.string.perm_nearby, R.string.perm_nearby_hint),
        USAGE(R.string.perm_usage, R.string.perm_usage_hint),
        PHONE(R.string.perm_phone, R.string.perm_phone_hint),
        NOTIFY(R.string.perm_notify, R.string.perm_notify_hint),
    }

    /** Access through root or Shizuku, as last checked; null while the check runs. */
    private var access: Backend? = null

    private fun render() {
        lifecycleScope.launch {
            access = Privileged.choose()
            renderPerms()
        }
        renderPerms()
    }

    private fun granted(p: Perm): Boolean = when (p) {
        Perm.ACCESS -> access != null && access != Backend.AUTO
        Perm.ALARMS -> Compat.canExact(getSystemService(AlarmManager::class.java))
        Perm.BATTERY -> isBatteryExempt()
        Perm.NEARBY -> Compat.granted(this, Manifest.permission.BLUETOOTH_CONNECT)
        Perm.PHONE -> checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        Perm.USAGE -> usageGranted(this)
        Perm.NOTIFY -> Compat.granted(this, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun renderPerms() {
        ui.permList.removeAllViews()
        for (p in Perm.entries) {
            val row = RowPermBinding.inflate(layoutInflater, ui.permList, false)
            row.title.setText(p.title)
            row.hint.setText(p.hint)
            val ok = granted(p)
            val needed = required(this, p)
            row.state.text = when {
                p == Perm.ACCESS && access == null -> "…"
                p == Perm.ACCESS && ok -> if (access == Backend.ROOT) "root ✓" else "Shizuku ✓"
                ok -> getString(R.string.perm_ok)
                needed -> getString(R.string.perm_missing)
                else -> getString(R.string.perm_optional)
            }
            // Theme colours: the fixed green and red read badly on the dark surface.
            row.state.setTextColor(com.google.android.material.color.MaterialColors.getColor(row.state, when {
                ok -> androidx.appcompat.R.attr.colorPrimary
                needed -> android.R.attr.colorError
                else -> com.google.android.material.R.attr.colorOnSurfaceVariant
            }))
            row.root.setOnClickListener { request(p) }
            ui.permList.addView(row.root)
        }
    }

    /**
     * Asks for exactly this permission. Android shows no request for one already granted,
     * so that case only says so instead of dropping the user into app info.
     */
    private fun request(p: Perm) {
        if (granted(p)) {
            Toast.makeText(this, R.string.perm_already, Toast.LENGTH_SHORT).show()
            return
        }
        val pkg = Uri.parse("package:$packageName")
        when (p) {
            Perm.ACCESS -> {
                val shizukuUp = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                if (shizukuUp) runCatching { Shizuku.requestPermission(3) }
                else Toast.makeText(this, R.string.perm_no_access, Toast.LENGTH_LONG).show()
            }
            Perm.ALARMS -> runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)) }
            Perm.BATTERY -> runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)) }
            Perm.NEARBY -> ask(Manifest.permission.BLUETOOTH_CONNECT)
            Perm.PHONE -> ask(Manifest.permission.READ_PHONE_STATE)
            Perm.NOTIFY -> ask(Manifest.permission.POST_NOTIFICATIONS)
            // An app-op the privileged shell grants directly; no settings screen needed.
            Perm.USAGE -> lifecycleScope.launch {
                runCatching { Privileged.exec("appops set $packageName GET_USAGE_STATS allow") }
                if (!usageGranted(this@PermissionsActivity)) {
                    runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                }
                render()
            }
        }
    }

    private fun ask(permission: String) {
        pendingAsk = permission
        requestPermissions(arrayOf(permission), 7)
    }

    private var pendingAsk: String? = null

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val asked = pendingAsk ?: return
        pendingAsk = null
        val denied = grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        // A second refusal makes Android answer "denied" without showing anything; the only
        // way left is the app's permission screen, so go there and say why.
        if (denied && !shouldShowRequestPermissionRationale(asked)) {
            Toast.makeText(this, R.string.perm_denied_forever, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        render()
    }

    private fun isBatteryExempt(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    companion object {
        /** How many permissions are missing, for the summary row in Settings. */
        fun usageGranted(ctx: Context): Boolean = runCatching {
            ctx.getSystemService(android.app.AppOpsManager::class.java).unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName,
            ) == android.app.AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        /**
         * Whether the app cannot do its job without [p] as it is set up now. The rest only add
         * something (carrier names, the status notification) and are not counted as missing.
         */
        fun required(ctx: Context, p: Perm): Boolean {
            val rules = Prefs.appRules.filter { it.enabled }
            return when (p) {
                Perm.ACCESS, Perm.ALARMS, Perm.BATTERY -> true
                Perm.NEARBY -> rules.any { it.trigger == Trigger.BLUETOOTH || it.endBtAddress != null }
                Perm.USAGE -> rules.any { it.trigger == Trigger.APP_OPEN }
                Perm.NOTIFY -> Prefs.statusNotification
                Perm.PHONE -> false
            }
        }

        suspend fun missingCount(ctx: Context): Int {
            var n = 0
            if (Privileged.choose() == Backend.AUTO) n++
            if (!Compat.canExact(ctx.getSystemService(AlarmManager::class.java))) n++
            if (!ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) n++
            if (required(ctx, Perm.NEARBY) && !Compat.granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)) n++
            if (required(ctx, Perm.USAGE) && !usageGranted(ctx)) n++
            if (required(ctx, Perm.NOTIFY) && !Compat.granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) n++
            return n
        }
    }
}
