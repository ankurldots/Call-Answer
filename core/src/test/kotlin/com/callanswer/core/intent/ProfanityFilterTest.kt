package com.callanswer.core.intent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
class ProfanityFilterTest : FunSpec({
    test("strong abuse in English, Hinglish and Devanagari is detected") {
        listOf(
            "what the fuck", "Fucking idiot", "you bitch", "son of a bitch", "asshole!",
            "madarchod", "behenchod", "bhenchod", "bhosdike kya hai", "chutiya hai kya", "chutiye",
            "harami", "haramzade", "teri maa ki", "gandu",
            "मादरचोद", "बहनचोद", "भोसड़ीके", "चूतिया", "हरामी", "तेरी माँ की", "गांडू",
            "you f*** off", "f**k", "b*tch",
        ).forEach { (it to ProfanityFilter.isAbusive(it)) shouldBe (it to true) }
    }
    test("ordinary delivery talk and look-alike words are not flagged") {
        listOf(
            "I have a parcel from Amazon", "Gandhi Nagar gate number 3", "aapka kutta bhonk raha hai",
            "saala ji ghar pe hain", "damn traffic", "oh shit I am lost", "where is the house",
            "मैं गांधी नगर में हूँ", "कृपया दरवाज़ा खोलिए", "Shitij road", "Chutney shop near gate",
            "bichhu", "dickens street", "scunthorpe", "price is 500", "",
        ).forEach { (it to ProfanityFilter.isAbusive(it)) shouldBe (it to false) }
    }
})
