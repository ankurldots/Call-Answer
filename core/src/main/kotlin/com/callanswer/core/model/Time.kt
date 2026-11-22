package com.callanswer.core.model

import kotlinx.serialization.Serializable

/** Hour and minute of day (24 h). The time is truncated to the minute (Requirement 11.5). */
@Serializable
data class LocalTimeHm(val hour: Int, val minute: Int) : Comparable<LocalTimeHm> {
    init {
        require(hour in 0..23) { "hour must be 0..23" }
        require(minute in 0..59) { "minute must be 0..59" }
    }

    val minuteOfDay: Int get() = hour * 60 + minute

    override fun compareTo(other: LocalTimeHm): Int = minuteOfDay.compareTo(other.minuteOfDay)

    /** `HH:MM`. */
    override fun toString(): String = "%02d:%02d".format(hour, minute)

    companion object {
        private val HM = Regex("""^([01]\d|2[0-3]):([0-5]\d)$""")

        /** Parses a 24-hour `HH:MM` value, or returns null. */
        fun parse(s: String): LocalTimeHm? {
            val m = HM.matchEntire(s.trim()) ?: return null
            return LocalTimeHm(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
    }
}
