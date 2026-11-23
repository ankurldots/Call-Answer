package com.callanswer.agent.store

import com.callanswer.core.model.IntentCategory
import com.callanswer.core.tenant.E164
import com.callanswer.core.tenant.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RecentCallStoreTest : FunSpec({
    val alice = UserId("alice-1")
    val bob = UserId("bob-2")
    val num = E164("+919876543210")
    val other = E164("+919811111111")
    val min = 60_000L

    fun repos(): Triple<InMemoryItemStore, UserScopedRepository, UserScopedRepository> {
        val s = InMemoryItemStore(); val b = InMemoryBlobStore()
        return Triple(s, UserScopedRepository(alice, s, b), UserScopedRepository(bob, s, b))
    }

    test("latest call wins and an older end never overwrites") {
        val (_, a, _) = repos()
        a.recordEnded(num, 2_000_000, IntentCategory.DELIVERY, "c2") shouldBe true
        a.recordEnded(num, 1_000_000, IntentCategory.UNKNOWN, "c1") shouldBe false
        a.recordEnded(num, 2_000_000, IntentCategory.UNKNOWN, "c3") shouldBe false
        a.lastNonSolicitation(num, 2_000_000, 15)!!.callId shouldBe "c2"
        a.recordEnded(num, 3_000_000, IntentCategory.UNKNOWN, "c4") shouldBe true
        a.lastNonSolicitation(num, 3_000_000, 15)!!.intent shouldBe IntentCategory.UNKNOWN
    }

    test("solicitation and hidden numbers are ignored, and the record carries a 2 hour TTL") {
        val (s, a, _) = repos()
        a.recordEnded(num, 1_000_000, IntentCategory.SOLICITATION, "c1") shouldBe false
        a.recordEnded(null, 1_000_000, IntentCategory.DELIVERY, "c1") shouldBe false
        s.snapshot() shouldBe emptyList()
        a.recordEnded(num, 1_000_000, IntentCategory.DELIVERY, "c1")
        s.snapshot().single().num("ttl") shouldBe 1_000 + 7_200
    }

    test("the window is checked in code on endMs, both ends inclusive") {
        val (_, a, _) = repos()
        a.recordEnded(num, 1_000_000, IntentCategory.DELIVERY, null)
        a.lastNonSolicitation(num, 1_000_000 + 15 * min, 15) shouldBe
            com.callanswer.core.cloud.RecentCall(1_000_000, IntentCategory.DELIVERY)
        a.lastNonSolicitation(num, 1_000_000 + 15 * min + 1, 15) shouldBe null
        a.lastNonSolicitation(num, 999_999, 15) shouldBe null
        a.lastNonSolicitation(null, 1_000_000, 15) shouldBe null
    }

    test("Users are isolated and the list is newest first") {
        val (_, a, b) = repos()
        a.recordEnded(num, 1_000, IntentCategory.DELIVERY, "c1")
        a.recordEnded(other, 5_000, IntentCategory.UNKNOWN, "c2")
        b.lastNonSolicitation(num, 1_000, 15) shouldBe null
        b.listRecentCallers() shouldBe emptyList()
        a.listRecentCallers().map { it.first } shouldBe listOf(other, num)
    }
})
