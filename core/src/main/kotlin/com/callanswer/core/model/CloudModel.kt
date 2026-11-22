package com.callanswer.core.model

import kotlinx.serialization.Serializable

/** What the Exotel flow does with a call the Agent does not take. */
enum class FallbackRoute { VOICEMAIL, FORWARD_TO_MOBILE }

/** Rates in paise per minute, used by the cost estimate. Configured values, never hard-coded prices. */
@Serializable
data class CostRates(
    val exotelPerMinutePaise: Long = 0,
    val sonicInputPerMinutePaise: Long = 0,
    val sonicOutputPerMinutePaise: Long = 0,
)

/** Reply latency figures of one call. */
@Serializable
data class LatencyReport(
    val ttfaMs: Long? = null,
    val medianReplyMs: Long? = null,
    val maxReplyMs: Long? = null,
)

/** One Sonic_Session of a call. [reason] is the reason it ended (renewal, idle, error, call end). */
@Serializable
data class SonicSessionRecord(val startMs: Long, val endMs: Long? = null, val reason: String? = null)
