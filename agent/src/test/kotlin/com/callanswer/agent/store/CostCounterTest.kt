package com.callanswer.agent.store

import com.callanswer.core.model.CostRates
import com.callanswer.core.tenant.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CostCounterTest : FunSpec({
    val rates = CostRates(exotelPerMinutePaise = 60, sonicInputPerMinutePaise = 0, sonicOutputPerMinutePaise = 0)
    val now = 1_750_000_000_000L // June 2025

    class Recorder : CostNotifier {
        var n80 = 0; var n100 = 0
        override suspend fun limit80Reached(totalPaise: Long) { n80++ }
        override suspend fun limit100Reached(totalPaise: Long) { n100++ }
    }

    test("80 percent notifies once, 100 percent notifies once and pauses, per user") {
        val store = InMemoryItemStore(); val blobs = InMemoryBlobStore()
        val na = Recorder(); val nb = Recorder()
        val a = CostCounter(UserScopedRepository(UserId("alice-1"), store, blobs), na)
        val b = CostCounter(UserScopedRepository(UserId("bob-2"), store, blobs), nb)
        // Limit 1 INR = 100 paise; each 30 s call costs 30 paise.
        a.recordCall(rates, 30, 0, 0, 1, now).notified80 shouldBe false
        a.recordCall(rates, 30, 0, 0, 1, now).monthTotalPaise shouldBe 60
        a.recordCall(rates, 30, 0, 0, 1, now).notified80 shouldBe true // 90
        a.limitReached(1, now) shouldBe false
        a.recordCall(rates, 30, 0, 0, 1, now).notified100 shouldBe true // 120
        a.recordCall(rates, 30, 0, 0, 1, now)
        a.limitReached(1, now) shouldBe true
        na.n80 shouldBe 1; na.n100 shouldBe 1
        b.limitReached(1, now) shouldBe false
        nb.n80 shouldBe 0
    }

    test("no limit never notifies") {
        val store = InMemoryItemStore()
        val n = Recorder()
        val c = CostCounter(UserScopedRepository(UserId("alice-1"), store, InMemoryBlobStore()), n)
        c.recordCall(rates, 6000, 0, 0, null, now).monthTotalPaise shouldBe 6000
        c.limitReached(null, now) shouldBe false
        n.n80 shouldBe 0
    }
})
