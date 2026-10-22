package com.netmode.switcher

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

/**
 * "И ещё если": states a rule needs besides its trigger. The rule works only while every one of
 * them holds — it waits when its trigger comes without them, starts when the last of them comes
 * while the trigger still holds, and ends when one of them goes (see [AppFreezer.onExtras]).
 *
 * Each is a state that can be told at any moment; [trigger] says which watcher reports changes
 * of it (TIME is the shared alarm).
 */
enum class ExtraKind(val trigger: Trigger) {
    TIME(Trigger.TIME),
    WIFI(Trigger.WIFI),
    CHARGING(Trigger.CHARGING),
    BATTERY(Trigger.BATTERY),
    HEADPHONES(Trigger.HEADPHONES),
    ROAMING(Trigger.ROAMING),
    DND(Trigger.DND),
    LOCKED(Trigger.LOCKED),
}

/**
 * One extra condition. [negate] turns it round ("не на зарядке", "не в сети «Дом»"); [level] is
 * the battery %; [from] and [to] are minutes of the day, a window that may run past midnight.
 */
data class Extra(
    val kind: ExtraKind,
    val negate: Boolean = false,
    val level: Int = 0,
    val ssid: String? = null,
    val from: Int = 0,
    val to: Int = 0,
) {
    val label: String
        get() = when (kind) {
            ExtraKind.TIME -> "с %02d:%02d до %02d:%02d".format(from / 60, from % 60, to / 60, to % 60)
            ExtraKind.WIFI -> (if (negate) "не в сети «" else "в сети «") + ssid + "»"
            ExtraKind.CHARGING -> if (negate) "не на зарядке" else "на зарядке"
            ExtraKind.BATTERY -> "заряд ≤ $level%"
            ExtraKind.HEADPHONES -> if (negate) "без наушников" else "наушники подключены"
            ExtraKind.ROAMING -> if (negate) "не в роуминге" else "в роуминге"
            ExtraKind.DND -> if (negate) "«Не беспокоить» выкл" else "«Не беспокоить» вкл"
            ExtraKind.LOCKED -> if (negate) "телефон разблокирован" else "телефон заблокирован"
        }

    /** Whether a time window holds at [now]; one that ends where it starts is the whole day. */
    fun timeHolds(now: LocalTime): Boolean {
        val m = now.hour * 60 + now.minute
        return when {
            from == to -> true
            from < to -> m in from until to
            else -> m >= from || m < to
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("k", kind.name).put("neg", negate).put("lvl", level)
        .put("ssid", ssid ?: JSONObject.NULL).put("from", from).put("to", to)

    companion object {
        fun fromJson(o: JSONObject): Extra? = runCatching {
            Extra(
                kind = ExtraKind.valueOf(o.getString("k")),
                negate = o.optBoolean("neg", false),
                level = o.optInt("lvl", 0),
                ssid = if (o.isNull("ssid")) null else o.optString("ssid").takeIf { it.isNotEmpty() },
                from = o.optInt("from", 0),
                to = o.optInt("to", 0),
            )
        }.getOrNull()

        fun listFromJson(a: JSONArray?): List<Extra> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fromJson) }
    }
}

object ExtraGate {

    enum class Step { NONE, START, END }

    /**
     * What a change of the extra conditions means for one rule. [open]: its window is open (or,
     * for a rule with no end, it has run since they last held). [primary]: its trigger holds,
     * null when that cannot be told. [extras]: all of them hold, null when one cannot be told.
     */
    fun step(open: Boolean, primary: Boolean?, extras: Boolean?): Step = when {
        extras == null -> Step.NONE
        !extras -> if (open) Step.END else Step.NONE
        open -> Step.NONE
        primary == true -> Step.START
        else -> Step.NONE
    }
}
