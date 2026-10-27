package com.netmode.switcher

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import androidx.core.view.ViewCompat
import com.netmode.switcher.databinding.ActivityHotspotBinding

/** Presentation only: section disclosure and the two independent launch buttons. */
internal class HotspotScreen(private val ui: ActivityHotspotBinding) {
    private val sections = listOf(
        "stats" to (ui.statsToggle to ui.statsDetails),
        "identity" to (ui.identityToggle to ui.identityDetails),
        "advanced" to (ui.advancedToggle to ui.advancedDetails),
    )

    fun restore(state: Bundle?) {
        sections.forEach { (key, pair) ->
            val (button, content) = pair
            fun paint(open: Boolean) {
                content.visibility = if (open) View.VISIBLE else View.GONE
                button.setIconResource(if (open) R.drawable.ic_hotspot_collapse else R.drawable.ic_expand)
                ViewCompat.setStateDescription(button, if (open) "Развёрнуто" else "Свёрнуто")
            }
            paint(state?.getBoolean("hotspot_section_$key") ?: false)
            button.setOnClickListener { paint(content.visibility != View.VISIBLE) }
        }
    }

    fun save(out: Bundle) {
        sections.forEach { (key, pair) -> out.putBoolean("hotspot_section_$key", pair.second.visibility == View.VISIBLE) }
    }

    companion object {
        fun quick(ui: ActivityHotspotBinding, active: TetherMode?) {
            ui.stopHotspot.visibility = if (active == null) View.GONE else View.VISIBLE
            for ((mode, button) in listOf(TetherMode.VPN to ui.startVpn, TetherMode.PROXY to ui.startProxy)) {
                val on = active == mode
                val title = TetherRoute.label(mode)
                val action = if (on) "Выключить" else "Включить"
                button.setToggleCheckedStateOnClick(false)
                val enabled = button.isEnabled
                button.isEnabled = true
                button.isChecked = on
                button.isEnabled = enabled
                button.text = SpannableString("$title\n$action").apply {
                    setSpan(RelativeSizeSpan(0.78f), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                button.contentDescription = "$action раздачу через $title"
                ViewCompat.setStateDescription(button, if (on) "Раздача включена" else "Раздача выключена")
            }
        }
    }
}
