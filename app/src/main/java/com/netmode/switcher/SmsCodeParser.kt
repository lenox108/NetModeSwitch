package com.netmode.switcher

/**
 * Finds a one-time code in a message: "Код: 482910", "Your code is 1234", "Пароль 12-34-56".
 * Pure text work, no Android calls, so it is covered by unit tests.
 *
 * A message counts only when it says what it is ("код", "пароль", "code"…); a bank message
 * about a payment has digits too, but no code. Of the candidates, the one closest to such a
 * word wins. Amounts, card tails, dates, times, phone numbers and order numbers are skipped.
 */
object SmsCodeParser {

    /**
     * Words that say a message carries a code, as regex pieces. Russian ones take any ending
     * ("кода", "пароля", "пинкод"); English ones are whole words, so "PINGVINA" is not a PIN.
     */
    private val KEYWORDS = listOf(
        "код", "пароль", "пароля", "парол", "пин(?=[-\\s:]|код|\\b|$)",
        "codes?\\b", "passcode\\b", "password\\b", "otp\\b", "pin\\b", "kod\\b",
        "verification\\b", "parol\\b",
    )

    private val KEYWORD = Regex(
        "(?<![\\p{L}\\d])(?:" + KEYWORDS.joinToString("|") + ")",
        RegexOption.IGNORE_CASE,
    )

    /** Advertising: a promo code is not something to put in the clipboard. */
    private val PROMO = Regex("промокод|promo\\s?code|promocode|по\\s+коду|купон|coupon", RegexOption.IGNORE_CASE)

    /**
     * A candidate: 4–8 digits, or 3+3 / 2+2+2 groups split by a space or a dash, or a short
     * mix of capital letters and digits. Not part of a longer word or number.
     */
    private val CANDIDATE = Regex(
        "(?<![\\p{L}\\d_])(" +
            "\\d{3}[- ]\\d{3}(?![- ]?\\d)" +
            "|\\d{2}-\\d{2}-\\d{2}(?!-?\\d)" +
            "|\\d{4,8}" +
            "|(?=[A-Z0-9]*\\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{4,8}" +
            ")(?![\\p{L}\\d])",
    )

    /** After the number: money, sizes, percent, units — not a code. */
    private val REJECT_AFTER = Regex(
        "^\\s?(?:[.,]\\d|руб|р\\.|р(?!\\p{L})|₽|rub|usd|eur|\\$|€|%|мб|гб|mb|gb|шт|км|kg|кг|бонус|балл|мин|сек|год|г\\.)",
        RegexOption.IGNORE_CASE,
    )

    /** Just before the number: card tail, number sign, amount sign, a decimal or a date. */
    private val REJECT_BEFORE = Regex(
        "(?:[*•#№+$€₽:/.,_]|[*•]\\s+|\\p{L}{2,}-|(?<![\\p{L}])[NnНн]|\\d[.,]|карт\\p{L}*\\s?|сч[её]т\\p{L}*\\s?|(?:заказ|zakaz)\\p{L}*\\s?|" +
            "card\\s?|order\\s?|tel\\.?\\s?|тел\\.?\\s?|ending\\s?(?:in\\s?)?)\\z",
        RegexOption.IGNORE_CASE,
    )

    /** "Код" with these after it is a phrase about something else: "код региона"… */
    private val WEAK_AFTER_KEYWORD = Regex("^\\s*(?:региона|страны|города|ошибки|товара|клиента)", RegexOption.IGNORE_CASE)

    /** The longest gap between the word and the code that still ties them together. */
    private const val MAX_DISTANCE = 120

    fun find(text: String?): String? {
        if (text.isNullOrBlank()) return null
        if (PROMO.containsMatchIn(text)) return null
        val body = text.replace(' ', ' ').replace(' ', ' ')
        val keywords = KEYWORD.findAll(body)
            .filterNot { WEAK_AFTER_KEYWORD.containsMatchIn(body.substring(it.range.last + 1)) }
            .map { it.range }.toList()
        if (keywords.isEmpty()) return null

        var best: String? = null
        var bestScore = Int.MAX_VALUE
        for (m in CANDIDATE.findAll(body)) {
            val raw = m.value
            val before = body.substring(maxOf(0, m.range.first - 12), m.range.first)
            val after = body.substring(m.range.last + 1, minOf(body.length, m.range.last + 8))
            if (REJECT_BEFORE.containsMatchIn(before) && !endsWithKeywordColon(before)) continue
            if (REJECT_AFTER.containsMatchIn(after)) continue
            if (raw.length == 4 && raw.all { it.isDigit() } && looksLikeYear(raw, before)) continue
            if (keywords.any { it.first <= m.range.last && m.range.first <= it.last }) continue
            val score = keywords.minOf { k -> distance(k, m.range) }
            if (score > MAX_DISTANCE) continue
            // Letter-and-digit codes are rarer and riskier: they lose a tie to a plain number.
            val weighted = score + if (raw.any { it.isLetter() }) 15 else 0
            if (weighted < bestScore) {
                bestScore = weighted
                best = raw
            }
        }
        return best?.filter { it.isLetterOrDigit() }
    }

    /** "Код: 1234" — the colon is the keyword's own, not a time or a ratio. */
    private fun endsWithKeywordColon(before: String): Boolean =
        KEYWORD_COLON.containsMatchIn(before)

    private val KEYWORD_COLON = Regex("(?:код|парол|pin|code|kod|parol)\\p{L}*\\s?:\\s?\\z", RegexOption.IGNORE_CASE)

    /** A code after the word is far more usual than before it, so that way costs less. */
    private fun distance(keyword: IntRange, code: IntRange): Int =
        if (code.first > keyword.last) code.first - keyword.last
        else (keyword.first - code.last) * 2

    private fun looksLikeYear(raw: String, before: String): Boolean {
        val n = raw.toInt()
        return n in 1990..2099 && Regex("(?:до|от|с|в|by|until|\\d{2}[./])\\s?\\z", RegexOption.IGNORE_CASE).containsMatchIn(before)
    }
}
