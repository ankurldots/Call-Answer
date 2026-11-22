package com.callanswer.core.stalling

/**
 * Chunk-wise PII check for streamed Agent text (Requirements 12.8, 12.9, 12.13).
 *
 * Chunks of one content (utterance) are appended in order and the accumulated text is checked
 * with [PiiGuard.containsProtected], so a Protected_Term split across chunks is reported by
 * the chunk that completes it (letter case, spacing and punctuation inside digit sequences are
 * handled by the underlying guard). Because the check is on the text received so far, a term
 * that is only a prefix of a longer word is reported conservatively. Use one instance per
 * content and call [reset] when a new content starts. Not thread safe.
 */
class StreamingPiiGuard(private val guard: PiiGuard) {

    private val buffer = StringBuilder()

    /** Becomes true on the first match and stays true until [reset]. */
    var matched: Boolean = false
        private set

    /** Appends [chunk] and returns true if the text so far contains a protected value. */
    fun accept(chunk: String): Boolean {
        if (matched) return true
        buffer.append(chunk)
        matched = guard.containsProtected(buffer.toString())
        return matched
    }

    /** Starts a new content. */
    fun reset() {
        buffer.setLength(0)
        matched = false
    }
}
