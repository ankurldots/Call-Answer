package com.callanswer.app.cloudtts
import com.callanswer.core.model.Language
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
/** On-device store of Polly audio, one raw PCM16 file per (language, sentence). */
class PollyAudioCache(private val dir: File) {
    fun key(text: String, language: Language): String =
        MessageDigest.getInstance("SHA-256").digest("$VOICE|${language.name}|$text".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    fun get(text: String, language: Language): ShortArray? {
        val f = File(dir, key(text, language) + EXT)
        if (!f.isFile) return null
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (bytes.size < 2) return null
        return ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
    }
    fun has(text: String, language: Language): Boolean = File(dir, key(text, language) + EXT).isFile
    fun put(text: String, language: Language, pcm: ShortArray) {
        dir.mkdirs()
        val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(pcm) }.array()
        val tmp = File(dir, "tmp-${System.nanoTime()}")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(File(dir, key(text, language) + EXT))) tmp.delete()
    }
    /** Deletes every cached file whose key is not in [keep]. */
    fun retainOnly(keep: Set<String>) {
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(EXT) && f.name.removeSuffix(EXT) !in keep) f.delete()
        }
    }
    fun clear() = retainOnly(emptySet())
    private companion object {
        const val VOICE = "kajal"
        const val EXT = ".pcm"
    }
}
