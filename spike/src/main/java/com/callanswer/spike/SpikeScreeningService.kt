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
        S1Spike.answerWithRetries(applicationContext)
        S1Spike.scheduleAutoEnd(applicationContext)
    }
}
