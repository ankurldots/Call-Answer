package com.callanswer.agent.store

import com.callanswer.core.cloud.CostEstimator
import com.callanswer.core.cloud.MonthlyCostLimit
import com.callanswer.core.model.CostRates
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Sends the push notifications of the monthly limit. Each is called at most once per User and month. */
interface CostNotifier {
    suspend fun limit80Reached(totalPaise: Long)
    suspend fun limit100Reached(totalPaise: Long)
}

data class CostOutcome(val estimatePaise: Long, val monthTotalPaise: Long, val notified80: Boolean, val notified100: Boolean)

/**
 * Month total of one User (`USER#<sub>` / `COST#<yyyy-MM>`). The repository is already scoped to one User, so the
 * estimate and the total never mix Users (Requirement 22.8). The estimate is not a bill and nothing is invoiced.
 */
class CostCounter(
    private val repo: UserScopedRepository,
    private val notifier: CostNotifier,
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
) {
    /** Adds the estimate of an ended call to the month total and sends the 80 and 100 percent notifications once. */
    suspend fun recordCall(
        rates: CostRates, callSec: Long, callerAudioSec: Long, agentAudioSec: Long,
        monthlyLimitInr: Int?, nowMs: Long,
    ): CostOutcome {
        val estimate = CostEstimator.estimatePaise(rates, callSec, callerAudioSec, agentAudioSec)
        val month = monthOf(nowMs)
        val total = repo.addCost(month, estimate)
        val status = MonthlyCostLimit.status(total, monthlyLimitInr)
        val sent80 = status.reached80 && repo.claimCostNotification(month, "notified80")
        if (sent80) notifier.limit80Reached(total)
        val sent100 = status.reached100 && repo.claimCostNotification(month, "notified100")
        if (sent100) notifier.limit100Reached(total)
        return CostOutcome(estimate, total, sent80, sent100)
    }

    /** True when new calls of this User take the Fallback_Route this month. */
    suspend fun limitReached(monthlyLimitInr: Int?, nowMs: Long): Boolean =
        MonthlyCostLimit.limitReached(repo.getCost(monthOf(nowMs))?.totalPaise ?: 0L, monthlyLimitInr)

    private fun monthOf(nowMs: Long): String = MONTH.format(Instant.ofEpochMilli(nowMs).atZone(zone))

    private companion object { val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM") }
}
