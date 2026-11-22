package com.callanswer.spike

import android.telecom.Call
import android.telecom.CallScreeningService

/** S1: logs the call, allows it, starts the foreground service and answers it. */
class SpikeScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        S1Spike.markRing()
        SpikeLog.init(this)
        SpikeLog.log(
            S1Spike.CHECK,
            "onScreenCall number=${callDetails.handle?.schemeSpecificPart} presentation=${callDetails.handlePresentation} direction=${callDetails.callDirection}",
        )
        respondToCall(callDetails, CallResponse.Builder().build())
        S1Spike.startForeground(applicationContext)
        val mode = S1Settings.mode(applicationContext)
        SpikeLog.log(S1Spike.CHECK, "active mode: ${S1Settings.describe(applicationContext)}")
        if (mode != S1Mode.OFF) S1Spike.answerWithRetries(applicationContext)
        if (mode == S1Mode.AUTO_ANSWER_AND_END) S1Spike.scheduleAutoEnd(applicationContext)
    }
}
