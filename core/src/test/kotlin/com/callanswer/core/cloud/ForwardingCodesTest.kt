package com.callanswer.core.cloud

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ForwardingCodesTest : FunSpec({
    val n = "+918012345678"

    test("set codes use the number and ring time") {
        ForwardingCodes.set(n) shouldBe listOf("**61*$n**20#", "**67*$n#", "**62*$n#")
        ForwardingCodes.set(n, 30)!![0] shouldBe "**61*$n**30#"
    }
    test("a bad number gives no codes") {
        ForwardingCodes.set("12") shouldBe null
        ForwardingCodes.unconditional("abc") shouldBe null
    }
    test("turn-off codes") {
        ForwardingCodes.turnOff shouldBe listOf("##61#", "##67#", "##62#", "##002#")
    }
    test("dialer URI encodes the hash") {
        ForwardingCodes.dialerUri("**67*$n#") shouldBe "tel:**67*$n%23"
    }
})
