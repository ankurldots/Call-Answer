package com.callanswer.core.language

import com.callanswer.core.model.Language
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ScriptLanguageDetectorTest : FunSpec({
    val d = ScriptLanguageDetector.withBundledLexicon()

    test("Devanagari is Hindi") { d.detect("आप कौन बोल रहे हैं") shouldBe Language.HI }
    test("English words are English") { d.detect("who is calling please") shouldBe Language.EN }
    test("Roman Hinglish is Hindi") { d.detect("aap kaun ho") shouldBe Language.HI }
    test("majority wins") { d.detect("please bataiye aap kaun hai") shouldBe Language.HI }
    test("tie goes to first counted token") {
        d.detect("hello aap") shouldBe Language.EN
        d.detect("aap hello") shouldBe Language.HI
    }
    test("digits and punctuation not counted") {
        d.detect("123 ... ?!") shouldBe null
        d.detect("") shouldBe null
        d.detect("aap 123 !!!") shouldBe Language.HI
    }
    test("resolver falls back") {
        ResponseLanguageResolver.resolve(Language.HI, Language.EN) shouldBe Language.HI
        ResponseLanguageResolver.resolve(null, Language.HI) shouldBe Language.HI
        ResponseLanguageResolver.resolve(null, null) shouldBe Language.EN
    }
})
