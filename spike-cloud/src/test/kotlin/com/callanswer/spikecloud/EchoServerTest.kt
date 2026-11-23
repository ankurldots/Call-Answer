package com.callanswer.spikecloud

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.Base64

class EchoServerTest : FunSpec({
    fun media(bytes: Int) =
        """{"event":"media","stream_sid":"s1","media":{"payload":"${Base64.getEncoder().encodeToString(ByteArray(bytes) { 1 })}"}}"""

    fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    test("resolve returns a wss url with token and sample-rate") {
        val dir = Files.createTempDirectory("c1").toFile()
        testApplication {
            application { echoModule(EchoConfig(publicHost = "h.example"), SpikeLog("c1", dir), Frames("exotel", dir)) }
            val body = client.get("/exotel/resolve?sample-rate=16000").bodyAsText()
            val url = parse(body)["url"]!!.jsonPrimitive.content
            url shouldStartWith "wss://h.example/stream/"
            url.endsWith("?sample-rate=16000") shouldBe true
        }
    }

    test("media is echoed in chunks, followed by a mark; DTMF # sends clear; Authorization value is not logged") {
        val dir = Files.createTempDirectory("c1").toFile()
        testApplication {
            application { echoModule(EchoConfig(chunkBytes = 3200), SpikeLog("c1", dir), Frames("exotel", dir)) }
            val ws = createClient { install(WebSockets) }
            ws.webSocket("/stream/tok", request = { header("Authorization", "Basic c2VjcmV0OnNlY3JldA==") }) {
                send(Frame.Text("""{"event":"start","start":{"stream_sid":"s1"}}"""))
                send(Frame.Text(media(8000)))
                val sizes = mutableListOf<Int>()
                var mark: String? = null
                while (mark == null) {
                    val m = parse((incoming.receive() as Frame.Text).readText())
                    when (m["event"]!!.jsonPrimitive.content) {
                        "media" -> sizes += Base64.getDecoder()
                            .decode(m["media"]!!.jsonObject["payload"]!!.jsonPrimitive.content).size
                        "mark" -> mark = m["mark"]!!.jsonObject["name"]!!.jsonPrimitive.content
                    }
                }
                sizes shouldBe listOf(3200, 3200, 3200)
                mark shouldBe "echo-1"
                send(Frame.Text("""{"event":"dtmf","stream_sid":"s1","dtmf":{"digit":"#"}}"""))
                parse((incoming.receive() as Frame.Text).readText())["event"]!!.jsonPrimitive.content shouldBe "clear"
            }
            val logged = dir.resolve("c1.jsonl").readText()
            logged.contains("c2VjcmV0") shouldBe false
            logged.contains("present:Basic") shouldBe true
        }
    }
})
