package com.callanswer.app.a11y

/**
 * Pure edits of `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` (a `:`-separated list of component names).
 * Other services in the list are never touched. Entries may be written in short form (`pkg/.Class`) or flattened
 * (`pkg/pkg.Class`), and the system may change their case, so entries are compared normalised.
 */
object A11yEnabledList {
    fun parse(raw: String?): List<String> = (raw ?: "").split(':').map { it.trim() }.filter { it.isNotEmpty() }

    /** `pkg/.Class` -> `pkg/pkg.class`, lower case, for comparison only. */
    fun normalize(entry: String): String {
        val slash = entry.indexOf('/')
        if (slash < 0) return entry.lowercase()
        val pkg = entry.substring(0, slash)
        var cls = entry.substring(slash + 1)
        if (cls.startsWith(".")) cls = pkg + cls
        return "$pkg/$cls".lowercase()
    }

    fun contains(entries: List<String>, ours: String): Boolean {
        val n = normalize(ours)
        return entries.any { normalize(it) == n }
    }

    /** Appends [ours] unless present; keeps every other entry and its order. Returns the new list. */
    fun add(entries: List<String>, ours: String): List<String> =
        if (contains(entries, ours)) entries else entries + ours

    /** Removes every entry equal to [ours] (also duplicates); keeps all others. */
    fun remove(entries: List<String>, ours: String): List<String> {
        val n = normalize(ours)
        return entries.filterNot { normalize(it) == n }
    }

    fun join(entries: List<String>): String = entries.joinToString(":")

    /** `ACCESSIBILITY_ENABLED` after a removal: 0 only when no service is left. */
    fun accessibilityEnabledAfterRemoval(remaining: List<String>): Int = if (remaining.isEmpty()) 0 else 1
}

/** Decisions about when our accessibility service must be switched off. */
object A11yPolicy {
    /**
     * Startup (app start or boot): leave the service on only while a call or one of our sessions is running.
     * [callActive] is null when the call state cannot be read; that counts as "no call" so the service is
     * switched off (fail safe).
     */
    fun disableAtStartup(enabled: Boolean, callActive: Boolean?, sessionRunning: Boolean): Boolean =
        enabled && !(callActive == true || sessionRunning)

    /** At call end, on any error path and in onDestroy the service is always switched off. */
    @Suppress("UNUSED_PARAMETER")
    fun disableAtCallEnd(enabled: Boolean): Boolean = true
}
