package com.callanswer.app.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.callanswer.app.service.NotificationCenter
import com.callanswer.core.voice.SpeakerDecision
import com.callanswer.core.voice.SpeakerPolicy
import kotlinx.coroutines.delay

interface AudioRouteController {
    /**
     * Applies the speaker policy at call answer. Returns true when the speakerphone is on afterwards.
     * Never throws; a failure is notified to the User and the call continues.
     */
    suspend fun enableSpeaker(): Boolean

    /** Restores the voice-call volume saved at [enableSpeaker] and stops headset tracking. */
    fun release()
}

/**
 * Routes call audio to the speaker (requirements 13.1, 13.2, 13.3, 13.16, 13.17).
 *
 * Route order (spike S3): [AudioManager.setSpeakerphoneOn], then [AudioManager.setCommunicationDevice].
 * Each step is verified before the next is tried.
 * Once on, the speaker is never touched again, so a User who turns it off keeps it off.
 */
class AndroidAudioRouteController(
    context: Context,
    private val notifications: NotificationCenter,
    private val speakerSetting: suspend () -> Boolean,
) : AudioRouteController {

    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)

    @Volatile private var headsetConnected = false
    private var savedVolume: Int? = null
    private var callbackRegistered = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshHeadset()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshHeadset()
    }

    override suspend fun enableSpeaker(): Boolean {
        registerCallback()
        refreshHeadset()
        raiseVoiceCallVolume()
        return when (SpeakerPolicy.decide(speakerSetting(), headsetConnected)) {
            SpeakerDecision.LeaveOff -> false
            SpeakerDecision.LeaveOffHeadset -> {
                notifications.speakerphoneHeadset()
                false
            }
            SpeakerDecision.TurnOn -> {
                val on = turnOn()
                if (!on) notifications.speakerphoneNotOn()
                on
            }
        }
    }

    override fun release() {
        if (callbackRegistered) {
            runCatching { audio.unregisterAudioDeviceCallback(callback) }
            callbackRegistered = false
        }
        savedVolume?.let { v -> runCatching { audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, v, 0) } }
        savedVolume = null
    }

    private fun registerCallback() {
        if (callbackRegistered) return
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        callbackRegistered = true
    }

    private fun refreshHeadset() {
        headsetConnected = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isHeadset(it.type) }
    }

    private fun raiseVoiceCallVolume() {
        runCatching {
            if (savedVolume == null) savedVolume = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetVolume(audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)), 0)
        }
    }

    private suspend fun turnOn(): Boolean {
        @Suppress("DEPRECATION")
        if (attempt { audio.isSpeakerphoneOn = true }) return true
        if (Build.VERSION.SDK_INT >= 31 && attempt {
                audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audio.setCommunicationDevice(it) }
            }
        ) return true
        return false
    }

    /** Runs one route step and waits up to [VERIFY_MS] for the speaker to be reported on. */
    private suspend fun attempt(step: () -> Unit): Boolean {
        runCatching(step).onFailure { return false }
        var waited = 0L
        while (true) {
            if (isSpeakerOn()) return true
            if (waited >= VERIFY_MS) return false
            delay(POLL_MS)
            waited += POLL_MS
        }
    }

    private fun isSpeakerOn(): Boolean {
        @Suppress("DEPRECATION")
        if (audio.isSpeakerphoneOn) return true
        return Build.VERSION.SDK_INT >= 31 &&
            audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    companion object {
        const val VOLUME_FRACTION = 0.8
        private const val VERIFY_MS = 250L
        private const val POLL_MS = 50L

        private val HEADSET_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= 31) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

        fun isHeadset(type: Int): Boolean = type in HEADSET_TYPES

        /** 80% of [max], at least 1 when [max] is positive. */
        fun targetVolume(max: Int): Int = if (max <= 0) 0 else maxOf(1, Math.round(max * VOLUME_FRACTION).toInt())
    }
}
