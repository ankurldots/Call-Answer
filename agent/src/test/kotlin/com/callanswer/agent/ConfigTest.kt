package com.callanswer.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ConfigTest : FunSpec({
    test("exotel defaults") {
        val c = Config.fromEnv(emptyMap())
        c.exotelStreamHost shouldBe "agent.leadingdots.com"
        c.exotelSampleRate shouldBe 8000
        c.exotelChunkBytes shouldBe 3200
        c.exotelSendAheadMs shouldBe 300
        c.exotelMediaTimeoutSeconds shouldBe 10
    }

    test("chunk size must be a multiple of 320 and at least 3200") {
        Config.fromEnv(mapOf("EXOTEL_CHUNK_BYTES" to "6400")).exotelChunkBytes shouldBe 6400
        shouldThrow<IllegalArgumentException> { Config.fromEnv(mapOf("EXOTEL_CHUNK_BYTES" to "1600")) }
        shouldThrow<IllegalArgumentException> { Config.fromEnv(mapOf("EXOTEL_CHUNK_BYTES" to "3300")) }
        shouldThrow<IllegalArgumentException> { Config.fromEnv(mapOf("EXOTEL_CHUNK_BYTES" to "100160")) }
    }

    test("small chunks need the explicit flag but must still be a multiple of 320") {
        val flag = "EXOTEL_ALLOW_SMALL_CHUNKS" to "true"
        Config.fromEnv(mapOf("EXOTEL_CHUNK_BYTES" to "960", flag)).exotelChunkBytes shouldBe 960
        shouldThrow<IllegalArgumentException> { Config.fromEnv(mapOf("EXOTEL_CHUNK_BYTES" to "1000", flag)) }
    }

    test("sample rate must be 8000, 16000 or 24000") {
        Config.fromEnv(mapOf("EXOTEL_SAMPLE_RATE" to "16000")).exotelSampleRate shouldBe 16000
        shouldThrow<IllegalArgumentException> { Config.fromEnv(mapOf("EXOTEL_SAMPLE_RATE" to "44100")) }
    }
})
