package com.callanswer.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

class SmokeTest : FunSpec({
    test("config defaults and overrides") {
        val d = Config.fromEnv(emptyMap())
        d.sonicMaxSessionSeconds shouldBe 480
        d.sonicModelId shouldBe "amazon.nova-2-sonic-v1:0"
        Config.fromEnv(mapOf("SONIC_IDLE_SECONDS" to "30")).sonicIdleSeconds shouldBe 30
    }

    test("/health answers 200") {
        testApplication {
            application { agentModule(Config.fromEnv(emptyMap())) }
            val r = client.get("/health")
            r.status shouldBe HttpStatusCode.OK
            r.bodyAsText() shouldBe """{"status":"UP"}"""
        }
    }
})
