package com.callanswer.core.cloud

import com.callanswer.core.tenant.E164
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
})
