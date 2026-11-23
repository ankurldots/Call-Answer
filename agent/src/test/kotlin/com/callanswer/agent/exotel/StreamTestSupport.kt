package com.callanswer.agent.exotel

import com.callanswer.agent.Clock
import com.callanswer.agent.Config
import com.callanswer.agent.SystemClock
import com.callanswer.agent.agentModule
import com.callanswer.agent.audio.PcmCodec
import com.callanswer.agent.audio.StreamFormat
import com.callanswer.agent.sonic.SonicEvent
import com.callanswer.agent.store.InMemoryBlobStore
import com.callanswer.agent.store.InMemoryItemStore
import com.callanswer.agent.store.NumberAssignmentService
import com.callanswer.agent.store.TenantResolver
import com.callanswer.agent.store.UnassignedCallLog
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.tenant.UserId
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class TestClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMillis() = now
}

/** A [StreamCall] that records what the handler gives it and plays back scripted Sonic events. */
class FakeStreamCall : StreamCall {
    val channel = Channel<SonicEvent>(Channel.UNLIMITED)
    override val events: Flow<SonicEvent> = channel.receiveAsFlow()
    val audio = CopyOnWriteArrayList<ByteArray>()
    val marks = CopyOnWriteArrayList<String>()
    val ends = CopyOnWriteArrayList<StreamEnd>()
    override fun callerAudio(pcm16k: ByteArray) { audio.add(pcm16k) }
    override fun onMark(name: String) { marks.add(name) }
    override fun onDtmf(digit: String) {}
    override suspend fun end(reason: StreamEnd) { ends.add(reason) }
}

class FakeCalls(val call: FakeStreamCall = FakeStreamCall()) : StreamCallFactory {
    val created = CopyOnWriteArrayList<String>()
    override suspend fun create(userId: UserId, callSid: String, from: String?, to: String?, format: StreamFormat): StreamCall? {
        created.add("${userId.sub}/$callSid")
        return call
    }
}

class StreamEnv(val auth: ExotelAuth, val clock: TestClock) {
    val basic = "Basic " + Base64.getEncoder().encodeToString("exotel:pw".toByteArray())
    fun token(callSid: String = "CA1", userSub: String = "alice-sub") = auth.createCallToken(callSid, userSub)
    fun path(token: String) = "/stream/$token?sample-rate=8000"
}

fun newStreamEnv(): StreamEnv {
    val clock = TestClock()
    return StreamEnv(ExotelAuth({ mapOf("web" to "exotel:pw", "tok" to "key-1")[it] }, "web", "tok", clock), clock)
}

const val ALICE_NUMBER = "+919800000001"
const val BOB_NUMBER = "+919800000002"

fun startEvent(callSid: String = "CA1", to: String = ALICE_NUMBER, from: String? = "+919811111111", rate: Int = 8000) =
    ExotelEvent.Start("s1", callSid, from, to, StreamFormat(sampleRate = rate), 1)

fun media(samples: Int = 160) = ExotelEvent.Media("s1", PcmCodec.encodeBase64(ShortArray(samples) { 1000 }), 1)

suspend fun DefaultClientWebSocketSession.sendEvent(e: ExotelEvent) = send(Frame.Text(ExotelMessages.encode(e)))

/** Reads decoded Exotel events until [ms] pass without a frame. */
suspend fun DefaultClientWebSocketSession.collectFor(ms: Long): List<ExotelEvent> {
    val out = mutableListOf<ExotelEvent>()
    while (true) {
        val f = withTimeoutOrNull(ms) { incoming.receiveCatching() } ?: break
        val frame = f.getOrNull() ?: break
        if (frame is Frame.Text) ExotelMessages.decode(frame.readText())?.let(out::add)
    }
    return out
}

suspend fun waitUntil(timeoutMs: Long = 3000, cond: () -> Boolean): Boolean {
    var waited = 0L
    while (!cond() && waited < timeoutMs) { delay(20); waited += 20 }
    return cond()
}

/** An app with alice and bob assigned to +919800000001/2 and the stream route mounted on [factory]. */
suspend fun withStreamApp(
    factory: StreamCallFactory,
    env: StreamEnv = newStreamEnv(),
    mediaTimeoutMs: Long = 10_000,
    block: suspend ApplicationTestBuilder.(StreamEnv) -> Unit,
) {
    val store = InMemoryItemStore()
    val resolver = TenantResolver(store, UnassignedCallLog(store, { 1_700_000_000_000L }), { 1_700_000_000_000L })
    val svc = NumberAssignmentService(store, resolver) { 1_700_000_000_000L }
    for ((sub, number) in listOf("alice-sub" to ALICE_NUMBER, "bob-sub" to BOB_NUMBER)) {
        val u = UserId(sub)
        UserScopedRepository(u, store, InMemoryBlobStore()).createProfileIfAbsent(1)
        svc.assign(number, u, "admin")
    }
    val handler = ExotelStreamHandler(resolver, factory, SystemClock, mediaTimeoutMs = mediaTimeoutMs)
    testApplication {
        application { agentModule(Config.fromEnv(emptyMap()), exotel = { exotelStreamRoute(env.auth, handler) }) }
        block(env)
    }
}

fun ApplicationTestBuilder.wsClient() = createClient { install(WebSockets) }
