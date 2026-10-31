package com.netmode.switcher

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Adding to favorites asks at once which group the apps go to, when there are groups. */
object IceFavoriteDialog {
    fun add(ctx: Context, keys: List<String>, done: () -> Unit = {}) {
        val groups = Freezer.groups()
        if (groups.isEmpty()) {
            Freezer.setFavorite(keys, true)
            done()
            return
        }
        val current = keys.mapNotNull { Freezer.find(it) }.map { it.group }.distinct()
        // A selection from several groups may keep each app where it is.
        val keep = current.size > 1
        val choices: List<String?> = listOf<String?>(null) + groups
        val items = (if (keep) listOf(ctx.getString(R.string.ice_favorite_keep_group)) else emptyList()) +
            ctx.getString(R.string.ice_no_group) + groups
        val checked = if (keep) 0 else choices.indexOf(current.singleOrNull()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.ice_favorite_group_title)
            .setSingleChoiceItems(items.toTypedArray(), checked) { d, i ->
                d.dismiss()
                val index = if (keep) i - 1 else i
                if (index >= 0) {
                    val group = choices[index]
                    Freezer.setGroups(keys, group?.takeIf { it in Freezer.groups() })
                }
                Freezer.setFavorite(keys, true)
                done()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
