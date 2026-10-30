package com.netmode.switcher

import org.json.JSONObject

/** One rule as text, to send to someone or keep; a whole backup stays with [Backup]. */
internal object RuleShare {

    private const val APP = "NetModeSwitch"

    fun encode(rule: AppRule): String =
        JSONObject().put("app", APP).put("rule", rule.toJson()).toString(2)

    /** Throws on anything that is not a shared rule of this app. */
    fun decode(text: String): AppRule {
        val o = JSONObject(text.trim())
        require(o.optString("app") == APP) { "это не правило NetModeSwitch" }
        return AppRule.fromJson(o.getJSONObject("rule"))
    }

    /** What a rule from someone else would do beyond this phone's switches, each spelled out. */
    fun risks(rule: AppRule): List<String> = listOfNotNull(
        rule.shell?.let { "Команда: $it" },
        rule.endShell?.let { "Команда в конце: $it" },
        rule.unlockEnd?.shell?.let { "Команда при разблокировке: $it" },
        rule.http?.let { "HTTP-запрос: $it" },
        rule.endHttp?.let { "HTTP-запрос в конце: $it" },
        rule.unlockEnd?.http?.let { "HTTP-запрос при разблокировке: $it" },
    )
}
