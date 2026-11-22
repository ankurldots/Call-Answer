package com.callanswer.core.stalling

import com.callanswer.core.model.Language
import kotlin.random.Random

/** Solicitation topic used to choose a stalling template (Requirement 5.2). */
enum class StallTopic { LOAN, PROPERTY, INSURANCE_OR_CARD, GENERIC }

/**
 * Local, offline stalling replies built from fictional slot pools (Requirements 5.2, 5.4, 5.5, 5.7, 5.10, 10.7).
 *
 * Every reply ends with exactly one follow-up question. Pool entries equal to or containing a protected
 * value are removed at construction. Selection is deterministic for a given seed.
 */
class FictitiousReplyLibrary(protectedValues: Set<String>) {

    private val guard = PiiGuard(protectedValues)
    private val protectedLower: List<String> =
        protectedValues.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun filter(pool: List<String>): List<String> = pool.filter { e ->
        val l = e.lowercase()
        protectedLower.none { l.contains(it) } && !guard.containsProtected(e)
    }

    private class Pools(val names: List<String>, val amounts: List<String>, val places: List<String>, val times: List<String>)

    private val pools: Map<Language, Pools> = mapOf(
        Language.EN to Pools(
            names = filter(EN_NAMES), amounts = filter(EN_AMOUNTS), places = filter(EN_PLACES), times = filter(EN_TIMES),
        ),
        Language.HI to Pools(
            names = filter(HI_NAMES), amounts = filter(HI_AMOUNTS), places = filter(HI_PLACES), times = filter(HI_TIMES),
        ),
    )

    /** A stalling reply for [topic] in [language]; always passes [PiiGuard.acceptReply] for the library's protected values. */
    fun reply(topic: StallTopic, language: Language, seed: Long): String {
        val rnd = Random(seed)
        val templates = TEMPLATES.getValue(language).getValue(topic) + TEMPLATES.getValue(language).getValue(StallTopic.GENERIC)
        repeat(MAX_ATTEMPTS) {
            val text = fill(templates[rnd.nextInt(templates.size)], pools.getValue(language), rnd) ?: return@repeat
            if (guard.acceptReply(text, language)) return text
        }
        return safeFiller(language, seed)
    }

    /** Fixed filler phrase used when no reply is ready in time (Requirement 5.10). */
    fun stallFiller(language: Language, seed: Long): String = safeFiller(language, seed)

    private fun safeFiller(language: Language, seed: Long): String {
        val all = STALL_FILLERS.getValue(language)
        val ok = all.filter { guard.acceptReply(it, language) }.ifEmpty { all }
        return ok[Random(seed).nextInt(ok.size)]
    }

    private fun fill(template: String, p: Pools, rnd: Random): String? {
        var out = template
        for ((key, pool) in listOf("{name}" to p.names, "{amount}" to p.amounts, "{place}" to p.places, "{time}" to p.times)) {
            if (out.contains(key)) {
                if (pool.isEmpty()) return null
                out = out.replace(key, pool[rnd.nextInt(pool.size)])
            }
        }
        return out
    }

