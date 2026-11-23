package com.callanswer.core.cloud

import com.callanswer.core.model.IntentCategory
import com.callanswer.core.model.Settings
import com.callanswer.core.settings.SettingsValidator
import com.callanswer.core.tenant.E164
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.FunSpec

class RingThroughTest : FunSpec({
    val num = E164("+919876543210")
    val end = 1_000_000L
    fun rec(i: IntentCategory) = RecentCall(end, i)

    test("repeatRules") {
        val w = 15 * 60_000L
        RingThroughPolicy.isRepeat(rec(IntentCategory.UNKNOWN), num, end + w, 15) shouldBe true
        RingThroughPolicy.isRepeat(rec(IntentCategory.UNKNOWN), num, end + w + 1, 15) shouldBe false
        RingThroughPolicy.isRepeat(rec(IntentCategory.DELIVERY), num, end - 1, 15) shouldBe false
        RingThroughPolicy.isRepeat(rec(IntentCategory.SOLICITATION), num, end, 15) shouldBe false
        RingThroughPolicy.isRepeat(rec(IntentCategory.DELIVERY), null, end, 15) shouldBe false
        RingThroughPolicy.isRepeat(null, num, end, 15) shouldBe false
    }

    test("defaultScheduleHasElevenLegs") {
        var legs = 0
        var now = 0L
        val st = { RingThroughState(0, legs) }
        while (true) {
            when (val a = RedialSchedule.next(st(), now)) {
                is RedialAction.DialNow -> { (a.timeoutSeconds <= 15) shouldBe true; legs++ }
                is RedialAction.Ringback -> now = a.untilMs
                RedialAction.Final -> break
            }
        }
        legs shouldBe 11
        RedialSchedule.next(RingThroughState(0, 10), 170_000) shouldBe RedialAction.DialNow(10)
        RedialSchedule.next(RingThroughState(0, 1), 3_000) shouldBe RedialAction.Ringback(17_000)
    }

    test("settingsDefaultsAndRules") {
        val s = Settings()
        (s.ringThroughEnabled to s.ringThroughWindowMinutes) shouldBe (true to 15)
        s.ringThroughMaxSeconds shouldBe 180
        SettingsValidator.validateRingThroughWindow(0).isValid shouldBe false
        SettingsValidator.validateRingThroughWindow(60).isValid shouldBe true
        SettingsValidator.validateRingThroughMax(29).isValid shouldBe false
        SettingsValidator.validateRingThroughMax(600).isValid shouldBe true
    }
})
