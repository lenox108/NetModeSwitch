package com.netmode.switcher

/**
 * How a rule names an app: the package, plus "@user" for a copy in another Android user.
 *
 * OxygenOS app cloning ("Клонирование приложений") installs the second copy into a separate
 * user profile, so "org.telegram.messenger@999" is the clone and the bare name the original.
 * Every shell command then takes --user, which shell (Shizuku) and root can both use.
 */
object AppKey {

    /** Package names and user ids only ever reach the shell after this check. */
    private val RE = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+(@\\d{1,6})?$")

    fun valid(key: String): Boolean = RE.matches(key)

    fun pkg(key: String): String = key.substringBefore('@')

    fun user(key: String): Int = key.substringAfter('@', "0").toIntOrNull() ?: 0

    fun of(pkg: String, user: Int): String = if (user == 0) pkg else "$pkg@$user"

    fun isClone(key: String): Boolean = user(key) != 0

    /** "--user N pkg", the argument form am, pm and appops all accept. */
    fun shell(key: String): String = "--user ${user(key)} ${pkg(key)}"

    /** Matches the app's processes in its own user only — pidof would see the clone too. */
    fun aliveCheck(key: String): String {
        val name = pkg(key).replace(".", "\\\\.")
        return "ps -A -o USER,NAME | grep -qE \"^u${user(key)}_a[0-9]+ +$name\$\""
    }

    /**
     * Packages installed in the other users (clone profiles), as keys. One shell call; the
     * output lines are "@<user> package:<name>".
     */
    suspend fun cloneKeys(): List<String> {
        val out = Privileged.exec(
            "for u in \$(pm list users | sed -n 's/.*UserInfo{\\([0-9]*\\):.*/\\1/p'); do " +
                "[ \"\$u\" = 0 ] && continue; pm list packages --user \$u | sed \"s/^/@\$u /\"; done"
        )
        return out.lineSequence().mapNotNull { line ->
            val user = line.substringAfter('@').substringBefore(' ').toIntOrNull() ?: return@mapNotNull null
            val pkg = line.substringAfter("package:", "").trim().ifEmpty { return@mapNotNull null }
            of(pkg, user).takeIf { valid(it) }
        }.toList()
    }
}
