package com.callanswer.core.intent

import com.callanswer.core.model.IntentCategory
import java.text.Normalizer

enum class SolicitationTopic { LOAN, PROPERTY, OTHER_SALES }

/**
 * Phrase tables over normalized text (see [TextNormalizer]): lower case, no punctuation, no nukta.
 * Every table holds Devanagari, Roman Hinglish (with spelling variants) and English patterns.
 * Patterns are word-bounded with lookarounds, because `\b` is unreliable for Devanagari combining marks.
 * Gate_Query patterns are kept apart from the category tables.
 */
class PhraseTables private constructor(
    val delivery: List<Regex>,
    val doorNotOpened: List<Regex>,
    val solicitationByTopic: Map<SolicitationTopic, List<Regex>>,
    val gateQuery: List<Regex>,
    val repeatRequest: List<Regex>,
    val acknowledgement: List<Regex>,
    val timingQuestion: List<Regex>,
    val directionsRequest: List<Regex>,
    val addressStatement: List<Regex>,
) {
    /** Union of all solicitation topics, treated as one category by the classifier. */
    val solicitation: List<Regex> = SolicitationTopic.entries.flatMap { solicitationByTopic.getValue(it) }

    fun forCategory(category: IntentCategory): List<Regex> = when (category) {
        IntentCategory.DELIVERY -> delivery
        IntentCategory.DOOR_NOT_OPENED -> doorNotOpened
        IntentCategory.SOLICITATION -> solicitation
        IntentCategory.UNKNOWN -> emptyList()
    }

    /** Normalizes [text] and tests whether any pattern of [table] matches it. */
    fun matchesAny(table: List<Regex>, text: String): Boolean {
        val n = TextNormalizer.normalize(text)
        return table.any { it.containsMatchIn(n) }
    }

    fun isTopic(topic: SolicitationTopic, text: String): Boolean =
        matchesAny(solicitationByTopic.getValue(topic), text)

    companion object {
        private const val L = "\\p{L}\\p{M}\\p{N}"

        /** Suffix of further letters, to accept inflections (for example `खरीदने`). */
        private const val S = "[\\p{L}\\p{M}]*"

        private fun table(vararg alternatives: String): List<Regex> =
            alternatives.map {
                // Patterns are written in the same normal form the TextNormalizer produces.
                val p = Normalizer.normalize(it, Normalizer.Form.NFKC).replace("\u093C", "")
                Regex("(?<![$L])(?:$p)(?![$L])")
            }

        private val DELIVERY = table(
            "deliver(?:y|i|ies|ed|ing)?",
            "dilivery|delivary|dilevery|deliveri|dilivary",
            "डिलीवरी|डिलिवरी|डिलेवरी|डिलीवर$S",
            "courier|couriar|kuriyar|kurier|kooriyar",
            "कूरियर|कुरियर",
            "parcel|parsal|parcell|parsel|parcal",
            "पार्सल|पारसल|पार्सेल",
            "package|packet|paket|pakat",
            "पैकेट|पैकेज|पेकेट",
            "amazon|flipkart|swiggy|zomato|blinkit|zepto|bigbasket|myntra|meesho",
            "अमेज़न|अमेजन|फ्लिपकार्ट|स्विगी|ज़ोमैटो|जोमैटो",
        )

        private val DOOR_NOT_OPENED = table(
            "(?:nobody|no one|noone|koi)(?: is| are| was)?(?: not)?(?: even)? (?:opening|open|answering|responding|khol|khul|kholta|kholti|khol raha|khol rahi)$S",
            "(?:darwaza|darwaja|darvaza|darvaja|door|gate) (?:nahi|nahin|nhi|not|na) (?:khol|khul|open)$S",
            "(?:door|darwaza|darwaja|darvaza|darvaja) (?:is )?(?:locked|closed|band)",
            "(?:door|darwaza|darwaja|darvaza|darvaja) (?:khol|open|khulwa)$S",
            "(?:ghar|house|home|gate|flat|apartment)(?: (?:ke|k|ki|ka|of))? (?:bahar|baahar|outside)",
            "(?:bahar|baahar|outside) (?:khada|khadi|khade|khara|khadaa|standing|waiting)",
            "standing (?:outside|at the gate|at your gate|at the door|at your door)",
            "(?:bell|ghanti|ghantee) (?:baja|bajai|bajaa|ring|rung|pressed)$S",
            "(?:door|gate)bell",
            "(?:no|not) (?:one )?(?:is )?(?:opening|answering) (?:the )?(?:door|gate)",
            "दरवाजा|दरवाजे|दरवाज़ा|दरवाज़े|दरवाजा|दरवाजे",
            "(?:घर|गेट|फ्लैट|मकान) (?:के )?बाहर",
            "बाहर (?:खड़ा|खड़ी|खड़े|खडा|खडी|खडे|इंतज़ार|इंतजार)$S",
            "कोई (?:नहीं |नही )?(?:दरवाजा |दरवाज़ा |गेट |दरवाजे )?(?:खोल|उठा|जवाब)$S",
            "(?:घंटी|बेल) (?:बजा|बज)$S",
            "(?:कोई )?(?:दरवाजा|गेट) (?:नहीं|नही) (?:खोल|खुल)$S",
        )

        private val LOAN = table(
            "loan|loans|lone|lon offer|karj|karza|karja",
            "लोन|ऋण|कर्ज|कर्जा|क़र्ज़|कर्ज़",
            "pre ?approved|preapproved|emi|interest rate",
            "प्री ?अप्रूव्ड|ईएमआई|ब्याज",
        )

        private val PROPERTY = table(
            "plot|plots|plaut|flat|flats|property|properties|propaerty|villa|villas|township|townships",
            "apartment|apartments|real estate|realestate|bhk|kothi|zameen|jamin|jameen|land",
            "प्लॉट|प्लाट|फ्लैट|फ़्लैट|प्रॉपर्टी|प्रोपर्टी|विला|टाउनशिप|अपार्टमेंट|ज़मीन|जमीन|कोठी|बीएचके",
        )

        private val OTHER_SALES = table(
            "offer(?:s|ing|ed)?|offar|ofer|scheme|skeem|discount|promotion|promo|membership|subscription",
            "congratulations?|congrats|badhai|badhaai|you(?:ve| have| had)? won|jeeta|jita|jeet(?:a|e|i)|inaam|inam|prize|lucky draw|lucky|gift|free|winner|reward",
            "bank|baink|otp|o t p|kyc|k y c|credit card|debit card|atm card|card details|account number",
            "insurance|insurence|bima|policy|invest(?:ment|ing|ed)?|investmant|mutual fund|trading|crypto",
            "buy|purchase|kharid$S|sale|sell|selling|bech$S|marketing|product|service plan|sim card|recharge|data plan|tower",
            "ऑफर|ऑफ़र|ओफर|ऑफ़र|स्कीम|डिस्काउंट|छूट|प्रमोशन|मेंबरशिप",
            "बधाई|मुबारक|इनाम|जीता|जीती|जीते|लकी ड्रा|लकी|गिफ्ट|तोहफा|फ्री|कंग्रेचुलेशन$S|कांग्रेचुलेशन$S",
            "बैंक|ओटीपी|केवाईसी|क्रेडिट कार्ड|डेबिट कार्ड|एटीएम|कार्ड|खाता नंबर|अकाउंट",
            "बीमा|इंश्योरेंस|पॉलिसी|इन्वेस्टमेंट|निवेश|म्यूचुअल फंड|ट्रेडिंग|क्रिप्टो",
            "खरीद$S|खरीदना|बेच$S|बिक्री|सेल|मार्केटिंग|प्रोडक्ट|रिचार्ज|सिम",
        )

        private val GATE_QUERY = table(
            "(?:which|what) (?:gate|entrance|entry|way|road)",
            "gate (?:number|no|kaun|kahan|kidhar|konsa|kaunsa|kaunsi|kis taraf|where|which)",
            "(?:kaun|kon|kaunsa|konsa|kaunsi|konsi|kaun sa|kon sa|kis|kis wale|kaun se|kon se|kaunse) (?:wale |vale |sa |se |si )?(?:gate|get|entrance|dwar|darwaza|darwaje)",
            "(?:entry|entrance|enter|andar|ghus$S|aana|aau|aaun|aaoon|aaye|aaoon|jaun|jaaun|jana) (?:kahan|kidhar|kaise|kaha|kahaan|from where|where|how)",
            "(?:kahan|kahaan|kidhar|kaha|kaise|kaisey) (?:se|ke) (?:entry|enter|andar|ghus$S|aau|aaun|aaoon|aana|jaun|jaaun|jana)",
            "(?:kahan|kahaan|kidhar|kaha|kaise) (?:se )?(?:jana|jaana|jaun|jaaun|aana|aaun|aau|aaoon|ghusna|ghusun|entry)",
            "(?:how|where|which way) (?:do|can|should|to|will|would)? ?(?:i|we)? ?(?:get in|enter|come in|go in|reach|come|enter the colony|enter the society)",
            "how (?:to|do i|can i|should i) (?:get in|enter|come in|go in|reach)",
            "(?:from )?where (?:to|do i|can i|should i|shall i) (?:enter|come in|go in|get in)",
            "(?:colony|society|sector|complex|mohalla) (?:mein|me|main|ke andar|ka|ki|ke) (?:kaise|kahan|kidhar|gate|entry|enter|andar)",
            "gate (?:kahan|kidhar|kaha|kaunsa|kaun sa|konsa)",
            "गेट (?:नंबर|नं|कहाँ|कहां|किधर|कौन|कौन सा|कौन सी|कौनसा|कौनसी|कौन से|कौनसे|किस)",
            "(?:कौन|किस|कौनसा|कौनसी|कौनसे|कौन सा|कौन सी|कौन से|कोनसा|कोन सा|कोन से) (?:वाले |सा |से |सी )?(?:गेट|गेट से|द्वार|दरवाजे|एंट्री|प्रवेश)",
            "(?:एंट्री|प्रवेश|अंदर|घुस$S|आना|आऊं|आऊँ|आउं|जाऊं|जाऊँ|जाना) (?:कहाँ|कहां|किधर|कैसे|कहाँ से|कहां से)",
            "(?:कहाँ|कहां|किधर|कैसे) (?:से )?(?:एंट्री|अंदर|घुस$S|आऊं|आऊँ|आना|जाऊं|जाऊँ|जाना|प्रवेश)",
            "(?:कॉलोनी|सोसाइटी|सोसायटी|सेक्टर) (?:में|के अंदर|का|की|के) (?:कैसे|कहाँ|कहां|किधर|गेट|एंट्री|अंदर)",
        )

        private val REPEAT_REQUEST = table(
            "repeat|repeat it|say (?:it )?again|say that again|once more|one more time|come again|pardon|once again",
            "(?:please )?(?:tell|say|speak) (?:it )?again",
            "again|dobara|dubara|dobaara|dubaara|phir se|fir se|phirse|firse|ek baar aur|ek bar aur|ek baar phir|ek baar fir",
            "repeat (?:kar|karo|kariye|kijiye|kijie|karna|karen|karein)$S|dohrao|dohraiye|dohraye|dohrana",
            "(?:kya|kia) (?:kaha|bola|bole|kahe)|phir (?:bolo|boliye|bataiye|batao)|fir (?:bolo|boliye|bataiye|batao)",
            "samajh nahi aaya|samajh nahin aaya|samjha nahi|samjh nahi|sunai nahi diya|sunayi nahi diya|awaaz nahi aa rahi",
            "didnt (?:get|catch|hear|understand)(?: that| it)?|did not (?:get|catch|hear|understand)|could not hear|couldnt hear|cant hear",
            "दोबारा|दुबारा|फिर से|फिरसे|एक बार और|एक बार फिर|एक बार दोबारा",
            "दोहरा$S|रिपीट$S",
            "(?:क्या|क्या आपने) (?:कहा|बोला|बोले)|फिर (?:बोलो|बोलिए|बताइए|बताओ|बताइये)",
            "समझ नहीं आया|समझ नही आया|समझा नहीं|समझा नही|सुनाई नहीं दिया|सुनाई नही दिया|आवाज़ नहीं आ रही|आवाज नहीं आ रही",
        )

        private val ACKNOWLEDGEMENT = table(
            "ok|okay|okk|okey|o k|alright|all right|fine|got it|understood|understand|noted|sure|right|cool|great",
            "thanks|thank you|thankyou|thanx|thank u|thx|cheers|bye|goodbye",
            "theek hai|theek|thik hai|thik|thikhai|theekhai|accha|acha|achha|achchha|samajh gaya|samajh gayi|samajh gaye|samjh gaya|samjha|mil gaya|mil gayi|mil gaye",
            "haan|han|haa|hanji|haanji|haan ji|ji haan|ji|jee|bilkul|shukriya|dhanyavad|dhanyawad|dhanyvaad|shukria|theek hai ji",
            "ठीक है|ठीक|ठिक है|ठीक हैं|अच्छा|अच्छा ठीक है|समझ गया|समझ गई|समझ गए|समझा|मिल गया|मिल गई|मिल गए",
            "ओके|ओके थैंक्स|ओके थैंक यू|ओके ठीक है|थैंक्स|थैंक यू|थैंक्यू|धन्यवाद|शुक्रिया|शुक्रिय|बाय",
            "हाँ|हां|हा|हाँ जी|हां जी|जी|जी हाँ|जी हां|बिल्कुल|जरूर",
        )

        private val TIMING_QUESTION = table(
            "(?:what|which) time|whats the time|at what time|till when|until when|till what time|until what time|from what time|from when",
            "(?:gate|entry|entrance) (?:timing|timings|time|hours|schedule)|(?:timing|timings|hours) (?:of|for) (?:the )?(?:gate|entry|entrance)",
            "(?:which|what) gate (?:at|in|during|after|before) (?:night|day|morning|evening|the night|the day)",
            "(?:night|raat|raat ko|din|din mein|din me|subah|shaam) (?:ko |mein |me |ke time |ka time )?(?:kaun|kon|kaunsa|konsa|kaunsi|kaun sa|kis|which|what) (?:gate|wala|wale)",
            "(?:kitne|kitna|kab|kis|kaun si|kaunsi|kon si) (?:baje|time|samay|tak|se|waqt|tarikh)",
            "kab tak|kab se|kab band|kab khul$S|kab khola|kab kholte|kitne baje|kis time|kis samay|kaun sa time|konsa time|time kya|time kitna|samay kya|waqt kya|time kab",
            "(?:raat|night) (?:ko |me |mein |ke )?(?:kab|kitne|kaun|kon|kis)",
            "(?:band|khula|khule|closed|open|opens|closes|closing|opening) (?:kab|hota|hoti|hote|rehta|rehti|rehte|when|time)",
            "(?:kab|when) (?:band|khul|close|closed|open|opens|closes|shut|lock)$S",
            "(?:night|raat|raat wala|night wala) (?:window|timing|time|samay|waqt)",
            "कितने बजे|कब तक|कब से|कब बंद|कब खुल$S|किस समय|किस टाइम|कौन सा टाइम|टाइम क्या|टाइम कितना|समय क्या|वक्त क्या|वक़्त क्या",
            "(?:गेट|एंट्री|प्रवेश) (?:का )?(?:टाइम|टाइमिंग|समय|वक्त|वक़्त)",
            "(?:रात|दिन|सुबह|शाम) (?:को |में |के टाइम |का टाइम )?(?:कौन|कोन|कौनसा|कौनसी|कौन सा|कौन सी|किस) (?:गेट|वाला|वाले)",
            "(?:बंद|खुला|खुले|खुलता|खुलते|बंद होता|बंद होते) (?:कब|कितने|कहाँ|होता|होती|होते|रहता|रहती|रहते)",
            "(?:रात|नाइट) (?:का |की |वाला )?(?:टाइम|समय|वक्त|विंडो|टाइमिंग)",
        )

        /** The Caller asks where the place is, or asks for the location or a map link. */
        private val DIRECTIONS_REQUEST = table(
            "location|locations|lokation|lokeshan|map|maps|link|direction|directions|route",
            "(?:where|wher) (?:is|are) (?:the |your |this )?(?:house|home|place|flat|building|society|apartment)|where exactly|how to reach|how do i reach|how to come",
            "(?:can t|cant|cannot|can not|unable to|not able to|couldn t|couldnt) (?:find|locate|reach)|lost|not finding|not getting",
            "लोकेशन|लोकेशन|मैप|मैप्स|लिंक|रास्ता|रास्ते|रूट|डायरेक्शन",
            "lokeshan|rasta|raasta|raste|kahan hai|kahaan hai|kidhar hai|kaha hai|kaise aana|kaise pahunch\\w*|nahi mil\\w*|nhi mil\\w*",
            "कहाँ है|कहां है|किधर है|कैसे आना|कैसे पहुँच\\w*|कैसे पहुंच\\w*|नहीं मिल\\w*",
        )

        /** The Caller says an address (the words that introduce it). */
        private val ADDRESS_STATEMENT = table(
            "address|adress|addres|addresses|ad dress",
            "एड्रेस|ऐड्रेस|एड्रेस",
            "पते|पता है",
        )

        val DEFAULT: PhraseTables = PhraseTables(
            delivery = DELIVERY,
            doorNotOpened = DOOR_NOT_OPENED,
            solicitationByTopic = mapOf(
                SolicitationTopic.LOAN to LOAN,
                SolicitationTopic.PROPERTY to PROPERTY,
                SolicitationTopic.OTHER_SALES to OTHER_SALES,
            ),
            gateQuery = GATE_QUERY,
            repeatRequest = REPEAT_REQUEST,
            acknowledgement = ACKNOWLEDGEMENT,
            timingQuestion = TIMING_QUESTION,
            directionsRequest = DIRECTIONS_REQUEST,
            addressStatement = ADDRESS_STATEMENT,
        )
    }
}
