package com.callanswer.agent.exotel

import com.callanswer.agent.Clock
import com.callanswer.agent.SystemClock
import com.callanswer.agent.audio.FrameValidator
import com.callanswer.agent.audio.OutboundPacer
import com.callanswer.agent.audio.PcmCodec
import com.callanswer.agent.audio.Resampler
import com.callanswer.agent.sonic.SonicEvent
import com.callanswer.agent.store.TenantResolution
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Sonic outputs 24 kHz, the Sonic input is 16 kHz. */
private const val SONIC_OUT_RATE = 24_000
private const val SONIC_IN_RATE = 16_000

/**
 * One Exotel media WebSocket. States: `connected`, `start` (binding checks, call creation), `media`, `mark`,
 * `dtmf`, `stop`. Exotel audio goes through [FrameValidator] and a resampler to 16 kHz Sonic input; Sonic audio
 * goes through a resampler to the stream rate into the [OutboundPacer]. [StreamCall.end] runs exactly once.
 *
 * Without a media event for [mediaTimeoutMs] (counted from the connection and from each media event) the
 * stream is treated as interrupted.
 */
class ExotelStreamHandler(
    private val resolver: com.callanswer.agent.store.TenantResolver,
    private val factory: StreamCallFactory,
    private val clock: Clock = SystemClock,
    private val chunkBytes: Int = OutboundPacer.DEFAULT_CHUNK_BYTES,
    private val sendAheadMs: Int = OutboundPacer.DEFAULT_SEND_AHEAD_MILLIS,
    private val mediaTimeoutMs: Long = 10_000,
) {
    /** Runs until the stream ends. [claims] come from the token that the handshake verified. */
    suspend fun run(ws: WebSocketSession, claims: CallTokenClaims) {
        var call: StreamCall? = null
        var reason = StreamEnd.AUDIO_INTERRUPTED
        try {
            coroutineScope {
                val outbox = Channel<String>(Channel.UNLIMITED)
                launch {
                    try {
                        for (text in outbox) ws.send(Frame.Text(text))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        outbox.close()
                    }
                }
                val jobs = mutableListOf<Job>()
                try {
                    var lastMediaAt = clock.nowMillis()
                    var validator: FrameValidator? = null
                    var inResampler: Resampler? = null
                    loop@ while (true) {
                        val wait = (mediaTimeoutMs - (clock.nowMillis() - lastMediaAt)).coerceAtLeast(1)
                        val received = withTimeoutOrNull(wait) { ws.incoming.receiveCatching() } ?: break
                        val frame = received.getOrNull() ?: break
                        if (frame !is Frame.Text) continue
                        when (val ev = ExotelMessages.decode(frame.readText())) {
                            is ExotelEvent.Start -> {
                                if (call != null) continue@loop
                                val userMatches = ev.callSid == claims.callSid &&
                                    (resolver.resolve(ev.to) as? TenantResolution.Resolved)?.userId?.sub == claims.userId
                                if (!userMatches) {
                                    ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "start does not match token"))
                                    break@loop
                                }
                                val created = factory.create(
                                    com.callanswer.core.tenant.UserId(claims.userId), claims.callSid, ev.from, ev.to, ev.format,
                                )
                                if (created == null) {
                                    ws.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "call not started"))
                                    break@loop
                                }
                                call = created
                                lastMediaAt = clock.nowMillis()
                                val rate = ev.format.sampleRate
                                validator = FrameValidator(ev.format)
                                inResampler = Resampler(rate, SONIC_IN_RATE)
                                val pacer = OutboundPacer(
                                    ev.streamSid, ev.format, clock,
                                    { out -> outbox.trySend(ExotelMessages.encode(out)) },
                                    chunkBytes, sendAheadMs,
                                )
                                jobs += launch { pacer.run() }
                                jobs += launch { pipeSonic(created, pacer, Resampler(SONIC_OUT_RATE, rate)) }
                            }
                            is ExotelEvent.Media -> {
                                val c = call ?: continue@loop
                                lastMediaAt = clock.nowMillis()
                                val samples = validator?.accept(ev.payload) ?: continue@loop
                                val resampled = inResampler!!.process(samples)
                                if (resampled.isNotEmpty()) c.callerAudio(PcmCodec.samplesToBytes(resampled))
                            }
                            is ExotelEvent.Mark -> call?.onMark(ev.name)
                            is ExotelEvent.Dtmf -> call?.onDtmf(ev.digit)
                            is ExotelEvent.Stop -> {
                                if (ev.reason.equals("callended", ignoreCase = true)) reason = StreamEnd.CALLER_HUNG_UP
                                break@loop
                            }
                            is ExotelEvent.Connected, is ExotelEvent.Clear, null -> Unit
                        }
                    }
                } finally {
                    jobs.forEach { it.cancel() }
                    outbox.close()
                }
            }
        } finally {
            call?.let { withContext(NonCancellable) { it.end(reason) } }
        }
    }

    private suspend fun pipeSonic(call: StreamCall, pacer: OutboundPacer, outResampler: Resampler) {
        var utterance = 0
        var offered = false
        call.events.collect { e ->
            when (e) {
                is SonicEvent.AgentAudio -> {
                    val even = e.pcm.copyOf(e.pcm.size - e.pcm.size % 2)
                    val samples = outResampler.process(PcmCodec.bytesToSamples(even))
                    if (samples.isNotEmpty()) {
                        pacer.offer("u$utterance", PcmCodec.samplesToBytes(samples))
                        offered = true
                    }
                }
                is SonicEvent.AgentTranscript -> if (offered) {
                    pacer.endUtterance("u$utterance")
                    utterance++
                    offered = false
                }
                is SonicEvent.UserSpeechStarted -> {
                    // Keep the id: Agent audio that is still in flight belongs to the interrupted utterance and must
                    // stay dropped until its transcript or the Interrupted event. Without audio in flight only
                    // the audio already queued or buffered at Exotel is cleared.
                    if (offered) pacer.interrupt("u$utterance") else pacer.interrupt()
                }
                is SonicEvent.Interrupted -> {
                    pacer.interrupt("u$utterance")
                    utterance++
                    offered = false
                }
                else -> Unit
            }
        }
    }
}
