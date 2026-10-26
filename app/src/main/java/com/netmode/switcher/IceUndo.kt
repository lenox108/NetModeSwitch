package com.netmode.switcher

import org.json.JSONArray
import org.json.JSONObject

data class IceUndoEntry(val before: IceApp, val after: IceApp) {
    fun eligible(current: IceApp, at: Long, lastSeen: Long): Boolean =
        current.key == after.key && current.revision == after.revision &&
            IcePolicy.sameState(current, after) && lastSeen <= at
}

/** Only confirmed changes are recorded. This is local runtime state, never part of a backup. */
data class IceUndo(val at: Long, val entries: List<IceUndoEntry>) {
    fun usable(now: Long) = now >= at && now - at <= 10 * 60_000L

    fun toJson(): JSONObject = JSONObject().put("at", at).put("entries", JSONArray(entries.map {
        JSONObject().put("before", it.before.toJson()).put("after", it.after.toJson())
    }))

    companion object {
        fun fromJson(o: JSONObject): IceUndo {
            val list = o.getJSONArray("entries")
            return IceUndo(o.getLong("at"), (0 until list.length()).map {
                val e = list.getJSONObject(it)
                IceUndoEntry(IceApp.fromJson(e.getJSONObject("before")), IceApp.fromJson(e.getJSONObject("after")))
            })
        }
    }
}
