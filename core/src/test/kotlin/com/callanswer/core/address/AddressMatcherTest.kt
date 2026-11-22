package com.callanswer.core.address

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AddressMatcherTest : FunSpec({
    val home = "Flat 4, Green Park Apartments, Press Enclave Road"

    test("matches a partial but consistent statement") {
        AddressMatcher.matches(home, "Sir address is flat 4 green park") shouldBe true
        AddressMatcher.matches(home, "address is Green Park Apartments") shouldBe true
    }
    test("rejects another address") {
        AddressMatcher.matches(home, "Sir address is B 12 Lotus Heights Sector 9") shouldBe false
    }
    test("rejects a single shared word out of several") {
        AddressMatcher.matches(home, "address is Green Meadows Society") shouldBe false
    }
    test("blank input never matches") {
        AddressMatcher.matches(null, "address is flat 4") shouldBe false
        AddressMatcher.matches(home, "sir address is") shouldBe false
    }
})

class HindiAddressTest : FunSpec({
    val home = "Flat 4, Green Park Apartments"
    val homeHi = "फ्लैट ४, ग्रीन पार्क अपार्टमेंट्स"

    test("a Hindi statement matches the Hindi address") {
        AddressMatcher.matches(null, "सर एड्रेस है फ्लैट 4 ग्रीन पार्क", homeHi) shouldBe true
    }
    test("a Hindi statement matches the English address by sound") {
        AddressMatcher.matches(home, "सर एड्रेस है फ्लैट 4 ग्रीन पार्क") shouldBe true
    }
    test("a Hindi statement of another address does not match") {
        AddressMatcher.matches(home, "एड्रेस है बी 12 लोटस हाइट्स सेक्टर 9", homeHi) shouldBe false
    }
})
