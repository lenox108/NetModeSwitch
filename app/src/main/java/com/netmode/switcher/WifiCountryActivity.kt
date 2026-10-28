package com.netmode.switcher

import android.os.Bundle
import android.text.InputFilter
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.netmode.switcher.databinding.ActivityWifiCountryBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

class WifiCountryActivity : AppCompatActivity() {
    private lateinit var ui: ActivityWifiCountryBinding
    private var state: WifiCountryState? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityWifiCountryBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.toolbar.setNavigationOnClickListener { finish() }
        ui.code.filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(2))
        ui.code.doAfterTextChanged { ui.codeLayout.error = null; controls() }
        ui.choose.setOnClickListener { chooseCountry() }
        ui.refresh.setOnClickListener { refresh() }
        ui.apply.setOnClickListener {
            val country = WifiCountryProtocol.normalize(ui.code.text.toString())
            if (country == null) ui.codeLayout.error = getString(R.string.wifi_country_invalid)
            else change(country)
        }
        ui.automatic.setOnClickListener { change(null) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun controls() {
        val writable = !busy && state?.let { it.root && it.supported } == true
        ui.apply.isEnabled = writable && WifiCountryProtocol.normalize(ui.code.text.toString()) != null
        ui.automatic.isEnabled = writable
        ui.refresh.isEnabled = !busy
        ui.choose.isEnabled = !busy
        ui.code.isEnabled = !busy
    }

    private fun paint(value: WifiCountryState) {
        state = value
        ui.mode.text = when {
            !value.overrideKnown -> getString(R.string.wifi_country_mode_unknown)
            value.override == null -> getString(R.string.wifi_country_auto)
            else -> getString(R.string.wifi_country_manual, value.override)
        }
        ui.current.text = getString(R.string.wifi_country_current, value.country ?: "—")
        ui.driver.text = getString(R.string.wifi_country_driver, value.driver ?: "—")
        ui.access.text = when {
            !value.root -> getString(R.string.wifi_country_need_root)
            !value.supported -> getString(R.string.wifi_country_unsupported)
            else -> getString(R.string.wifi_country_ready)
        }
        // Keep the user's draft when returning from the picker or refreshing the state.
        if (ui.code.text.isNullOrBlank()) ui.code.setText(value.override ?: value.country.orEmpty())
        controls()
    }

    private fun refresh() {
        if (busy) return
        busy = true
        ui.result.text = ""
        controls()
        lifecycleScope.launch {
            try {
                paint(WifiCountry.read())
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                state = null
                ui.mode.setText(R.string.wifi_country_unavailable)
                ui.current.text = ""
                ui.driver.text = ""
                ui.access.text = getString(R.string.wifi_country_need_root)
                ui.result.text = e.message
            } finally {
                busy = false
                controls()
            }
        }
    }

    private fun change(country: String?) {
        if (busy) return
        busy = true
        controls()
        ui.result.setText(R.string.wifi_country_working)
        // The command/readback completes even if the Activity is recreated or closed.
        App.scope.launch {
            val result = runCatching { WifiCountry.change(country) }
            val latest = result.getOrNull() ?: runCatching { WifiCountry.read() }.getOrNull()
            val message = result.fold(onSuccess = { after ->
                when {
                    country == null -> getString(R.string.wifi_country_auto_done)
                    after.driver == country -> getString(R.string.wifi_country_done, country)
                    else -> getString(R.string.wifi_country_pending, country)
                }
            }, onFailure = { getString(R.string.wifi_country_failed, it.message) })
            Prefs.log("Wi-Fi: $message")
            withContext(Dispatchers.Main) {
                busy = false
                if (!isDestroyed) {
                    latest?.let(::paint)
                    ui.result.text = message
                    controls()
                }
            }
        }
    }

    private fun chooseCountry() {
        // The app's current strings are Russian even on an English-language phone.
        val locale = Locale.forLanguageTag("ru")
        val collator = Collator.getInstance(locale)
        val countries = listOf("00" to getString(R.string.wifi_country_world)) + Locale.getISOCountries()
            .map { it to "$it · ${Locale.Builder().setRegion(it).build().getDisplayCountry(locale)}" }
            .sortedWith { a, b -> collator.compare(a.second.substringAfter(" · "), b.second.substringAfter(" · ")) }
        var shown = countries
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = (24 * resources.displayMetrics.density).toInt()
        content.setPadding(pad, 0, pad, 0)
        val search = EditText(this).apply { hint = getString(R.string.wifi_country_search); setSingleLine() }
        val list = ListView(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { it.second }.toMutableList())
        list.adapter = adapter
        content.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val height = (resources.displayMetrics.heightPixels * 0.45).toInt()
        // A positive weight lets the list shrink when the keyboard reduces the dialog height.
        content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, 1f))
        search.doAfterTextChanged { query ->
            val text = query.toString().trim()
            shown = countries.filter { it.second.contains(text, ignoreCase = true) }
            adapter.clear()
            adapter.addAll(shown.map { it.second })
            adapter.notifyDataSetChanged()
        }
        fun hideKeyboard() {
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(search.windowToken, 0)
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.wifi_country_choose).setView(content)
            .setNegativeButton(R.string.cancel) { _, _ -> hideKeyboard() }.create()
        dialog.setOnCancelListener { hideKeyboard() }
        list.setOnItemClickListener { _, _, position, _ ->
            hideKeyboard()
            ui.code.setText(shown[position].first)
            dialog.dismiss()
            ui.code.clearFocus()
            ui.root.requestFocus()
        }
        dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}
