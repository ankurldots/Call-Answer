package com.callanswer.app.translate

import android.util.Log
import com.callanswer.core.delivery.StepTranslator
import com.callanswer.core.model.Language
import com.callanswer.core.model.QuickReply
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit

/**
 * ML Kit on-device translation (Requirement 9.19). It never downloads a model: when either model of the pair is
 * missing, or anything fails, it returns null and the caller speaks the provided text as is. Blocks the calling
 * thread, so call it off the main thread only.
 */
class MlKitStepTranslator(private val timeoutSeconds: Long = 4) : StepTranslator {
    override fun translate(text: String, from: Language, to: Language): String? = try {
        val src = code(from)
        val dst = code(to)
        if (src == dst || !modelPresent(src) || !modelPresent(dst)) {
            null
        } else {
            val translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build(),
            )
            try {
                Tasks.await(translator.translate(text), timeoutSeconds, TimeUnit.SECONDS)
            } finally {
                translator.close()
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "translation unavailable", t)
        null
    }

    private fun modelPresent(code: String): Boolean {
        val model = TranslateRemoteModel.Builder(code).build()
        return Tasks.await(RemoteModelManager.getInstance().isModelDownloaded(model), timeoutSeconds, TimeUnit.SECONDS)
    }

    private fun code(l: Language) = if (l == Language.HI) TranslateLanguage.HINDI else TranslateLanguage.ENGLISH

    private companion object {
        const val TAG = "MlKitStepTranslator"
    }
}

/** The text of a Quick_Reply in [language]; the other language's text, translated when possible, else as is (9.19). */
fun QuickReply.textFor(language: Language, translator: StepTranslator?): String {
    val (own, other, otherLang) = when (language) {
        Language.EN -> Triple(textEn, textHi, Language.HI)
        Language.HI -> Triple(textHi, textEn, Language.EN)
    }
    if (own.isNotBlank()) return own
    if (other.isBlank()) return own
    return translator?.translate(other, otherLang, language)?.takeIf { it.isNotBlank() } ?: other
}
