package com.callanswer.app.auth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class AccountValidationTest {
    @Test fun emails() {
        assertTrue(AccountValidation.isValidEmail(" a@b.com "))
        assertFalse(AccountValidation.isValidEmail("a@b"))
        assertFalse(AccountValidation.isValidEmail("a b@c.com"))
    }
    @Test fun passwords() {
        assertTrue(AccountValidation.isValidPassword("Abcdefghi1"))
        assertFalse(AccountValidation.isValidPassword("Abcdefgh1"))   // 9 characters
        assertFalse(AccountValidation.isValidPassword("abcdefghij1")) // no upper case
        assertFalse(AccountValidation.isValidPassword("ABCDEFGHIJ1")) // no lower case
        assertFalse(AccountValidation.isValidPassword("Abcdefghijk")) // no digit
    }
}
