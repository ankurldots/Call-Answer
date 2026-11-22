package com.callanswer.app.cloudtts
import com.callanswer.app.tts.SentenceSplitter
import com.callanswer.core.model.DeliveryStep
import com.callanswer.core.model.Language
import com.callanswer.core.delivery.DeliveryDirectionsRenderer
import kotlinx.coroutines.CancellationException
/** Outcome of [PollyPrefetcher.refresh]. [stoppedBy] is set when a failure ended the run early. */
data class PrefetchResult(val fetched: Int, val alreadyCached: Int, val stoppedBy: PollyException.Kind?)
/**
 * Fetches Polly audio for every sentence of the Delivery_Steps, in both languages, as they will be spoken on a
 * call (placeholders filled, then split into sentences exactly as [com.callanswer.app.tts.synthesizeSentences] does).
 */
class PollyPrefetcher(
    private val client: PollyClient,
    private val cache: PollyAudioCache,
) {
    fun sentences(steps: List<DeliveryStep>, language: Language, mapLocation: String?): List<String> =
        DeliveryDirectionsRenderer.render(steps, language, mapLocation, "", null)
            .drop(1)
            .filter { it.isNotBlank() }
            .flatMap { SentenceSplitter.split(it) }
            .distinct()
    suspend fun refresh(steps: List<DeliveryStep>, mapLocation: String?): PrefetchResult {
        val wanted = Language.entries.flatMap { lang -> sentences(steps, lang, mapLocation).map { lang to it } }
        var fetched = 0
        var cached = 0
        for ((lang, text) in wanted) {
            if (cache.has(text, lang)) { cached++; continue }
            try {
                cache.put(text, lang, client.fetch(text, lang))
                fetched++
            } catch (e: CancellationException) {
                throw e
            } catch (e: PollyException) {
                return PrefetchResult(fetched, cached, e.kind)
            }
        }
        cache.retainOnly(wanted.map { (lang, text) -> cache.key(text, lang) }.toSet())
        return PrefetchResult(fetched, cached, null)
    }
}
