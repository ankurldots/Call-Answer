package com.callanswer.spikecloud

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class HarnessTest : FunSpec({
    test("SpikeLog writes a JSON line with timestamp") {
        val dir = Files.createTempDirectory("sl").toFile()
        val log = SpikeLog("c5", dir)
        log.log("hello", mapOf("n" to 1, "s" to "a\"b"))
        val line = dir.resolve("c5.jsonl").readLines().single()
        line.startsWith("{\"ts\":\"") shouldBe true
        line.endsWith("\"event\":\"hello\",\"n\":1,\"s\":\"a\\\"b\"}") shouldBe true
    }
    test("Frames stores text and binary frames in order") {
        val dir = Files.createTempDirectory("fr").toFile()
        val fr = Frames("exotel", dir)
        fr.record("in", "{\"event\":\"start\"}").readText() shouldBe "{\"event\":\"start\"}"
        fr.record("out", byteArrayOf(1, 2)).name shouldBe "00001-out.bin"
    }
})
