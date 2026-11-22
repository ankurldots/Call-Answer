package com.callanswer.core.tenant

/** Cognito `sub` of a User. Never blank and never contains `/` or `#`, so keys built from it cannot cross Users. */
@JvmInline
value class UserId(val sub: String) {
    init {
        require(sub.isNotBlank()) { "UserId must not be blank" }
        require(sub.none { it == '/' || it == '#' || it.isWhitespace() }) { "UserId contains a forbidden character" }
    }

    override fun toString(): String = sub
}

/** A phone number in E.164 form: `+` and 8 to 15 digits, first digit not 0. */
@JvmInline
value class E164(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Not an E.164 number" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("""\+[1-9][0-9]{7,14}""")
    }
}
