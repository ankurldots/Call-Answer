package com.callanswer.app

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.callanswer.app.recording.PlaybackController
import com.callanswer.app.recording.PlaybackState
import com.callanswer.app.sms.SmsIntentLauncher
import com.callanswer.app.tts.DirectoryPiperVoiceLocator
import com.callanswer.app.tts.FallbackPhrase
import com.callanswer.app.tts.FallbackPhrases
import com.callanswer.app.tts.SherpaPiperSynthesizer
import com.callanswer.core.gate.GateInfoRenderer
import com.callanswer.core.gate.selectGate
import com.callanswer.core.intent.RuleIntentClassifier
import com.callanswer.core.language.ScriptLanguageDetector
import com.callanswer.core.model.DefaultGates
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.model.Language
import com.callanswer.core.model.LocalTimeHm
import com.callanswer.core.model.VoiceGender
import com.callanswer.core.report.SpamReportBuilder
import com.callanswer.core.report.TraiReport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Task 34.2: the offline features run with no network (Requirements 2.9, 7.15, 8.11, 11.15, 12.13, 14.22).
 *
 * Run with airplane mode on (see tools/run_airplane_suite.sh). Every test first asserts that the device
 * has no validated internet connection, so a run with the network up fails instead of passing falsely.
 * Tests that need on-device assets (Piper voices in files/tts-voices) are skipped, not passed, when
 * those assets are absent.
 */
class AirplaneModeSuiteTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Before
    fun networkIsOff() {
        // `-e requireOffline false` runs the suite online as a smoke check; that run is NOT airplane-mode evidence.
        if (InstrumentationRegistry.getArguments().getString("requireOffline") == "false") return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        assertFalse("Device is online; enable airplane mode (and keep Wi-Fi off) before running", online)
    }

    // 2.9: classification offline
    @Test
    fun classifiesIntentsOffline() {
        val c = RuleIntentClassifier()
        assertEquals(IntentCategory.SOLICITATION, c.classify("loan offer, also a parcel delivery").category)
        assertEquals(IntentCategory.DELIVERY, c.classify("parcel delivery, also a loan offer").category)
        assertEquals(IntentCategory.UNKNOWN, c.classify("hello").category)
        val gate = c.classify("main delivery boy hoon, kaun sa gate se aaun")
        assertEquals(IntentCategory.DELIVERY, gate.category)
        assertTrue(gate.gateQuery)
        val hi = c.classify("कौन से गेट से आऊं, पार्सल है")
        assertEquals(IntentCategory.DELIVERY, hi.category)
        assertTrue(hi.gateQuery)
    }

    // 2.9: language detection offline
    @Test
    fun detectsLanguageOffline() {
        val d = ScriptLanguageDetector.withBundledLexicon()
        assertEquals(Language.HI, d.detect("आप कौन बोल रहे हैं"))
        assertEquals(Language.EN, d.detect("who is calling please"))
        assertEquals(Language.HI, d.detect("aap kaun ho"))
    }

    // 11.15: gate info offline, inside and outside the Night_Window
    @Test
    fun rendersGateInfoOffline() {
        val day = selectGate(LocalTimeHm(12, 0), DefaultGates)
        val night = selectGate(LocalTimeHm(23, 30), DefaultGates)
        val en = GateInfoRenderer.render(day, Language.EN, includeWindowTimes = false)
        val hi = GateInfoRenderer.render(night, Language.HI, includeWindowTimes = true)
        assertTrue(en, en.startsWith("Gate "))
        assertTrue(hi, hi.contains("गेट नंबर"))
        assertTrue(hi, hi.contains("22:30") || hi.contains("10:30"))
        assertTrue(en != hi)
    }

    // 8.11, 7.15: bundled Piper voices of both genders synthesize with no network
    @Test
    fun synthesizesWithBundledVoicesForBothGenders() = runBlocking {
        val root = File(context.filesDir, "tts-voices")
        val locator = DirectoryPiperVoiceLocator(root)
        val tts = SherpaPiperSynthesizer(locator)
        try {
            for (gender in VoiceGender.entries) {
                assumeTrue("Piper $gender voices not installed in ${root.path}", tts.hasVoices(gender))
                for ((lang, text) in listOf(Language.EN to "Please leave the parcel at the gate.", Language.HI to "कृपया पार्सल गेट पर रख दीजिए।")) {
                    val r = tts.synthesize(text, lang, gender)
                    assertTrue("$gender $lang produced no audio", r.pcm.size > r.sampleRate / 4)
                    assertTrue(r.voice.bundled)
                    assertTrue(r.pcm.any { it.toInt() != 0 })
                }
            }
        } finally {
            tts.release()
        }
    }

    // 8.11: pre-rendered fallback phrases load from assets with no synthesis
    @Test
    fun loadsFallbackWavs() {
        val phrases = FallbackPhrases(context.assets)
        for (lang in Language.entries) for (phrase in FallbackPhrase.entries) {
            val clip = phrases.load(phrase, lang, VoiceGender.FEMALE)
            assertTrue("Missing fallback $lang $phrase", clip != null)
            assertFalse(clip!!.substituted)
            assertTrue(clip.sampleRate >= 8000)
            assertTrue(clip.pcm.size > clip.sampleRate / 4)
        }
        // A male request always gets a clip: the male one, or the female one marked substituted.
        for (lang in Language.entries) for (phrase in FallbackPhrase.entries) {
            val clip = phrases.load(phrase, lang, VoiceGender.MALE)
            assertTrue("No clip at all for MALE $lang $phrase", clip != null)
            if (clip!!.gender != VoiceGender.MALE) assertTrue(clip.substituted)
        }
    }

    // 12.13: a recording in shared storage plays through content:// with no network
    @Test
    fun playsRecordingOffline() {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "airplane_suite_${System.nanoTime()}.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/CallAnswerTest")
        }
        val uri: Uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create a test audio file")
        var controller: PlaybackController? = null
        try {
            resolver.openOutputStream(uri)!!.use { it.write(sineWav(seconds = 2)) }
            instrumentation.runOnMainSync { controller = PlaybackController(context).also { it.play(uri) } }
            val deadline = System.currentTimeMillis() + 5_000
            var state = PlaybackState.IDLE
            while (System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync { state = controller!!.state.value }
                if (state == PlaybackState.ENDED || state == PlaybackState.ERROR) break
                Thread.sleep(100)
            }
            assertEquals(PlaybackState.ENDED, state)
        } finally {
            instrumentation.runOnMainSync { controller?.stop() }
            resolver.delete(uri, null, null)
        }
    }

    // 14.22: the spam report is built and handed to the messaging app without any network
    @Test
    fun buildsSpamReportAndHandsOffOffline() {
        val date = SpamReportBuilder.reportDate(1_700_000_000_000L, java.time.ZoneId.of("Asia/Kolkata"))
        val msg = SpamReportBuilder.build("Unsolicited loan offer call", "+919876543210", date)
        assertEquals("Unsolicited loan offer call, +919876543210, $date", msg.text)
        assertTrue(msg.text.length <= TraiReport.MAX_LENGTH)
        val parsed = SpamReportBuilder.parse(msg.text)
        assertEquals("+919876543210", parsed?.sender)

        // The real launcher builds an ACTION_SENDTO intent to 1909; a messaging app may be absent, which is handled.
        var launched: Boolean? = null
        val launcher: SmsIntentLauncher = com.callanswer.app.sms.AndroidSmsIntentLauncher(context)
        launched = runCatching { launcher.openComposer(msg.text) }.getOrNull()
        assertTrue("openComposer threw instead of returning a result", launched != null)
        // Close the composer if one opened, so later tests are not covered by it.
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
    }

    private fun sineWav(seconds: Int, rate: Int = 16000): ByteArray {
        val n = seconds * rate
        val data = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) data.putShort((Math.sin(2 * Math.PI * 440 * i / rate) * 8000).toInt().toShort())
        val out = ByteArrayOutputStream()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(n * 2)
        out.write(h.array()); out.write(data.array())
        return out.toByteArray()
    }
}
