package com.callanswer.core.intent

import com.callanswer.core.model.IntentCategory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

class RuleIntentClassifierTest : StringSpec({
    val classifier = RuleIntentClassifier(PhraseTables.DEFAULT)
    val p = PhraseTables.DEFAULT

    "normalizer applies NFKC, lower case, nukta removal, punctuation strip and space collapse" {
        TextNormalizer.normalize("  Hello,   WORLD!! ") shouldBe "hello world"
        TextNormalizer.normalize("दरवाज़ा, खोलो।") shouldBe "दरवाजा खोलो"
        TextNormalizer.normalize("Pre-approved") shouldBe "pre approved"
    }

    "S7 reference utterances classify as expected" {
        val file = listOf("../spike/src/main/assets/s7/reference.tsv", "spike/src/main/assets/s7/reference.tsv")
            .map { File(it) }.first { it.exists() }
        val rows = file.readLines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }
        (rows.size >= 30) shouldBe true
        val wrong = rows.filter { classifier.classify(it[4]).category.name != it[3] }.map { it[0] }
        wrong shouldBe emptyList()
    }

    "earliest match wins and ties go to the longer match" {
        classifier.classify("loan offer, also a parcel delivery").category shouldBe IntentCategory.SOLICITATION
        classifier.classify("parcel delivery, also a loan offer").category shouldBe IntentCategory.DELIVERY
        classifier.classify("hello").let { it.category to it.matchStart } shouldBe (IntentCategory.UNKNOWN to null)
    }

    "gate query is flagged without changing the category" {
        classifier.classify("Which gate should I use?").let { it.category to it.gateQuery } shouldBe (IntentCategory.UNKNOWN to true)
        classifier.classify("main delivery boy hoon, kaun sa gate se aaun").let { it.category to it.gateQuery } shouldBe (IntentCategory.DELIVERY to true)
        classifier.classify("कौन से गेट से आऊं, पार्सल है").let { it.category to it.gateQuery } shouldBe (IntentCategory.DELIVERY to true)
        classifier.classify("I have a parcel").gateQuery shouldBe false
    }

    "engine tables match repeat, acknowledgement and timing phrases" {
        p.matchesAny(p.repeatRequest, "Can you say that again?") shouldBe true
        p.matchesAny(p.repeatRequest, "दोबारा बताइए") shouldBe true
        p.matchesAny(p.repeatRequest, "phir se bolo") shouldBe true
        p.matchesAny(p.acknowledgement, "okay, thank you") shouldBe true
        p.matchesAny(p.acknowledgement, "ठीक है") shouldBe true
        p.matchesAny(p.timingQuestion, "gate kitne baje band hota hai") shouldBe true
        p.matchesAny(p.timingQuestion, "what time does the gate close") shouldBe true
    }

    "solicitation topics are split" {
        p.isTopic(SolicitationTopic.LOAN, "personal loan offer") shouldBe true
        p.isTopic(SolicitationTopic.PROPERTY, "buy a plot") shouldBe true
        p.isTopic(SolicitationTopic.LOAN, "buy a plot") shouldBe false
    }
})
