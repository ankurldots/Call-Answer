package com.callanswer.app.auth
/** Client-side checks that mirror the Cognito user pool password policy, so most mistakes show before a network call. */
object AccountValidation {
    const val MIN_PASSWORD_LENGTH = 10
    private val email = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
    fun isValidEmail(value: String): Boolean = email.matches(value.trim())
    /** At least [MIN_PASSWORD_LENGTH] characters with a lower-case letter, an upper-case letter and a digit. */
    fun isValidPassword(value: String): Boolean =
        value.length >= MIN_PASSWORD_LENGTH && value.any { it.isLowerCase() } &&
            value.any { it.isUpperCase() } && value.any { it.isDigit() }
}
