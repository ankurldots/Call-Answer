package com.callanswer.spike

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/**
 * Spike S3: Agent speech audible to the Caller, and speaker route.
 * Place a call, answer it, then play a sentence with each audio usage at 60 to 100 percent call volume.
 * The User reports whether the Caller heard it. Stop latency (pause + flush) is logged per stop.
 * Speaker route attempts, in order: setSpeakerphoneOn, setCommunicationDevice, Samsung Speaker button click.
 */
class S3Activity : Activity() {
    private lateinit var player: AudioPlayer
    private lateinit var audio: AudioManager
    private lateinit var status: TextView
    private lateinit var lang: RadioGroup
    private lateinit var usageGroup: RadioGroup
    private lateinit var clip: EditText
    private lateinit var heard: CheckBox
    private lateinit var banner: TextView
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var volume = 80

    private val usages = listOf(
        "VOICE_COMMUNICATION" to AudioAttributes.USAGE_VOICE_COMMUNICATION,
        "ASSISTANT" to AudioAttributes.USAGE_ASSISTANT,
        "MEDIA" to AudioAttributes.USAGE_MEDIA,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpikeLog.init(this)
        title = "S3 Agent speech and speaker route"
        player = AudioPlayer(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        banner = TextView(this).apply { textSize = 14f; setPadding(16, 16, 16, 16) }
        root.addView(banner)
        root.addView(label("Language"))
        lang = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(context).apply { text = "Hindi"; id = ID_HI })
            addView(RadioButton(context).apply { text = "English"; id = ID_EN })
            check(ID_HI)
        }
        root.addView(lang)
        clip = EditText(this).apply { hint = "Sentence number 1 to 10"; setText("1"); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        root.addView(clip)
        root.addView(label("Audio usage"))
        usageGroup = RadioGroup(this)
        usages.forEachIndexed { i, (name, _) ->
            usageGroup.addView(RadioButton(this).apply { text = name; id = ID_USAGE + i })
        }
        usageGroup.check(ID_USAGE)
        root.addView(usageGroup)
        root.addView(label("Call volume"))
        val volRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(60, 70, 80, 90, 100).forEach { p ->
            volRow.addView(Button(this).apply {
                text = "$p"
                isAllCaps = false
                setOnClickListener {
                    volume = p
                    val idx = AudioPlayer.setCallVolumePercent(this@S3Activity, p)
                    SpikeLog.log("S3", "call volume set to $p% index=$idx")
                    show("Call volume $p% ($idx)")
                }
            })
        }
        root.addView(volRow)
        root.addView(button("Play") { play() })
        root.addView(button("Stop (measure latency)") { stop() })
        heard = CheckBox(this).apply { text = "Caller heard it clearly" }
        root.addView(heard)
        root.addView(button("Log Caller result for last play") {
            SpikeLog.log("S3", "result ${lastPlay} callerHeard=${heard.isChecked}")
            show("Logged: $lastPlay callerHeard=${heard.isChecked}")
        })
        root.addView(label("Speaker route"))
        root.addView(button("1. setSpeakerphoneOn(true)") { routeSpeakerphone() })
        root.addView(button("2. setCommunicationDevice(speaker)") { routeCommunicationDevice() })
        root.addView(button("Speaker off and clear") { clearRoute() })
        status = TextView(this).apply { textSize = 13f }
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
        SpikeLog.log("S3", "opened mode=${modeName()} speakerphoneOn=${audio.isSpeakerphoneOn}")
    }

    override fun onResume() {
        super.onResume()
        banner.text = S1Settings.bannerText(this)
        banner.setBackgroundColor(if (S1Settings.mode(this) == S1Mode.AUTO_ANSWER_AND_END) 0xFFFFCDD2.toInt() else 0xFFC8E6C9.toInt())
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player.stop()
        super.onDestroy()
    }

    private var lastPlay = "none"

    private fun play() {
        val prefix = if (lang.checkedRadioButtonId == ID_HI) "hi" else "en"
        val n = (clip.text.toString().toIntOrNull() ?: 1).coerceIn(1, 10)
        val (usageName, usage) = usages[usageGroup.checkedRadioButtonId - ID_USAGE]
        val asset = "s3/%s_%02d.wav".format(prefix, n)
        val vol = AudioPlayer.setCallVolumePercent(this, volume)
        val durMs = player.play(asset, usage) {
            SpikeLog.log("S3", "playback finished $asset")
            runOnUiThread { show("Finished $asset") }
        }
        lastPlay = "asset=$asset usage=$usageName volume=$volume% index=$vol"
        SpikeLog.log("S3", "play $lastPlay durationMs=$durMs mode=${modeName()} speakerphoneOn=${audio.isSpeakerphoneOn}")
        show("Playing $lastPlay")
    }

    private fun stop() {
        val ms = player.stopMeasured()
        if (ms < 0) {
            show("Nothing playing")
        } else {
            SpikeLog.log("S3", "stop latency pause+flush=%.2f ms (%s)".format(ms, lastPlay))
            show("Stopped: pause+flush %.2f ms".format(ms))
        }
    }

    private fun routeSpeakerphone() {
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = true
        @Suppress("DEPRECATION")
        val on = audio.isSpeakerphoneOn
        val dev = if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice?.let { "${it.type}" } else "n/a"
        SpikeLog.log("S3", "route 1 setSpeakerphoneOn(true) -> isSpeakerphoneOn=$on communicationDeviceType=$dev mode=${modeName()}")
        show("Route 1: isSpeakerphoneOn=$on communicationDeviceType=$dev. Check the in-call screen and whether the speaker is really on.")
    }

    private fun routeCommunicationDevice() {
        if (Build.VERSION.SDK_INT < 31) {
            SpikeLog.log("S3", "route 2 setCommunicationDevice unavailable (API ${Build.VERSION.SDK_INT})")
            show("Route 2 needs API 31+")
            return
        }
        val speaker = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (speaker == null) {
            SpikeLog.log("S3", "route 2 no builtin speaker among ${audio.availableCommunicationDevices.map { it.type }}")
            show("No built-in speaker communication device")
            return
        }
        val ok = audio.setCommunicationDevice(speaker)
        val now = audio.communicationDevice?.type
        SpikeLog.log("S3", "route 2 setCommunicationDevice(speaker) -> accepted=$ok communicationDeviceType=$now mode=${modeName()}")
        show("Route 2: accepted=$ok current type=$now. Check whether the speaker is really on.")
    }

    private fun clearRoute() {
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = false
        if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
        SpikeLog.log("S3", "route cleared")
        show("Route cleared")
    }

    private fun modeName() = when (audio.mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
        else -> audio.mode.toString()
    }

    private fun show(s: String) { status.text = s }

    private fun label(s: String) = TextView(this).apply { text = s; textSize = 16f; setPadding(0, 24, 0, 8) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    companion object {
        private const val ID_HI = 9001
        private const val ID_EN = 9002
        private const val ID_USAGE = 9100
    }
}
