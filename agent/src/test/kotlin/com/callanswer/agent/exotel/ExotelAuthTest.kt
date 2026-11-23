package com.callanswer.agent.exotel

import com.callanswer.agent.Clock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Base64

class ExotelAuthTest : FunSpec({
    class ManualClock(var now: Long = 1_000_000L) : Clock {
        override fun nowMillis() = now
    }

    val clock = ManualClock()
    val secrets = mapOf("web" to "exotel:pw", "tok" to "key-1")
    val auth = ExotelAuth({ secrets[it] }, "web", "tok", clock)
    fun basic(s: String) = "Basic " + Base64.getEncoder().encodeToString(s.toByteArray())

    test("basic auth accepts only the stored credential") {
        auth.checkBasic(basic("exotel:pw")) shouldBe true
        auth.checkBasic(basic("exotel:bad")) shouldBe false
        auth.checkBasic("Basic !!!") shouldBe false
        auth.checkBasic(null) shouldBe false
        ExotelAuth({ null }, "web", "tok", clock).checkBasic(basic("exotel:pw")) shouldBe false
    }

    test("token verifies before expiry and yields the user id") {
        val t = auth.createCallToken("CA1", "user-1")
        auth.verifyCallToken(t, "CA1") shouldBe "user-1"
        clock.now += 59_999
        auth.verifyCallToken(t, "CA1") shouldBe "user-1"
        clock.now += 1
        auth.verifyCallToken(t, "CA1") shouldBe null
        clock.now = 1_000_000L
    }

    test("inspectCallToken returns claims and rejects expired, tampered and garbage tokens") {
        val t = auth.createCallToken("CA1", "user-1")
        auth.inspectCallToken(t) shouldBe CallTokenClaims("CA1", "user-1")
        auth.inspectCallToken(t.dropLast(2) + "AA") shouldBe null
        auth.inspectCallToken("garbage") shouldBe null
        auth.inspectCallToken("a.b.c.d") shouldBe null
        clock.now += 60_000
        auth.inspectCallToken(t) shouldBe null
        clock.now = 1_000_000L
    }

    test("token fails for other call, other secret, or tampering") {
        val t = auth.createCallToken("CA1", "user-1")
        auth.verifyCallToken(t, "CA2") shouldBe null
        ExotelAuth({ if (it == "tok") "key-2" else null }, "web", "tok", clock).verifyCallToken(t, "CA1") shouldBe null
        auth.verifyCallToken(t.dropLast(2) + "AA", "CA1") shouldBe null
        auth.verifyCallToken("garbage", "CA1") shouldBe null
    }
})
