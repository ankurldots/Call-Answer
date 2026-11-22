package com.callanswer.agent.store

import com.callanswer.core.cloud.RetentionPolicy
import com.callanswer.core.model.CallLogEntry
import com.callanswer.core.model.EndReason
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.model.Language
import com.callanswer.core.model.Outcome
import com.callanswer.core.model.RecordingInfo
import com.callanswer.core.model.RecordingStatus
import com.callanswer.core.model.Settings
import com.callanswer.core.model.VoiceGender
import com.callanswer.core.tenant.Keys
import com.callanswer.core.tenant.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun call(id: String, start: Long) = CallLogEntry(
    id = id, callerNumber = "+919812345678", startedAtEpochMs = start, zoneId = "Asia/Kolkata", durationSec = 12,
    language = Language.EN, intent = IntentCategory.DELIVERY, outcome = Outcome.CALLER_HUNG_UP,
    endReason = EndReason.CALLER_HUNG_UP, transcript = emptyList(), gateStatements = emptyList(),
    voiceGender = VoiceGender.FEMALE, voiceSubstitutions = emptyList(), stallingDurationSec = null,
    takeovers = emptyList(), interruption = null, recording = RecordingInfo(RecordingStatus.PENDING), notes = emptyList(),
)

class RetentionSweeperTest : FunSpec({
    val day = 86_400_000L
    val now = 100 * day
    val alice = UserId("alice-1")
    val bob = UserId("bob-2")

    test("selection is inclusive at start plus retention and idempotent") {
        RetentionPolicy.isExpired(now - 30 * day, now, 30) shouldBe true
        RetentionPolicy.isExpired(now - 30 * day + 1, now, 30) shouldBe false
        val starts = listOf(now - 31 * day, now - 30 * day, now - 29 * day)
        val once = RetentionPolicy.select(starts, now, 30) { it }
        once shouldBe starts.take(2)
        RetentionPolicy.select(starts - once.toSet(), now, 30) { it } shouldBe emptyList()
    }

    test("sweeper deletes expired data per user retention and leaves the rest") {
        val items = InMemoryItemStore(); val blobs = InMemoryBlobStore()
        val dir = DirectoryRepository(items)
        val settings = SettingsStore(items, dir)
        val a = UserScopedRepository(alice, items, blobs); val b = UserScopedRepository(bob, items, blobs)
        a.createProfileIfAbsent(1); b.createProfileIfAbsent(1)
        settings.update(bob, 0, Settings(retentionDays = 7))
        for ((r, u) in listOf(a to alice, b to bob)) {
            r.putCall(call("old", now - 20 * day)); r.putTrace("old", "{}", 1)
            blobs.put(Keys.recording(u, "old"), byteArrayOf(1), "audio/wav")
            r.putCall(call("new", now - 2 * day)); r.putTrace("new", "{}", 1)
            blobs.put(Keys.recording(u, "new"), byteArrayOf(1), "audio/wav")
        }
        val sweeper = RetentionSweeper(dir, settings, items, blobs, now = { now })
        sweeper.sweepOnce() shouldBe 1
        a.getCall(now - 20 * day, "old") shouldBe call("old", now - 20 * day)
        b.getCall(now - 20 * day, "old") shouldBe null
        b.getTrace("old") shouldBe null
        blobs.get(Keys.recording(bob, "old")) shouldBe null
        b.getCall(now - 2 * day, "new")?.id shouldBe "new"
        blobs.get(Keys.recording(bob, "new"))?.size shouldBe 1
        sweeper.sweepOnce() shouldBe 0
    }

    test("immediate deletion removes one call, its trace, and its recording") {
        val items = InMemoryItemStore(); val blobs = InMemoryBlobStore()
        val dir = DirectoryRepository(items)
        val a = UserScopedRepository(alice, items, blobs)
        a.putCall(call("c1", now)); a.putTrace("c1", "{}", 1)
        blobs.put(Keys.recording(alice, "c1"), byteArrayOf(1), "audio/wav")
        val sweeper = RetentionSweeper(dir, SettingsStore(items, dir), items, blobs, now = { now })
        sweeper.deleteCallNow(alice, "c1") shouldBe true
        a.findCall("c1") shouldBe null
        a.getTrace("c1") shouldBe null
        blobs.get(Keys.recording(alice, "c1")) shouldBe null
        sweeper.deleteCallNow(alice, "c1") shouldBe false
    }
})
