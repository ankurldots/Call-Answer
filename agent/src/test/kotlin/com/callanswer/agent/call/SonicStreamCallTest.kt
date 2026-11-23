package com.callanswer.agent.call

import com.callanswer.agent.audio.StreamFormat
import com.callanswer.agent.exotel.ExotelEvent
import com.callanswer.agent.exotel.FakeCalls
import com.callanswer.agent.exotel.StreamCall
import com.callanswer.agent.exotel.StreamCallFactory
import com.callanswer.agent.exotel.StreamEnd
import com.callanswer.agent.exotel.media
import com.callanswer.agent.exotel.sendEvent
import com.callanswer.agent.exotel.startEvent
import com.callanswer.agent.exotel.waitUntil
import com.callanswer.agent.exotel.withStreamApp
import com.callanswer.agent.exotel.wsClient
import com.callanswer.agent.sonic.SonicStream
import com.callanswer.agent.sonic.SonicStreamFactory
import com.callanswer.agent.sonic.SonicStreamListener
import com.callanswer.agent.store.CostCounter
import com.callanswer.agent.store.CostNotifier
import com.callanswer.agent.store.DirectoryRepository
import com.callanswer.agent.store.InMemoryBlobStore
import com.callanswer.agent.store.InMemoryItemStore
import com.callanswer.agent.store.RecordingSigner
import com.callanswer.agent.store.RecordingStore
import com.callanswer.agent.store.SettingsStore
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.model.EndReason
import com.callanswer.core.model.Outcome
import com.callanswer.core.model.Speaker
import com.callanswer.core.tenant.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArrayList

private class FakeSonic : SonicStreamFactory, SonicStream {
    val sent = CopyOnWriteArrayList<String>()
    @Volatile var listener: SonicStreamListener? = null
    override fun open(listener: SonicStreamListener): SonicStream { this.listener = listener; return this }
    override fun send(event: String) { sent.add(event) }
    override fun close() {}
    fun emit(name: String, body: String) = listener!!.onEvent("""{"event":{"$name":$body}}""")
    fun say(role: String, id: String, text: String) {
        emit("contentStart", """{"contentId":"$id","type":"TEXT","role":"$role"}""")
        emit("textOutput", """{"contentId":"$id","role":"$role","content":"$text"}""")
    }
}

class SonicStreamCallTest : FunSpec({
    val alice = UserId("alice-sub")
    val noNotify = object : CostNotifier {
        override suspend fun limit80Reached(totalPaise: Long) {}
        override suspend fun limit100Reached(totalPaise: Long) {}
    }

    class Setup {
        val items = InMemoryItemStore()
        val blobs = InMemoryBlobStore()
        val sonic = FakeSonic()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = UserScopedRepository(alice, items, blobs, pause = {})
        val factory = SonicStreamCallFactory(
            settings = SettingsStore(items, DirectoryRepository(items)),
            repoFor = { UserScopedRepository(it, items, blobs, pause = {}) },
            finalizerFor = { u, r -> CallFinalizer(u, r, CostCounter(r, noNotify), RecordingStore(blobs, RecordingSigner { k, _ -> k })) },
            sonic = sonic,
            scope = scope,
        )
    }

    suspend fun Setup.stored(call: StreamCall) = (call as SonicStreamCall).result!!.let { r ->
        r.stored shouldBe true
        repo.getCall(r.entry.startedAtEpochMs, r.entry.id)!!
    }

    test("a scripted Exotel sequence reaches a stored CallLogEntry with the caller number") {
        val s = Setup()
        var captured: StreamCall? = null
        val capturing = StreamCallFactory { u, sid, from, to, fmt -> s.factory.create(u, sid, from, to, fmt).also { captured = it } }
        withStreamApp(capturing) { env ->
            wsClient().webSocket(env.path(env.token()), request = { header(HttpHeaders.Authorization, env.basic) }) {
                sendEvent(startEvent(from = "+919811111111"))
                repeat(3) { sendEvent(media()) }
                waitUntil { s.sonic.listener != null } shouldBe true
                s.sonic.say("USER", "c1", "I have a parcel")
                s.sonic.say("ASSISTANT", "c2", "Please use gate 3")
                kotlinx.coroutines.delay(150)
                sendEvent(ExotelEvent.Mark("s1", "m1"))
                sendEvent(ExotelEvent.Stop("s1", "CA1", "callended"))
            }
            waitUntil { (captured as? SonicStreamCall)?.result != null } shouldBe true
            val entry = s.stored(captured!!)
            entry.callerNumber shouldBe "+919811111111"
            entry.outcome shouldBe Outcome.CALLER_HUNG_UP
            entry.endReason shouldBe EndReason.CALLER_HUNG_UP
            entry.transcript.map { it.speaker to it.text } shouldBe
                listOf(Speaker.CALLER to "I have a parcel", Speaker.AGENT to "Please use gate 3")
            // 3 x 160 samples at 8 kHz became 16 kHz audio for Sonic
            s.sonic.sent.any { it.contains("audioInput") } shouldBe true
        }
        s.scope.cancel()
    }

    test("a blank caller number is recorded as unknown") {
        val s = Setup()
        val call = s.factory.create(alice, "CA1", "  ", "+919800000001", StreamFormat())!!
        call.end(StreamEnd.CALLER_HUNG_UP)
        s.stored(call).callerNumber shouldBe null
        s.scope.cancel()
    }

    test("stopped and a dropped socket are recorded as AUDIO_INTERRUPTED") {
        val s = Setup()
        val call = s.factory.create(alice, "CA2", "+919811111111", "+919800000001", StreamFormat())!!
        call.end(StreamEnd.AUDIO_INTERRUPTED)
        val entry = s.stored(call)
        entry.outcome shouldBe Outcome.INTERRUPTED
        entry.endReason shouldBe EndReason.AUDIO_INTERRUPTED
        s.scope.cancel()
    }
})
