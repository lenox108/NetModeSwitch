package com.netmode.switcher

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** The same single-app actions in the manager and the folder opened from the shade. */
object IceAppMenu {
    fun show(
        activity: AppCompatActivity,
        key: String,
        open: () -> Unit,
        freeze: (Boolean) -> Unit,
        thawFor: () -> Unit,
        card: () -> Unit,
        remove: () -> Unit,
        select: (() -> Unit)? = null,
    ) {
        val a = Freezer.find(key) ?: return
        val keys = listOf(key)
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        fun action(title: Int, run: () -> Unit) { actions += title to run }
        action(R.string.ice_open, open)
        action(if (a.frozen) R.string.ice_thaw else R.string.ice_freeze) { freeze(!a.frozen) }
        action(R.string.ice_thaw_for, thawFor)
        if (!a.frozen && a.keepUntil != 0L) {
            action(R.string.ice_resume_auto) { App.scope.launch { Freezer.resumeAuto(keys) } }
        }
        action(if (a.favorite) R.string.ice_favorite_remove else R.string.ice_favorite_add) {
            if (a.favorite) Freezer.setFavorite(keys, false) else IceFavoriteDialog.add(activity, keys)
        }
        if (!IceShortcuts.isPinned(activity, key)) {
            action(R.string.ice_pin) {
                if (!IceShortcuts.pin(activity, key)) Toast.makeText(activity, R.string.ice_pin_failed, Toast.LENGTH_SHORT).show()
            }
        }
        action(R.string.ice_card, card)
        select?.let { action(R.string.ice_select, it) }
        action(R.string.ice_remove, remove)
        MaterialAlertDialogBuilder(activity)
            .setTitle(Freezer.label(key))
            .setItems(actions.map { activity.getString(it.first) }.toTypedArray()) { _, index ->
                if (Freezer.find(key) != null) actions[index].second()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
