package com.callanswer.app.tts

import android.content.res.AssetManager
import com.callanswer.core.model.Language
import com.callanswer.core.model.VoiceGender
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class FallbackPhrase(val index: Int) { FALLBACK(1), FILLER(2), PLEASE_WAIT(3), NOBODY_AVAILABLE(4) }

/** PCM of a bundled phrase. [substituted] is true when no clip of the requested gender is bundled. */
class FallbackClip(val pcm: ShortArray, val sampleRate: Int, val gender: VoiceGender, val substituted: Boolean)

/**
 * Pre-rendered phrases in `assets/fallback/<lang>_<gender>_<NN>.wav` (16-bit mono PCM). They need no
 * runtime TTS, so they work when synthesis fails (Requirements 7.14, 8.9, 8.11).
 */
class FallbackPhrases(private val open: (String) -> java.io.InputStream) {
    constructor(assets: AssetManager) : this({ assets.open(it) })

    /** The clip for [gender] and [language]; the other gender (with `substituted = true`) if that one is missing; null if neither exists. */
    fun load(phrase: FallbackPhrase, language: Language, gender: VoiceGender): FallbackClip? {
        read(phrase, language, gender)?.let { (pcm, rate) -> return FallbackClip(pcm, rate, gender, false) }
        val other = if (gender == VoiceGender.MALE) VoiceGender.FEMALE else VoiceGender.MALE
        return read(phrase, language, other)?.let { (pcm, rate) -> FallbackClip(pcm, rate, other, true) }
    }

    private fun read(phrase: FallbackPhrase, language: Language, gender: VoiceGender): Pair<ShortArray, Int>? {
        val name = "fallback/%s_%s_%02d.wav".format(language.name.lowercase(), gender.name.lowercase(), phrase.index)
        val bytes = try { open(name).use { it.readBytes() } } catch (_: FileNotFoundException) { return null }
        return parseWav(bytes)
    }

    companion object {
        /** Parses a canonical RIFF/WAVE file with 16-bit mono PCM. */
        fun parseWav(bytes: ByteArray): Pair<ShortArray, Int> {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Not a WAV file" }
            var pos = 12
            var rate = 0
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = bb.getInt(pos + 4)
                if (id == "fmt ") {
                    require(bb.getShort(pos + 10).toInt() == 1 && bb.getShort(pos + 22).toInt() == 16) { "Need 16-bit mono PCM" }
                    rate = bb.getInt(pos + 12)
                } else if (id == "data") {
                    val n = minOf(size, bytes.size - pos - 8) / 2
                    val pcm = ShortArray(n)
                    bb.position(pos + 8)
                    bb.asShortBuffer().get(pcm)
                    return pcm to rate
                }
                pos += 8 + size + (size and 1)
            }
            throw IllegalArgumentException("No data chunk")
        }
    }
}
