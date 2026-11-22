package com.callanswer.core.model

import kotlinx.serialization.Serializable

/** A blank text means the language is missing. */
@Serializable
data class DeliveryStep(val id: String, val textEn: String, val textHi: String)

@Serializable
data class QuickReply(val textEn: String, val textHi: String = "")

val DefaultDeliverySteps: List<DeliveryStep> = listOf(
    DeliveryStep("step-1", "Come to the house.", "घर पर आइए।"),
    DeliveryStep("step-2", "Take the stairs in the middle of the house.", "घर के बीच में बनी सीढ़ियों से ऊपर जाइए।"),
    DeliveryStep("step-3", "Go to the second floor.", "दूसरी मंज़िल पर जाइए।"),
    DeliveryStep("step-4", "Ring the bell.", "घंटी बजाइए।"),
)

val DefaultQuickReplies: List<QuickReply> = listOf(
    QuickReply(textEn = "Please wait", textHi = "कृपया प्रतीक्षा करें"),
    QuickReply(textEn = "I am coming", textHi = "मैं आ रहा हूँ"),
)

const val DEFAULT_AGENT_NAME = "Agent"

@Serializable
data class Settings(
    /** Name the User gives the answering agent. Shown in the app; blank falls back to [DEFAULT_AGENT_NAME]. */
    val agentName: String = DEFAULT_AGENT_NAME,
    val screeningEnabled: Boolean = true,
    /** Location URL (map link). Texted to a delivery caller who needs directions; never spoken. */
    val mapLocation: String? = null,
    /** Postal address. Only used to check the address a delivery caller states; never spoken or sent. */
    val address: String? = null,
    /** [address] in Devanagari, made automatically from it and editable. Used only for matching. */
    val addressHi: String? = null,
    val transferTarget: String? = null,
    val maxStallingSeconds: Int = 120,
    val gates: GateConfig = DefaultGates,
    val deliverySteps: List<DeliveryStep> = DefaultDeliverySteps,
    val voiceGender: VoiceGender = VoiceGender.FEMALE,
    val disclosureEnabled: Boolean = false,
    val speakerEnabled: Boolean = true,
    val silenceTimeoutSeconds: Int = 8,
    val quickReplies: List<QuickReply> = DefaultQuickReplies,
    val recordingFolderUri: String? = null,
    val protectedTerms: List<String> = emptyList(),
    val cloudLlmEnabled: Boolean = false,
    val silentSendSpamReports: Boolean = false,
) {
    /** Snapshot taken at call start. */
    fun freeze(): FrozenSettings = FrozenSettings(
        mapLocation = mapLocation,
        address = address,
        addressHi = addressHi,
        transferTarget = transferTarget,
        maxStallingSeconds = maxStallingSeconds,
        gates = gates,
        deliverySteps = deliverySteps,
        voiceGender = voiceGender,
        disclosureEnabled = disclosureEnabled,
        speakerEnabled = speakerEnabled,
        silenceTimeoutSeconds = silenceTimeoutSeconds,
        quickReplies = quickReplies,
        protectedTerms = protectedTerms,
        cloudLlmEnabled = cloudLlmEnabled,
        silentSendSpamReports = silentSendSpamReports,
        agentName = agentName,
    )
}

/** Settings taken at call start; they do not change during the call (9.15, 9.22, 9.25). */
@Serializable
data class FrozenSettings(
    val mapLocation: String? = null,
    val address: String? = null,
    val addressHi: String? = null,
    val transferTarget: String? = null,
    val maxStallingSeconds: Int = 120,
    val gates: GateConfig = DefaultGates,
    val deliverySteps: List<DeliveryStep> = DefaultDeliverySteps,
    val voiceGender: VoiceGender = VoiceGender.FEMALE,
    val disclosureEnabled: Boolean = false,
    val speakerEnabled: Boolean = true,
    val silenceTimeoutSeconds: Int = 8,
    val quickReplies: List<QuickReply> = DefaultQuickReplies,
    val protectedTerms: List<String> = emptyList(),
    val cloudLlmEnabled: Boolean = false,
    val silentSendSpamReports: Boolean = false,
    /** Name the agent gives itself on the call. */
    val agentName: String = DEFAULT_AGENT_NAME,
)
