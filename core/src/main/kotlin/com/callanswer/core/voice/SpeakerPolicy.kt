package com.callanswer.core.voice

sealed interface SpeakerDecision {
    data object TurnOn : SpeakerDecision
    data object LeaveOff : SpeakerDecision
    data object LeaveOffHeadset : SpeakerDecision
}

object SpeakerPolicy {
    /** Setting off: leave off. Setting on with a headset connected: leave off and notify. Otherwise turn on. */
    fun decide(speakerSetting: Boolean, headsetConnected: Boolean): SpeakerDecision = when {
        !speakerSetting -> SpeakerDecision.LeaveOff
        headsetConnected -> SpeakerDecision.LeaveOffHeadset
        else -> SpeakerDecision.TurnOn
    }
}
