package com.callanswer.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SmokeTest : StringSpec({
    "core module test infrastructure runs" {
        (1 + 1) shouldBe 2
    }
})
