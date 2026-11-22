package com.callanswer.core.tenant

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class TenantTest : FunSpec({
    test("normalize handles the supported forms") {
        val expected = E164("+919812345678")
        listOf("+91 98123-45678", "09812345678", "919812345678", "9812345678", " 98123 45678 ", "+919812345678")
            .forEach { PhoneNumbers.normalize(it) shouldBe expected }
        PhoneNumbers.normalize("hello") shouldBe null
        PhoneNumbers.normalize("") shouldBe null
        PhoneNumbers.normalize("12345") shouldBe null
    }

    test("normalize is idempotent") {
        checkAll(Arb.string(0..16)) { s ->
            val n = PhoneNumbers.normalize(s)
            if (n != null) PhoneNumbers.normalize(n.value) shouldBe n
        }
    }

    test("keys are built from the user id") {
        val u = UserId("abc-123")
        Keys.user(u) shouldBe "USER#abc-123"
        Keys.number(E164("+919812345678")) shouldBe "NUMBER#+919812345678"
        Keys.call("c1") shouldBe "CALL#c1"
        Keys.device("d1") shouldBe "DEVICE#d1"
        Keys.recording(u, "c1") shouldBe "users/abc-123/recordings/c1.wav"
        runCatching { Keys.recording(u, "../x") }.isFailure shouldBe true
    }

    test("threeWay merges per field") {
        fun o(vararg p: Pair<String, String>) = JsonObject(p.associate { it.first to JsonPrimitive(it.second) })
        val r = SettingsMerge.threeWay(
            base = o("a" to "1", "b" to "1", "c" to "1"),
            server = o("a" to "1", "b" to "2", "c" to "2"),
            edits = o("a" to "9", "b" to "9", "c" to "2"),
        )
        r.merged["a"] shouldBe JsonPrimitive("9")
        r.merged["b"] shouldBe JsonPrimitive("2")
        r.merged["c"] shouldBe JsonPrimitive("2")
        r.conflicts shouldBe listOf("b")
    }
})
