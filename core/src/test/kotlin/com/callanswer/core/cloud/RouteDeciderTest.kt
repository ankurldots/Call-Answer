package com.callanswer.core.cloud

import com.callanswer.core.tenant.E164
import com.callanswer.core.model.IntentCategory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RouteDeciderTest : FunSpec({
    val mobile = E164("+919800000001")
    val virtual = E164("+918000000002")
    val ok = RouteInput(
        agentEnabled = true, consentAccepted = true, userActive = true,
        monthTotalPaise = 100, monthlyLimitPaise = 1000,
        userActiveCalls = 2, globalActiveCalls = 9, from = "+919811111111",
        userMobile = mobile, assignedNumbers = setOf(virtual),
    )

    test("agent selected when every condition holds") {
        RouteDecider.decide(ok) shouldBe RouteDecision.Agent
    }
    test("hidden caller number is allowed") {
        RouteDecider.decide(ok.copy(from = null)) shouldBe RouteDecision.Agent
    }
    test("each failing condition records its cause") {
        RouteDecider.decide(ok.copy(agentEnabled = false)) shouldBe RouteDecision.Fallback(FallbackCause.AGENT_OFF)
        RouteDecider.decide(ok.copy(consentAccepted = false)) shouldBe RouteDecision.Fallback(FallbackCause.CONSENT_NOT_ACCEPTED)
        RouteDecider.decide(ok.copy(userActive = false)) shouldBe RouteDecision.Fallback(FallbackCause.USER_INACTIVE)
        RouteDecider.decide(ok.copy(monthTotalPaise = 1000)) shouldBe RouteDecision.Fallback(FallbackCause.MONTHLY_LIMIT)
        RouteDecider.decide(ok.copy(userActiveCalls = 3)) shouldBe RouteDecision.Fallback(FallbackCause.USER_LIMIT)
        RouteDecider.decide(ok.copy(globalActiveCalls = 10)) shouldBe RouteDecision.Fallback(FallbackCause.GLOBAL_CAP)
    }
    test("from equal to the user mobile or an assigned number is a loop, in any format") {
        RouteDecider.decide(ok.copy(from = "09800000001")) shouldBe RouteDecision.Fallback(FallbackCause.LOOP)
        RouteDecider.decide(ok.copy(from = "+918000000002")) shouldBe RouteDecision.Fallback(FallbackCause.LOOP)
    }

    val now = 10_000_000L
    val recent = RecentCall(endMs = now - 60_000, intent = IntentCategory.DELIVERY)
    val repeat = ok.copy(recentCall = recent, nowMs = now)

    test("repeat caller rings through and ignores the cost limit and the caps") {
        RouteDecider.decide(repeat) shouldBe RouteDecision.RingThrough
        RouteDecider.decide(
            repeat.copy(monthTotalPaise = 5000, userActiveCalls = 3, globalActiveCalls = 10),
        ) shouldBe RouteDecision.RingThrough
    }
    test("ring-through still needs the user gates and ringThroughEnabled") {
        RouteDecider.decide(repeat.copy(agentEnabled = false)) shouldBe RouteDecision.Fallback(FallbackCause.AGENT_OFF)
        RouteDecider.decide(repeat.copy(consentAccepted = false)) shouldBe RouteDecision.Fallback(FallbackCause.CONSENT_NOT_ACCEPTED)
        RouteDecider.decide(repeat.copy(userActive = false)) shouldBe RouteDecision.Fallback(FallbackCause.USER_INACTIVE)
        RouteDecider.decide(repeat.copy(ringThroughEnabled = false)) shouldBe RouteDecision.Agent
    }
    test("loop wins over ring-through, and hidden or non-repeat callers do not ring through") {
        RouteDecider.decide(repeat.copy(from = "+918000000002")) shouldBe RouteDecision.Fallback(FallbackCause.LOOP)
        RouteDecider.decide(repeat.copy(from = null)) shouldBe RouteDecision.Agent
        RouteDecider.decide(repeat.copy(recentCall = null)) shouldBe RouteDecision.Agent
        RouteDecider.decide(repeat.copy(recentCall = recent.copy(intent = IntentCategory.SOLICITATION))) shouldBe RouteDecision.Agent
        RouteDecider.decide(repeat.copy(nowMs = now + 16 * 60_000)) shouldBe RouteDecision.Agent
    }
})
