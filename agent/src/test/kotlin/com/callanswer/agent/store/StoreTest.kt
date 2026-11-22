package com.callanswer.agent.store

import com.callanswer.core.model.CallLogEntry
import com.callanswer.core.model.EndReason
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.model.Language
import com.callanswer.core.model.Outcome
import com.callanswer.core.model.RecordingInfo
import com.callanswer.core.model.RecordingStatus
import com.callanswer.core.model.Speaker
import com.callanswer.core.model.Utterance
import com.callanswer.core.model.VoiceGender
import com.callanswer.core.tenant.E164
import com.callanswer.core.tenant.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.assertions.throwables.shouldThrow

private fun entry(id: String, start: Long, transcript: List<Utterance> = emptyList()) = CallLogEntry(
    id = id, callerNumber = "+919812345678", startedAtEpochMs = start, zoneId = "Asia/Kolkata", durationSec = 12,
    language = Language.EN, intent = IntentCategory.DELIVERY, outcome = Outcome.CALLER_HUNG_UP,
    endReason = EndReason.CALLER_HUNG_UP, transcript = transcript, gateStatements = emptyList(),
    voiceGender = VoiceGender.FEMALE, voiceSubstitutions = emptyList(), stallingDurationSec = null,
    takeovers = emptyList(), interruption = null, recording = RecordingInfo(RecordingStatus.PENDING), notes = emptyList(),
)

