package com.callanswer.core.stalling

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll

class StreamingPiiGuardTest : StringSpec({
    val guard = PiiGuard(setOf("Ankur Sharma", "+91 98765 43210"))

    fun split(text: String, cuts: List<Int>): List<String> {
        val points = (cuts.map { it % (text.length + 1) } + 0 + text.length).distinct().sorted()
        return points.zipWithNext { a, b -> text.substring(a, b) }
    }

    /** **Validates: Requirements 12.8, 12.9, 12.13** */
    "reports a term no later than the chunk that completes it, for any split" {
        val texts = listOf("hello this is ANKUR sharma ok", "call +91 98765-43210 now", "reach +91 98765 43210")
        checkAll(Arb.element(texts), Arb.list(Arb.int(0..100), 0..6)) { text, cuts ->
            val chunks = split(text, cuts)
            val s = StreamingPiiGuard(guard)
            var sent = ""
            var firstHit = -1
            chunks.forEachIndexed { i, c ->
                sent += c
                if (s.accept(c) && firstHit < 0) firstHit = i
                if (guard.containsProtected(sent) && firstHit < 0) error("missed at chunk $i")
            }
            (firstHit >= 0) shouldBe true
        }
    }

    "no match for clean text under any split" {
        checkAll(Arb.list(Arb.int(0..100), 0..6)) { cuts ->
            val s = StreamingPiiGuard(guard)
            split("I can take a message for him, please call again", cuts).any { s.accept(it) } shouldBe false
        }
    }

    "reports a term split inside a word and across digits, and reset clears state" {
        val s = StreamingPiiGuard(guard)
        s.accept("my name is Ank") shouldBe false
        s.accept("ur Shar") shouldBe false
        s.accept("ma") shouldBe true
        s.reset()
        s.accept("+91 98") shouldBe false
        s.accept(" 765") shouldBe true
    }
})
