package com.netmode.switcher

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.netmode.switcher.databinding.ActivitySetupBinding
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

internal object SetupFlow {
    const val STEPS = 4

    /** Once, and only on a fresh install: whoever already has rules or saw the check knows the app. */
    fun shouldOffer(offered: Boolean, checkOffered: Boolean, hasRules: Boolean): Boolean =
        !offered && !checkOffered && !hasRules
}

/** First start: access, permissions, the phone check, a first rule. Every step can be skipped. */
class SetupActivity : AppCompatActivity() {

    private lateinit var ui: ActivitySetupBinding
    private var step = 0
    private var access: Backend? = null

    private val backStep = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { step--; render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(ui.root)
        step = savedInstanceState?.getInt("step") ?: 0
        onBackPressedDispatcher.addCallback(this, backStep)
        ui.next.setOnClickListener { if (step == SetupFlow.STEPS - 1) finish() else { step++; render() } }
        ui.skip.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
        lifecycleScope.launch {
            access = Privileged.choose()
            render()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("step", step)
    }

    private fun render() {
        backStep.isEnabled = step > 0
        ui.progress.setProgressCompat(step + 1, false)
        ui.stepLabel.text = getString(R.string.setup_step, step + 1, SetupFlow.STEPS)
        ui.next.setText(if (step == SetupFlow.STEPS - 1) R.string.setup_done else R.string.setup_next)
        ui.skip.visibility = if (step == SetupFlow.STEPS - 1) View.GONE else View.VISIBLE
        ui.state.visibility = View.GONE
        ui.action.visibility = View.VISIBLE
        ui.action.isEnabled = true
        ui.templates.removeAllViews()
        when (step) {
            0 -> {
                ui.title.setText(R.string.setup_access_title)
                ui.body.setText(R.string.setup_access_body)
                ui.state.visibility = View.VISIBLE
                ui.state.text = when (access) {
                    null -> getString(R.string.working)
                    Backend.ROOT -> getString(R.string.setup_access_root)
                    Backend.SHIZUKU -> getString(R.string.setup_access_shizuku)
                    Backend.AUTO -> getString(R.string.setup_access_none)
                }
                ui.action.setText(R.string.setup_access_retry)
                ui.action.setOnClickListener { recheck() }
            }
            1 -> {
                ui.title.setText(R.string.setup_perms_title)
                ui.body.setText(R.string.setup_perms_body)
                ui.action.setText(R.string.setup_perms_open)
                ui.action.setOnClickListener { startActivity(Intent(this, PermissionsActivity::class.java)) }
            }
            2 -> {
                val can = access == Backend.ROOT || access == Backend.SHIZUKU
                ui.title.setText(R.string.setup_check_title)
                ui.body.setText(if (can) R.string.setup_check_body else R.string.setup_check_no_access)
                ui.action.setText(R.string.setup_check_open)
                ui.action.isEnabled = can
                ui.action.setOnClickListener {
                    Prefs.checkOffered = true
                    startActivity(Intent(this, CheckActivity::class.java).putExtra(CheckActivity.EXTRA_START, true))
                }
            }
            else -> {
                ui.title.setText(R.string.setup_rule_title)
                ui.body.setText(R.string.setup_rule_body)
                ui.action.visibility = View.GONE
                Templates.all.forEach { t ->
                    ui.templates.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        isAllCaps = false
                        text = getString(t.title) + " — " + getString(t.short)
                        setOnClickListener {
                            startActivity(Intent(this@SetupActivity, RuleEditActivity::class.java)
                                .putExtra(RuleEditActivity.EXTRA_TEMPLATE, t.rule(this@SetupActivity).toJson().toString()))
                        }
                    })
                }
            }
        }
    }

    private fun recheck() {
        runCatching {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(3)
        }
        lifecycleScope.launch {
            access = Privileged.choose()
            render()
        }
    }
}
