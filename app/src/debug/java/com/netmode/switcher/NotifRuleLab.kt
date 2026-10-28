package com.netmode.switcher

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** Debug-only editor fixture. Callbacks never change notification rules or channels. */
class NotifRuleLab : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = TextView(this)
        val open = MaterialButton(this).apply { setText(R.string.hide_title) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(open)
            addView(result)
        })
        fun show() {
            val remove: (() -> Unit)? = if (intent.getBooleanExtra("existing", false)) {
                { result.text = "Removed" }
            } else null
            NotifRuleEditor.show(this, HideRule(1, "android", "TetherNotification", null), remove) {
                result.text = "Saved: ${it.channel}; ${it.title}; ${it.ongoingOnly}; ${it.enabled}; ${it.exclusions}"
            }
        }
        open.setOnClickListener { show() }
        show()
    }
}
