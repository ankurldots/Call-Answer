package com.callanswer.core.tenant

object PhoneNumbers {
    private val separators = Regex("""[\s\-().]""")

    /**
     * Turns `+91 98xxx-xxxxx`, `098xxxxxxxx`, `9198xxxxxxxx`, and `98xxxxxxxx` (10 digits, India default)
     * into `+9198xxxxxxxx`. Returns null for text that is not a number. Idempotent.
     */
    fun normalize(raw: String): E164? {
        val s = raw.trim().replace(separators, "")
        if (s.isEmpty()) return null
        val plus = s.startsWith("+")
        val digits = if (plus) s.substring(1) else s
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        val full = when {
            plus -> digits
            digits.length == 10 -> "91$digits"
            digits.length == 11 && digits.startsWith("0") -> "91" + digits.substring(1)
            digits.length == 12 && digits.startsWith("91") -> digits
            digits.startsWith("00") -> digits.substring(2)
            else -> return null
        }
        if (full.length !in 8..15 || full.startsWith("0")) return null
        return E164("+$full")
    }
}
