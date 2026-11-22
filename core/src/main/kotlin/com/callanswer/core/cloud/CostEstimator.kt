package com.callanswer.core.cloud

import com.callanswer.core.model.CostRates

/** Estimates the cost of a call in paise from the configured [CostRates]. */
object CostEstimator {
    /** Durations are in seconds; each rate is per minute. Rounds up to a whole paisa. */
    fun estimatePaise(rates: CostRates, callSec: Long, callerAudioSec: Long, agentAudioSec: Long): Long {
        val weighted = rates.exotelPerMinutePaise * callSec.coerceAtLeast(0) +
            rates.sonicInputPerMinutePaise * callerAudioSec.coerceAtLeast(0) +
            rates.sonicOutputPerMinutePaise * agentAudioSec.coerceAtLeast(0)
        return (weighted + 59) / 60
    }
}
