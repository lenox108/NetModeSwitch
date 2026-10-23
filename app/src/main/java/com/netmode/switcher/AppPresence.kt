package com.netmode.switcher

/**
 * Whether an "app on screen" rule still holds. Leaving is any other app coming to the front —
 * but not everything that comes to the front is leaving: the share sheet, a permission dialog,
 * a file picker or a link opened in a browser tab sit on top of the app for a moment and go.
 * Without telling them apart a rule that switches the VPN would drop it and bring it back
 * every time.
 */
object AppPresence {

    /** What one look at the screen saw, as far as one rule is concerned. */
    enum class Seen {
        /** One of the rule's apps. */
        APP,

        /** Something on top of the app for a moment: a system dialog, a browser tab, an app the rule lets through. */
        PASS,

        /** Another app, the launcher included. */
        OTHER,
        SCREEN_OFF,

        /** Nothing read yet since the screen came on. */
        UNKNOWN,
    }

    /** [holds] as for AppFreezer (null: cannot be told, keep as is), and since when the app is away. */
    data class Look(val holds: Boolean?, val awaySince: Long?)

    /**
     * System screens that pop up over an app on its behalf: the chooser and the resolver
     * ("android" itself), permission and install dialogs, the file picker, Google sign-in and
     * payment sheets.
     */
    private val SYSTEM = setOf(
        "android", "com.android.intentresolver", "com.android.systemui",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.documentsui", "com.google.android.documentsui",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.google.android.gms",
    )

    /** A link opened in an app's own browser tab: Chrome-based browsers, Firefox. */
    private val BROWSER_TAB = Regex("CustomTab|ExternalAppBrowser", RegexOption.IGNORE_CASE)

    /** Whether the activity [cls] of [pkg] is one of those that only sit on top of an app. */
    fun passes(pkg: String, cls: String?): Boolean =
        pkg in SYSTEM || (cls != null && BROWSER_TAB.containsMatchIn(cls))

    /**
     * Apps still on screen behind the one in front: a video in a picture-in-picture window, the
     * other half of a split screen. Their activity is paused, not stopped — pressing Home stops
     * it, going to a small window only pauses it — so from activity events: resumed and paused
     * count as on screen until stopped or destroyed.
     */
    class Behind {
        /** "pkg/cls" of each activity resumed or paused and not stopped since. */
        private val shown = LinkedHashMap<String, String>()

        /** An activity event: [visible] for resumed or paused, false for stopped or destroyed. */
        fun onEvent(pkg: String, cls: String?, visible: Boolean) {
            val key = "$pkg/$cls"
            if (visible) shown[key] = pkg else shown.remove(key)
        }

        fun clear() = shown.clear()

        /** Packages on screen apart from [front]. */
        fun packages(front: String?): Set<String> = shown.values.filterTo(HashSet()) { it != front }
    }

    /**
     * One rule, one look. [open]: the rule's window is open. [awaySince]: when its app was last
     * seen leaving, while the delay runs. Leaving starts the delay; a pass-through screen or,
     * with [screenOffStays], the screen going off keep the rule as it is — unless the app has
     * already left, then the delay simply runs on.
     */
    fun look(seen: Seen, open: Boolean, awaySince: Long?, now: Long, delayMs: Long, screenOffStays: Boolean): Look = when {
        seen == Seen.APP -> Look(true, null)
        !open -> Look(false, null)
        seen == Seen.UNKNOWN -> Look(null, awaySince)
        awaySince == null && (seen == Seen.PASS || (seen == Seen.SCREEN_OFF && screenOffStays)) -> Look(true, null)
        else -> {
            val since = awaySince ?: now
            if (now - since < delayMs) Look(true, since) else Look(false, null)
        }
    }
}
