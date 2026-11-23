package com.callanswer.agent.audio

import com.callanswer.agent.Clock
import com.callanswer.agent.exotel.ExotelEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Outbound audio queue towards Exotel: cuts Agent audio (already at the Exotel rate) into fixed chunks,
 * sends them at real-time rate with a bounded send-ahead, sends a `mark` after each utterance, and
 * flushes the queue with exactly one `clear` on an interruption or PII hit.
 *
 * ASSUMPTIONS (spike C-1 not run): chunk size 3200 bytes (a multiple of 320, Exotel minimum) and a 300 ms
 * send-ahead (config: EXOTEL_CHUNK_BYTES, EXOTEL_SEND_AHEAD_MS; sizes below 3200 need EXOTEL_ALLOW_SMALL_CHUNKS=true).
 * If the spike requires small chunks, construct with e.g. chunkBytes = 320 and maxSendAheadMillis = 100.
 *
 * Time comes from the injected [Clock]; nothing here sleeps except [run], whose delay is also injectable,
 * so tests drive [pump] directly with a manual clock. All public methods are thread-safe.
 *
 * C-5: callers invoke [interrupt] on Sonic `userSpeechStart` or `INTERRUPTED`; audio offered later for the
 * interrupted content is dropped until [endUtterance] for that content.
 */
class OutboundPacer(
    private val streamSid: String?,
    private val format: StreamFormat,
    private val clock: Clock,
    private val sink: (ExotelEvent) -> Unit,
    val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
    val maxSendAheadMillis: Int = DEFAULT_SEND_AHEAD_MILLIS,
) {
    init {
        require(chunkBytes > 0 && chunkBytes % FRAME_UNIT_BYTES == 0) { "chunkBytes must be a multiple of $FRAME_UNIT_BYTES" }
        require(maxSendAheadMillis > 0) { "maxSendAheadMillis must be positive" }
    }

    private sealed interface Item {
        class Chunk(val bytes: ByteArray) : Item
        class Mark(val name: String) : Item
    }

    private val lock = Any()
    private val queue = ArrayDeque<Item>()
    private var partial = ByteArray(0)
    private var openContent: String? = null
    private val dropped = HashSet<String>()
    private var playEndMillis = 0L
    private var chunkCounter = 0L
    private var markCounter = 0L

    /** Number of `clear` events sent so far. */
    @Volatile var clearCount: Int = 0
        private set

    /** Duration of one chunk in milliseconds at the stream's sample rate. */
    val chunkMillis: Int get() = chunkBytes * 1000 / (format.sampleRate * 2)

    /** Queues PCM16 bytes of [contentId]; dropped when that content was interrupted. Odd trailing byte is ignored. */
    fun offer(contentId: String, pcm: ByteArray) {
        synchronized(lock) {
            if (contentId in dropped) return
            openContent = contentId
            val evenLength = pcm.size - pcm.size % 2
            val all = partial + pcm.copyOf(evenLength)
            var pos = 0
            while (all.size - pos >= chunkBytes) {
                queue.addLast(Item.Chunk(all.copyOfRange(pos, pos + chunkBytes)))
                pos += chunkBytes
            }
            partial = all.copyOfRange(pos, all.size)
        }
    }

    /**
     * Ends the utterance: pads the last partial chunk with silence and queues a `mark`.
     * For an interrupted content it only lifts the drop and sends no mark.
     */
    fun endUtterance(contentId: String, markName: String? = null) {
        synchronized(lock) {
            if (dropped.remove(contentId)) {
                if (openContent == contentId) openContent = null
                return
            }
            if (partial.isNotEmpty()) {
                queue.addLast(Item.Chunk(partial.copyOf(chunkBytes)))
                partial = ByteArray(0)
            }
            if (openContent == contentId) openContent = null
            queue.addLast(Item.Mark(markName ?: "utt-${++markCounter}"))
        }
    }

    /**
     * Drops queued audio and sends one `clear` when audio is queued or may still be playing at Exotel.
     * The content being produced (or [contentId]) is dropped until its [endUtterance].
     * Returns true when a `clear` was sent. The call is synchronous, so it takes effect immediately.
     */
    fun interrupt(contentId: String? = null): Boolean {
        synchronized(lock) {
            (contentId ?: openContent)?.let { id ->
                if (id == openContent || contentId != null) dropped.add(id)
            }
            val outstanding = queue.isNotEmpty() || partial.isNotEmpty() || playEndMillis > clock.nowMillis()
            queue.clear()
            partial = ByteArray(0)
            playEndMillis = 0L
            if (!outstanding) return false
            clearCount++
            sink(ExotelEvent.Clear(streamSid))
            return true
        }
    }

    /** Milliseconds of audio sent to Exotel that has not played yet (by the real-time estimate). */
    fun sendAheadMillis(): Long = synchronized(lock) { (playEndMillis - clock.nowMillis()).coerceAtLeast(0) }

    /** Bytes queued and not yet sent (excluding the unfinished partial chunk). */
    fun queuedBytes(): Int = synchronized(lock) { queue.sumOf { (it as? Item.Chunk)?.bytes?.size ?: 0 } }

    /**
     * Sends every queued item allowed by the send-ahead limit. Returns the milliseconds to wait before the
     * next call can send more, or null when the queue is empty.
     */
    fun pump(): Long? {
        synchronized(lock) {
            while (true) {
                val head = queue.firstOrNull() ?: return null
                if (head is Item.Mark) {
                    queue.removeFirst()
                    sink(ExotelEvent.Mark(streamSid, head.name))
                    continue
                }
                head as Item.Chunk
                val now = clock.nowMillis()
                val ahead = (playEndMillis - now).coerceAtLeast(0)
                val millis = head.bytes.size * 1000L / (format.sampleRate * 2)
                if (ahead > 0 && ahead + millis > maxSendAheadMillis) {
                    return ahead + millis - maxSendAheadMillis
                }
                queue.removeFirst()
                playEndMillis = now + ahead + millis
                chunkCounter++
                sink(ExotelEvent.Media(streamSid, PcmCodec.encodeBase64(head.bytes), chunk = chunkCounter))
            }
        }
    }

    /** Pump loop for production; cancel the coroutine to stop. [idleMillis] is the poll interval when idle. */
    suspend fun run(idleMillis: Long = 10) {
        while (coroutineContext.isActive) {
            val wait = pump()
            delay((wait ?: idleMillis).coerceAtLeast(1))
        }
    }

    companion object {
        const val FRAME_UNIT_BYTES = 320
        const val DEFAULT_CHUNK_BYTES = 3200
        const val DEFAULT_SEND_AHEAD_MILLIS = 300
    }
}
