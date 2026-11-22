package com.callanswer.agent.tools

import com.callanswer.core.model.FrozenSettings
import com.callanswer.core.model.Language
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class StallingResultsTest : FunSpec({
    val settings = FrozenSettings(mapLocation = "https://maps.example/xyz", protectedTerms = listOf("Press Enclave", "9876501234"))
    fun st(id: String = "c1") = MutableCallState("u1", settings, Language.EN, stallingMode = true, callId = id)

    test("persona is consistent per call and free of real data") {
        val a = GetStallingPersonaTool().execute(JsonObject(emptyMap()), st())
        val b = GetStallingPersonaTool().execute(JsonObject(emptyMap()), st())
        a shouldBe b
        val s = a.toString()
        for (t in listOf("Press Enclave", "9876501234", "maps.example")) s shouldNotContain t
        a["persona"]!!.jsonObject["name"]!!.jsonPrimitive.content.isNotEmpty() shouldBe true
    }

    test("stalling directions are fictitious") {
        val r = GetDeliveryDirectionsTool(com.callanswer.agent.SystemClock, Language.EN)
            .execute(JsonObject(mapOf("language" to kotlinx.serialization.json.JsonPrimitive("en"))), st())
        r["status"]!!.jsonPrimitive.content shouldBe "OK"
        r.toString() shouldNotContain "maps.example"
        r.toString() shouldNotContain "Press Enclave"
        r.containsKey("map_location") shouldBe false
    }
})
