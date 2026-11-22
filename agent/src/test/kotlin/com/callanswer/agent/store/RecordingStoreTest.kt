package com.callanswer.agent.store

import com.callanswer.core.tenant.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

class RecordingStoreTest : FunSpec({
    val seen = mutableListOf<Pair<String, Duration>>()
    fun store(blobs: BlobStore = InMemoryBlobStore()) =
        RecordingStore(blobs) { key, exp -> seen += key to exp; "https://signed/$key" }
    val u = UserId("abc")
    val other = UserId("abcd")

    test("upload goes under the user's recordings prefix and status becomes AVAILABLE") {
        val s = store()
        s.status(u, "c1") shouldBe RecordingStatus.NOT_FOUND
        s.upload(u, "c1", byteArrayOf(1)) shouldBe "users/abc/recordings/c1.wav"
        s.status(u, "c1") shouldBe RecordingStatus.AVAILABLE
        s.status(other, "c1") shouldBe RecordingStatus.NOT_FOUND
    }

    test("presign expires in 10 minutes and returns null for a missing recording") {
        val s = store()
        s.presign(u, "c1") shouldBe null
        s.upload(u, "c1", byteArrayOf(1))
        val p = s.presign(u, "c1")!!
        p.expiresInSeconds shouldBe 600
        seen.last().second shouldBe Duration.ofMinutes(10)
    }

    test("a key outside the prefix is refused") {
        val s = store()
        shouldThrow<IllegalArgumentException> { s.presignKey(u, "users/abcd/recordings/c1.wav") }
        shouldThrow<IllegalArgumentException> { s.presignKey(u, "users/abc/transcripts/c1.json") }
        shouldThrow<IllegalArgumentException> { s.presignKey(u, "users/abc/recordings/../../abcd/recordings/c1.wav") }
    }

    test("delete removes only the user's recording") {
        val s = store()
        s.upload(u, "c1", byteArrayOf(1))
        s.upload(other, "c1", byteArrayOf(2))
        s.delete(u, "c1")
        s.status(u, "c1") shouldBe RecordingStatus.NOT_FOUND
        s.status(other, "c1") shouldBe RecordingStatus.AVAILABLE
    }
})