class StoreTest : FunSpec({
    val alice = UserId("alice-1")
    val bob = UserId("bob-2")

    test("conditional put, update with ADD, and transactions behave like DynamoDB") {
        val s = InMemoryItemStore()
        val item = StoreItem("USER#a", "X", mapOf("v" to AttrValue.N(1)))
        s.put(item, Condition.NotExists)
        shouldThrow<ConditionFailedException> { s.put(item, Condition.NotExists) }
        shouldThrow<ConditionFailedException> { s.put(item, Condition.AttrEquals("v", AttrValue.N(2))) }
        s.put(item.copy(attrs = mapOf("v" to AttrValue.N(2))), Condition.AttrEquals("v", AttrValue.N(1)))
        s.update(ItemKey("USER#a", "C"), ItemUpdate(add = mapOf("t" to 5))).num("t") shouldBe 5
        s.update(ItemKey("USER#a", "C"), ItemUpdate(add = mapOf("t" to 7))).num("t") shouldBe 12
        shouldThrow<TransactionCancelledException> {
            s.transact(listOf(TransactOp.Put(StoreItem("USER#a", "N1")), TransactOp.Put(item, Condition.NotExists)))
        }
        s.get(ItemKey("USER#a", "N1")) shouldBe null
    }

    test("queries honor key conditions, order, limit, and cursor") {
        val s = InMemoryItemStore()
        listOf("CALL#1", "CALL#2", "CALL#3", "DEVICE#1").forEach { s.put(StoreItem("USER#a", it)) }
        s.put(StoreItem("USER#b", "CALL#9"))
        val p1 = s.query(StoreQuery("USER#a", SortCondition.BeginsWith("CALL#"), forward = false, limit = 2))
        p1.items.map { it.sk } shouldContainExactly listOf("CALL#3", "CALL#2")
        val p2 = s.query(StoreQuery("USER#a", SortCondition.BeginsWith("CALL#"), forward = false, limit = 2, startAfter = p1.lastKey))
        p2.items.map { it.sk } shouldContainExactly listOf("CALL#1")
        p2.lastKey shouldBe null
        s.query(StoreQuery("USER#a", SortCondition.Between("CALL#2", "CALL#3"))).items.size shouldBe 2
    }

    test("call round trip, newest first, user isolation") {
        val store = InMemoryItemStore(); val blobs = InMemoryBlobStore()
        val a = UserScopedRepository(alice, store, blobs); val b = UserScopedRepository(bob, store, blobs)
        a.putCall(entry("c1", 1_700_000_000_000)) shouldBe true
        a.putCall(entry("c2", 1_700_000_100_000)) shouldBe true
        b.putCall(entry("c3", 1_700_000_200_000)) shouldBe true
        a.listCalls(10).entries.map { it.id } shouldContainExactly listOf("c2", "c1")
        a.getCall(1_700_000_000_000, "c1") shouldBe entry("c1", 1_700_000_000_000)
        a.findCall("c3") shouldBe null
        a.callRefsStartedAtOrBefore(1_700_000_000_000).map { it.callId } shouldContainExactly listOf("c1")
        a.deleteCall(1_700_000_000_000, "c1") shouldBe true
        a.findCall("c1") shouldBe null
    }

    test("a transcript over 300 KB goes to S3 under users/<sub>/ and comes back on detail") {
        val store = InMemoryItemStore(); val blobs = InMemoryBlobStore()
        val repo = UserScopedRepository(alice, store, blobs)
        val big = listOf(Utterance(Speaker.CALLER, "x".repeat(310 * 1024), Language.EN, 0, 1))
        repo.putCall(entry("big", 1_700_000_000_000, big)) shouldBe true
        blobs.listKeys("") shouldContainExactly listOf("users/alice-1/transcripts/big.json")
        repo.getCall(1_700_000_000_000, "big")!!.transcript shouldBe big
        repo.listCalls(5).entries.single().transcript shouldBe emptyList()
        repo.deleteCall(1_700_000_000_000, "big")
        blobs.listKeys("") shouldContainExactly emptyList()
    }

    test("a failing write is retried 3 times at 1 second, then NOT_LOGGED and a redacted line") {
        val store = InMemoryItemStore().also { it.beforeWrite = { throw IllegalStateException("secret +919812345678") } }
        val pauses = mutableListOf<Long>(); val lines = mutableListOf<String>(); val alerts = mutableListOf<String>()
        val repo = UserScopedRepository(alice, store, InMemoryBlobStore(), { u, c -> alerts += "${u.sub}/$c" }, { lines += it }, { pauses += it })
        repo.putCall(entry("c1", 1_700_000_000_000)) shouldBe false
        store.writeCount shouldBe 4
        pauses shouldContainExactly listOf(1000L, 1000L, 1000L)
        alerts shouldContainExactly listOf("alice-1/c1")
        lines.single().shouldNotContain("+91").shouldNotContain("secret")
    }

    test("devices keep at most five, replacing the oldest") {
        val repo = UserScopedRepository(alice, InMemoryItemStore(), InMemoryBlobStore())
        (1..6).forEach { repo.putDevice(DeviceRecord("d$it", "t$it", it.toLong())) }
        repo.listDevices().map { it.deviceId }.sorted() shouldContainExactly listOf("d2", "d3", "d4", "d5", "d6")
    }

    test("settings-free records, cost counter, open markers, and the directory") {
        val store = InMemoryItemStore(); val blobs = InMemoryBlobStore()
        val a = UserScopedRepository(alice, store, blobs); val b = UserScopedRepository(bob, store, blobs)
        a.createProfileIfAbsent(1) shouldBe true
        a.createProfileIfAbsent(2) shouldBe false
        b.createProfileIfAbsent(3) shouldBe true
        a.addCost("2025-01", 40) shouldBe 40
        a.addCost("2025-01", 2) shouldBe 42
        a.getCost("2025-01")!!.totalPaise shouldBe 42
        a.putOpenMarker("c1", 500)
        b.putOpenMarker("c2", 100)
        val dir = DirectoryRepository(store)
        dir.listUsers().toSet() shouldBe setOf(alice, bob)
        dir.listOpenCalls().map { it.callId } shouldContainExactly listOf("c2", "c1")
        val n = E164("+919812345678")
        dir.assign(n, alice, 1, "admin") shouldBe true
        dir.assign(n, bob, 2, "admin") shouldBe false
        dir.assign(E164("+919800000000"), alice, 2, "admin") shouldBe false
        dir.lookupNumber(n)!!.userId shouldBe alice
        dir.listAssignments().map { it.e164 } shouldContainExactly listOf(n)
        dir.unassign(alice) shouldBe true
        dir.lookupNumber(n) shouldBe null
        a.setProfileStatus("DEACTIVATED")
        // the listing reads the status attribute from the index
        dir.listActiveUsers().size shouldBe 1
        a.deleteOpenMarker("c1")
        dir.listOpenCalls().map { it.callId } shouldContainExactly listOf("c2")
    }
})
