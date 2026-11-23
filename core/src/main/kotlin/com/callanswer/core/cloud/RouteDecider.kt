package com.callanswer.core.cloud

import com.callanswer.core.tenant.E164
import com.callanswer.core.tenant.PhoneNumbers

/** Why a call takes the Fallback_Route instead of the Agent. The name is stored as the Call_Log `fallbackCause`. */
enum class FallbackCause {
    AGENT_OFF,
    CONSENT_NOT_ACCEPTED,
    USER_INACTIVE,
    MONTHLY_LIMIT,
    LOOP,
    USER_LIMIT,
    GLOBAL_CAP,
}

sealed class RouteDecision {
    object Agent : RouteDecision() {
        override fun toString(): String = "Agent"
    }

    data class Fallback(val cause: FallbackCause) : RouteDecision()
    /** Repeat_Caller: the server rings the User's mobile and starts no Sonic_Session. */
    object RingThrough : RouteDecision() {
        override fun toString(): String = "RingThrough"
    }
}

/** Everything the route decision reads for one incoming call. Counters are the calls active before this one. */
data class RouteInput(
    val agentEnabled: Boolean,
    val consentAccepted: Boolean,
    val userActive: Boolean,
    val monthTotalPaise: Long,
    val monthlyLimitPaise: Long,
    val userActiveCalls: Int,
    val globalActiveCalls: Int,
    val perUserLimit: Int = RouteDecider.DEFAULT_PER_USER_LIMIT,
    val globalCap: Int = RouteDecider.DEFAULT_GLOBAL_CAP,
    /** The `From` of the call; null or blank when the Caller number is hidden. */
    val from: String?,
    val userMobile: E164?,
    /** Every assigned Virtual_Number. */
    val assignedNumbers: Set<E164>,
    /** Result of the `RecentCall` lookup for this Caller and User; null when none or when the lookup failed. */
    val recentCall: RecentCall? = null,
    val nowMs: Long = 0L,
    val ringThroughEnabled: Boolean = true,
    val ringThroughWindowMinutes: Int = RingThroughPolicy.DEFAULT_WINDOW_MINUTES,
)

/** Pure decision of `/exotel/route` after the tenant is resolved (Requirements 1.5, 1.7, 17.2, 17.4, 20.7, 22.5, 25.8, 29.1 to 29.3). */
object RouteDecider {
    const val DEFAULT_PER_USER_LIMIT = 3
    const val DEFAULT_GLOBAL_CAP = 10

    /**
     * Order (design, "Repeat caller ring-through"): loop rejection, User gates (active, consent, Agent on),
     * then `ringThroughEnabled` with `isRepeat` (RingThrough), then the Agent gates (cost limit, per-User
     * calls, Global_Call_Cap). RingThrough ignores the cost limit and the caps because no Sonic_Session starts.
     */
    fun decide(input: RouteInput): RouteDecision {
        val from = normalizedFrom(input.from)
        if (from != null && (from == input.userMobile || from in input.assignedNumbers)) {
            return RouteDecision.Fallback(FallbackCause.LOOP)
        }
        val gateCause = when {
            !input.agentEnabled -> FallbackCause.AGENT_OFF
            !input.consentAccepted -> FallbackCause.CONSENT_NOT_ACCEPTED
            !input.userActive -> FallbackCause.USER_INACTIVE
            else -> null
        }
        if (gateCause != null) return RouteDecision.Fallback(gateCause)
        if (input.ringThroughEnabled &&
            RingThroughPolicy.isRepeat(input.recentCall, from, input.nowMs, input.ringThroughWindowMinutes)
        ) {
            return RouteDecision.RingThrough
        }
        val cause = when {
            input.monthTotalPaise >= input.monthlyLimitPaise -> FallbackCause.MONTHLY_LIMIT
            input.userActiveCalls >= input.perUserLimit -> FallbackCause.USER_LIMIT
            input.globalActiveCalls >= input.globalCap -> FallbackCause.GLOBAL_CAP
            else -> null
        }
        return if (cause == null) RouteDecision.Agent else RouteDecision.Fallback(cause)
    }

    private fun normalizedFrom(from: String?): E164? =
        from?.takeIf { it.isNotBlank() }?.let { PhoneNumbers.normalize(it) }
}
