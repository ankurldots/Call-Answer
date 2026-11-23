package com.callanswer.agent.exotel

import com.callanswer.agent.audio.StreamFormat
import com.callanswer.agent.sonic.SonicEvent
import com.callanswer.core.tenant.UserId
import kotlinx.coroutines.flow.Flow

/** Why a Voice_Stream ended, as seen by the handler (plan decision 7). */
enum class StreamEnd {
    /** `stop` with reason `callended`. */
    CALLER_HUNG_UP,

    /** `stop` with reason `stopped`, a dropped socket, or no media for the media timeout. */
    AUDIO_INTERRUPTED,
}

/** One call as the stream handler sees it: Caller audio in, Sonic events out. */
interface StreamCall {
    /** Events of the Sonic conversation (Agent audio at 24 kHz, transcripts, barge-in). */
    val events: Flow<SonicEvent>

    /** Caller audio, 16 kHz PCM16 mono little-endian. */
    fun callerAudio(pcm16k: ByteArray)

    /** Exotel confirmed that the audio before the mark `name` has played. */
    fun onMark(name: String)

    fun onDtmf(digit: String)

    /** Called exactly once when the stream is over. Finalizes the call. */
    suspend fun end(reason: StreamEnd)
}

/** Creates the call for a verified stream. Returns null when the call cannot be started. */
fun interface StreamCallFactory {
    suspend fun create(userId: UserId, callSid: String, from: String?, to: String?, format: StreamFormat): StreamCall?
}
