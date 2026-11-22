package com.callanswer.app.nlg

import com.callanswer.core.model.Language
import com.callanswer.core.model.Utterance
import com.callanswer.core.network.ProviderRouter
import com.callanswer.core.network.Source
import com.callanswer.core.stalling.FictitiousReplyLibrary
import com.callanswer.core.stalling.PiiGuard
import com.callanswer.core.stalling.PromptBuilder
import com.callanswer.core.stalling.StallTopic

enum class ReplySource { LOCAL_TEMPLATE, CLOUD }

data class StallContext(
    val callerText: String,
    val language: Language,
    val gateQuery: Boolean,
    val history: List<Utterance>,
    /** Cloud deadline in ms, from `cloudDeadlineMs(elapsedSinceEndOfSpeech)`. */
    val deadlineMs: Long,
)

data class StallReply(val text: String, val source: ReplySource)

interface StallingResponder {
    suspend fun reply(ctx: StallContext): StallReply
}

/**
 * Renders the local fictitious template first, then asks the cloud (through [ProviderRouter], with the
 * deadline in [StallContext.deadlineMs]) and uses the cloud text only if [PiiGuard.acceptReply] accepts it.
 * On timeout, error, disabled cloud or a rejected reply, it silently returns the local reply
 * (Requirements 5.2, 5.4, 5.7, 8.3, 8.4, 10.7).
 */
class DefaultStallingResponder(
    private val library: FictitiousReplyLibrary,
    private val guard: PiiGuard,
    private val router: ProviderRouter,
    private val cloud: CloudLlmClient?,
    private val promptBuilder: PromptBuilder = PromptBuilder(guard),
    private val seed: () -> Long = { System.nanoTime() },
) : StallingResponder {

    override suspend fun reply(ctx: StallContext): StallReply {
        val localText = library.reply(topicOf(ctx.callerText), ctx.language, seed())
        val local = StallReply(localText, ReplySource.LOCAL_TEMPLATE)
        val client = cloud?.takeIf { it.isConfigured() } ?: return local
        val routed = router.cloudOrLocal(
            deadlineMs = ctx.deadlineMs,
            cloud = {
                val prompt = promptBuilder.build(ctx.callerText, ctx.language, ctx.history)
                val text = client.complete(prompt)
                // A rejected or empty cloud reply is an error, so the router falls back to local.
                if (text == null || !guard.acceptReply(text, ctx.language)) throw RejectedCloudReply()
                StallReply(text, ReplySource.CLOUD)
            },
            local = { local },
        )
        return if (routed.source == Source.CLOUD) routed.value else local
    }

    private class RejectedCloudReply : Exception()

    companion object {
        internal fun topicOf(text: String): StallTopic {
            val t = text.lowercase()
            return when {
                listOf("loan", "लोन", "ऋण", "emi").any { t.contains(it) } -> StallTopic.LOAN
                listOf("property", "flat", "plot", "प्रॉपर्टी", "फ्लैट", "प्लॉट").any { t.contains(it) } -> StallTopic.PROPERTY
                listOf("insurance", "credit card", "card", "बीमा", "इंश्योरेंस", "क्रेडिट", "कार्ड").any { t.contains(it) } ->
                    StallTopic.INSURANCE_OR_CARD
                else -> StallTopic.GENERIC
            }
        }
    }
}
