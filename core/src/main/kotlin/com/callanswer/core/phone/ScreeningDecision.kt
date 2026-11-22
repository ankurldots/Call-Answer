package com.callanswer.core.phone

enum class Decision { ANSWER, LEAVE_RINGING, LEAVE_RINGING_NOTIFY_NO_CONTACT_ACCESS }

interface CallAnswerer { suspend fun answer(): Boolean }

interface ContactIndex {
    fun isKnown(rawNumber: String): Boolean
    val readable: Boolean
}

object ScreeningDecision {
    fun decide(
        screeningEnabled: Boolean,
        contactsReadable: Boolean,
        numberHidden: Boolean,
        knownContact: Boolean,
    ): Decision = when {
        !screeningEnabled -> Decision.LEAVE_RINGING
        !contactsReadable -> Decision.LEAVE_RINGING_NOTIFY_NO_CONTACT_ACCESS
        numberHidden -> Decision.ANSWER
        knownContact -> Decision.LEAVE_RINGING
        else -> Decision.ANSWER
    }
}
