package com.netmode.switcher

import android.content.Context

/** Reads cached observations only. Rendering a countdown never starts a shell command. */
object IceStatus {
    fun text(ctx: Context, a: IceApp, now: Long = System.currentTimeMillis()): String {
        fun remaining(at: Long) = Steps.words((((at - now).coerceAtLeast(0L) + 59_999L) / 60_000L)
            .coerceIn(1L, 24L * 60L).toInt() * 60)
        val parts = mutableListOf<String>()
        if (a.frozen) parts += ctx.getString(if (a.frozenAs == IceMode.DEEP) R.string.ice_st_deep else R.string.ice_st_soft)
        else {
            if (a.keepUntil == -1L) parts += ctx.getString(R.string.ice_st_manual_hold)
            else if (a.keepUntil > now) parts += ctx.getString(R.string.ice_st_timed_hold, remaining(a.keepUntil))
            if (a.heldBy != 0L) {
                val rule = Prefs.appRules.find { it.id == a.heldBy }
                parts += ctx.getString(R.string.ice_st_held, rule?.let { RuleTiles.label(it) } ?: "?")
            }
            if (parts.isEmpty()) {
                val reason = Freezer.waitReason(a.key)
                val deadline = Freezer.deadline(a)
                parts += when {
                    reason == Freezer.WaitReason.CALL -> ctx.getString(R.string.ice_st_call)
                    reason == Freezer.WaitReason.BUSY -> ctx.getString(R.string.ice_st_busy)
                    Freezer.isOnScreen(a.key) || reason == Freezer.WaitReason.ON_SCREEN -> ctx.getString(R.string.ice_st_open)
                    deadline == null -> ctx.getString(R.string.ice_st_never)
                    deadline <= now -> ctx.getString(R.string.ice_st_due)
                    else -> ctx.getString(R.string.ice_st_countdown, remaining(deadline))
                }
            }
        }
        when (Freezer.waitReason(a.key)) {
            Freezer.WaitReason.NO_ACCESS -> parts += ctx.getString(R.string.ice_no_access)
            Freezer.WaitReason.FAILED -> parts += ctx.getString(R.string.ice_st_failed)
            else -> Unit
        }
        return parts.joinToString(" · ")
    }
}
