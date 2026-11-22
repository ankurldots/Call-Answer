package com.callanswer.core.cloud

/**
 * GSM conditional call-forwarding codes for a validated Virtual_Number (E.164, with the leading `+`).
 * The carrier-specific behavior is settled by spikes C-4 and C-7.
 */
object ForwardingCodes {
    private val E164 = Regex("""\+[1-9][0-9]{7,14}""")

    /** Sets forwarding when unanswered (with [ringSeconds]), busy, and unreachable. Null for a bad number. */
    fun set(virtualNumber: String, ringSeconds: Int = 20): List<String>? {
        if (!E164.matches(virtualNumber)) return null
        return listOf(
            "**61*$virtualNumber**$ringSeconds#",
            "**67*$virtualNumber#",
            "**62*$virtualNumber#",
        )
    }

    /** Clears all conditional forwarding. */
    val clear: String = "##002#"

    /** Unconditional forwarding to the number. Null for a bad number. */
    fun unconditional(virtualNumber: String): String? =
        if (E164.matches(virtualNumber)) "**21*$virtualNumber#" else null

    /** Turns off one kind of conditional forwarding: no reply, busy, unreachable. */
    val clearNoReply: String = "##61#"
    val clearBusy: String = "##67#"
    val clearUnreachable: String = "##62#"

    /** Clears unconditional forwarding. */
    val clearUnconditional: String = "##21#"

    /** The three single clear codes followed by the all-conditional clear [clear]. */
    val turnOff: List<String> = listOf(clearNoReply, clearBusy, clearUnreachable, clear)

    /** Ring time choices (seconds) the carrier accepts for the no-reply code. */
    val ringSecondsChoices: List<Int> = listOf(10, 15, 20, 25, 30)

    /** `tel:` URI for the dialer with `#` written as `%23` (a raw `#` would start a URI fragment). */
    fun dialerUri(code: String): String = "tel:" + code.replace("#", "%23")
}