    companion object {
        private const val MAX_ATTEMPTS = 12

        /** Fixed, slot-free phrases, each with exactly one question. */
        val STALL_FILLERS: Map<Language, List<String>> = mapOf(
            Language.EN to listOf(
                "One moment please, could you say that once more?",
                "Sorry, the line is not clear, can you repeat that?",
                "Please hold on a second, what were you saying?",
            ),
            Language.HI to listOf(
                "एक मिनट रुकिए, क्या आप दोबारा बता सकते हैं?",
                "माफ़ कीजिए, आवाज़ साफ़ नहीं आ रही, क्या आप फिर से बोल सकते हैं?",
                "ज़रा रुकिए, आप क्या कह रहे थे?",
            ),
        )

        private val EN_NAMES = listOf("Mr. Sharma", "my husband Raju", "my mother Kamla", "Mrs. Verma", "my brother Sunil")
        private val EN_AMOUNTS = listOf("two lakh", "five lakh", "fifty thousand", "three lakh", "one lakh")
        private val EN_PLACES = listOf("the market", "my sister's house", "the temple", "the bank near the station", "my uncle's shop")
        private val EN_TIMES = listOf("this evening", "tomorrow morning", "after lunch", "next week", "in the afternoon")

        private val HI_NAMES = listOf("शर्मा जी", "मेरे पति राजू", "मेरी माँ कमला", "वर्मा जी", "मेरा भाई सुनील")
        private val HI_AMOUNTS = listOf("दो लाख", "पाँच लाख", "पचास हज़ार", "तीन लाख", "एक लाख")
        private val HI_PLACES = listOf("बाज़ार", "मेरी बहन के घर", "मंदिर", "स्टेशन के पास वाले बैंक", "चाचा की दुकान")
        private val HI_TIMES = listOf("आज शाम", "कल सुबह", "खाने के बाद", "अगले हफ़्ते", "दोपहर में")

        private val TEMPLATES: Map<Language, Map<StallTopic, List<String>>> = mapOf(
            Language.EN to mapOf(
                StallTopic.LOAN to listOf(
                    "Oh, a loan? {name} was just talking about needing {amount}. What is the interest rate?",
                    "I am at {place} right now. Can you explain how the loan works?",
                    "My family is discussing a loan of {amount}. Which documents do you need?",
                ),
                StallTopic.PROPERTY to listOf(
                    "A property, you say? We were looking near {place}. Which area is it in?",
                    "{name} wanted to buy a flat {time}. How big is it?",
                    "I am busy at {place} but interested. What is the price?",
                ),
                StallTopic.INSURANCE_OR_CARD to listOf(
                    "Insurance? {name} mentioned that {time}. What does the plan cover?",
                    "A card offer, hmm. I am at {place} right now. Are there any yearly charges?",
                    "We pay about {amount} for cover already. How is yours different?",
                ),
                StallTopic.GENERIC to listOf(
                    "I am at {place} right now, it is a bit noisy. Which company are you calling from?",
                    "{name} is not here {time}. What is this about exactly?",
                    "Sorry, I did not catch that. Could you explain it slowly?",
                ),
            ),
            Language.HI to mapOf(
                StallTopic.LOAN to listOf(
                    "अच्छा, लोन? {name} अभी {amount} की बात कर रहे थे। ब्याज दर क्या है?",
                    "मैं अभी {place} में हूँ। क्या आप समझा सकते हैं कि लोन कैसे मिलता है?",
                    "हमारे घर में {amount} के लोन की बात चल रही है। कौन से कागज़ चाहिए?",
                ),
                StallTopic.PROPERTY to listOf(
                    "प्रॉपर्टी? हम {place} के पास देख रहे थे। यह किस इलाके में है?",
                    "{name} {time} फ़्लैट लेना चाहते थे। कितना बड़ा है?",
                    "मैं {place} में व्यस्त हूँ पर रुचि है। इसकी कीमत क्या है?",
                ),
                StallTopic.INSURANCE_OR_CARD to listOf(
                    "बीमा? {name} ने {time} इसके बारे में बताया था। इसमें क्या-क्या कवर होता है?",
                    "कार्ड का ऑफ़र, हम्म। मैं अभी {place} में हूँ। क्या कोई सालाना शुल्क है?",
                    "हम पहले से {amount} का कवर लेते हैं। आपका प्लान अलग कैसे है?",
                ),
                StallTopic.GENERIC to listOf(
                    "मैं अभी {place} में हूँ, थोड़ा शोर है। आप किस कंपनी से बोल रहे हैं?",
                    "{name} {time} यहाँ नहीं हैं। यह किस बारे में है?",
                    "माफ़ कीजिए, समझ नहीं आया। क्या आप धीरे से समझा सकते हैं?",
                ),
            ),
        )
    }
}
