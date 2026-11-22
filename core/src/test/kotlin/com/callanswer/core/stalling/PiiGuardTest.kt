package com.callanswer.core.stalling

import com.callanswer.core.model.Language
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.checkAll
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.arbitrary

class PiiGuardTest : StringSpec({
    val guard = PiiGuard(setOf("Ankur Sharma", "Block C Press Enclave", "+91 98765 43210", "9810012345"))

    "matches protected words at token level, case-insensitively" {
        guard.containsProtected("this is ANKUR sharma speaking") shouldBe true
        guard.containsProtected("press enclave, block c") shouldBe false
        guard.containsProtected("ankur only") shouldBe false
        guard.containsProtected("Ankurs Sharmas") shouldBe false
    }

    "matches phone numbers regardless of formatting" {
        guard.containsProtected("call 98765-43210 now") shouldBe true
        guard.containsProtected("call 12 34 now") shouldBe false
        guard.containsProtected("981 001 2345") shouldBe true
    }

    "rejects sensitive patterns" {
        guard.containsProtected("write to me@example.com") shouldBe true
        guard.containsProtected("PAN ABCDE1234F") shouldBe true
        guard.containsProtected("ifsc SBIN0001234") shouldBe true
        guard.containsProtected("aadhaar 1234 5678 9012") shouldBe true
        guard.containsProtected("card 4111-1111-1111-1111") shouldBe true
        guard.containsProtected("pin 12345") shouldBe false
    }

    "sanitizePrompt removes protected values and patterns" {
        val out = guard.sanitizePrompt("I am Ankur Sharma, mail a@b.com, number 98765 43210 ok")
        guard.containsProtected(out) shouldBe false
        out.contains("ok") shouldBe true
    }

    "sanitizePrompt leaves clean text unchanged" {
        guard.sanitizePrompt("do you offer a home loan") shouldBe "do you offer a home loan"
    }

    "acceptReply requires exactly one question and expected language" {
        guard.acceptReply("We have a 2 bedroom flat. Which bank is the loan from?", Language.EN) shouldBe true
        guard.acceptReply("Which bank? And what rate?", Language.EN) shouldBe false
        guard.acceptReply("We have a flat.", Language.EN) shouldBe false
        guard.acceptReply("हमें लोन चाहिए। कौन सा बैंक है?", Language.HI) shouldBe true
        guard.acceptReply("Which bank is it?", Language.HI) shouldBe false
        guard.acceptReply("हमें लोन चाहिए। कौन सा बैंक है?", Language.EN) shouldBe false
        guard.acceptReply("Is this Ankur Sharma?", Language.EN) shouldBe false
        guard.acceptReply("   ") shouldBe false
    }

    "property: sanitizePrompt output never contains protected data" {
        val gen = arbitrary { rs ->
            val parts = listOf("Ankur Sharma", "98765 43210", "x@y.com", "hello", "loan", "9810012345", "ABCDE1234F")
            (1..5).joinToString(" ") { parts[rs.random.nextInt(parts.size)] }
        }
        checkAll(gen) { s -> guard.containsProtected(guard.sanitizePrompt(s)) shouldBe false }
    }

    "property: text without digits, '@' or protected words is never flagged" {
        val clean = Arb.string(0..40).filter { s -> s.none { it.isDigit() || it == '@' } && !s.lowercase().contains("ankur") }
        checkAll(clean) { s -> guard.containsProtected(s) shouldBe false }
    }
})
