package com.callanswer.app.cloudtts
import com.callanswer.core.delivery.StepTranslator
import com.callanswer.core.model.Settings
import kotlinx.coroutines.CancellationException
/** Outcome of [PollyPrefetcher.refresh]. [stoppedBy] is set when a failure ended the run early. */
data class PrefetchResult(val fetched: Int, val alreadyCached: Int, val stoppedBy: PollyException.Kind?)
/**
 * Keeps the on-device Polly audio in line with the settings: fetches every sentence of [SpokenTextCatalog] that is
 * not cached, then deletes cached audio no longer needed. A failure stops the run and keeps what was fetched.
 */
class PollyPrefetcher(
    private val client: PollyClient,
    private val cache: PollyAudioCache,
) {
    suspend fun refresh(settings: Settings, translator: StepTranslator? = null): PrefetchResult {
        val wanted = SpokenTextCatalog.sentences(settings, translator)
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
