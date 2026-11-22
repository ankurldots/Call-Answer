package com.callanswer.app.service

import com.callanswer.core.engine.Mode
import com.callanswer.core.engine.Phase
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.model.Language
import com.callanswer.core.model.QuickReply
import com.callanswer.core.model.Utterance

/** State shown by the Live_Call_Screen and the live-call notification. */
data class LiveCallUiState(
    val active: Boolean = false,
    val callerNumber: String? = null,
    val phase: Phase = Phase.ANSWERING,
    val mode: Mode = Mode.AGENT_ACTIVE,
    val intent: IntentCategory = IntentCategory.UNKNOWN,
    val language: Language? = null,
    val transcript: List<Utterance> = emptyList(),
    val takeoverAlertShown: Boolean = false,
    /** Quick_Replies of the call's frozen settings (13.13, 13.14). */
    val quickReplies: List<QuickReply> = emptyList(),
)
