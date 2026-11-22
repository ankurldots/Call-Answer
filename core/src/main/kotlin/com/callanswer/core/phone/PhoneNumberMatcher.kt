package com.callanswer.core.phone

object PhoneNumberMatcher {
    private const val KEY_LENGTH = 10

    /** Strips every non-digit character and keeps the last 10 digits when longer than 10. */
    fun matchKey(raw: String): String {
        val digits = raw.filter { it in '0'..'9' }
        return if (digits.length > KEY_LENGTH) digits.takeLast(KEY_LENGTH) else digits
    }
}
