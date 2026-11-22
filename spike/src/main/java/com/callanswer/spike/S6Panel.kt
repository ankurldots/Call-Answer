package com.callanswer.spike

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Spike S6: transfer Path A and Path B. Five tries per path, each logged pass/fail.
 *
 * Path A: placeCall(tel:target) during an active call, accessibility click on Samsung Merge, 30 s no-answer timer,
 * end the second call and click Swap/Resume. Run with mobile data off (state is logged with every step).
 *
 * Path B: Takeover_Alert on channel `takeover_alert` (IMPORTANCE_HIGH, category CALL, full-screen intent, sound,
 * vibration), an alert tone through the S3 [AudioPlayer], and a "Take over" action that reaches
 * [SpikeForegroundService] from the lock screen. Test with and without full-screen intent access.
 */
object S6Panel {
    private const val TAG = "S6"
    private const val NO_ANSWER_MS = 30_000L
    private const val TRIES = 5
    private val main = Handler(Looper.getMainLooper())
    private val tries = mutableMapOf("A" to 0, "B" to 0)
    private val passes = mutableMapOf("A" to 0, "B" to 0)
    private var noAnswerTimer: Runnable? = null
    private var secondCallPlacedAt = 0L

    fun build(activity: Activity, root: LinearLayout, body: TextView) {
        root.addView(TextView(activity).apply {
            text = "Path A. Start a real call and answer it first. Run with mobile data off."
        })
        val target = EditText(activity).apply {
            hint = "Transfer target number"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        root.addView(target)
        root.addView(btn(activity, "A1 Place second call (starts 30 s no-answer timer)") {
            placeSecondCall(activity, target.text.toString().trim(), body)
        })
        root.addView(btn(activity, "A2 Click Merge (target answered)") {
            cancelTimer()
            clickByWords(activity, "A-merge", listOf("merge", "मर्ज"), body)
        })
        root.addView(btn(activity, "A3 End second call, then click Swap/Resume") {
            cancelTimer()
            endSecondCallAndResume(activity, body)
        })
        root.addView(btn(activity, "A pass") { record("A", true, body) })
        root.addView(btn(activity, "A fail") { record("A", false, body) })

        root.addView(TextView(activity).apply {
            text = "\nPath B. Lock the phone within the delay to test the lock screen."
            textSize = 14f
        })
        root.addView(btn(activity, "B1 Post Takeover_Alert now") {
            postAlert(activity, body)
        })
        root.addView(btn(activity, "B1 Post Takeover_Alert in 10 s (lock the phone)") {
            body.text = "Alert in 10 s. Lock the phone now."
            main.postDelayed({ postAlert(activity.applicationContext, body) }, 10_000)
        })
        root.addView(btn(activity, "B2 Open full-screen-intent access settings") {
            openFsiSettings(activity)
        })
        root.addView(btn(activity, "B3 Log alert state (channel, FSI access, DND)") {
            body.text = alertState(activity).also { SpikeLog.log(TAG, "state $it") }
        })
        root.addView(btn(activity, "B pass") { record("B", true, body) })
        root.addView(btn(activity, "B fail") { record("B", false, body) })
        root.addView(btn(activity, "Clear alert and stop tone") { S6Alert.cancel(activity) })

        body.text = "Tries are logged per path (target $TRIES each). ${summary()}\n${dataState(activity)}"
        SpikeLog.log(TAG, "panel opened; ${dataState(activity)}")
    }

    private fun has(ctx: Context, perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun placeSecondCall(ctx: Context, number: String, body: TextView) {
        if (number.isEmpty()) { body.text = "Enter the target number"; return }
        if (!has(ctx, Manifest.permission.CALL_PHONE)) {
            SpikeLog.log(TAG, "A placeCall: CALL_PHONE not granted")
            body.text = "CALL_PHONE not granted"
            return
        }
        val tm = ctx.getSystemService(TelecomManager::class.java)
        secondCallPlacedAt = SystemClock.elapsedRealtime()
        SpikeLog.log(TAG, "A placeCall tel:$number during active call; ${dataState(ctx)}; inCall=${tm.isInCall}")
        try {
            tm.placeCall(Uri.fromParts("tel", number, null), null)
            SpikeLog.log(TAG, "A placeCall accepted; 30 s no-answer timer started")
            body.text = "Second call placed. Timer 30 s. When the target answers press A2."
        } catch (e: Exception) {
            SpikeLog.log(TAG, "A placeCall FAILED: ${e.javaClass.simpleName}: ${e.message}")
            body.text = "placeCall failed: $e"
            return
        }
        cancelTimer()
        noAnswerTimer = Runnable {
            SpikeLog.log(TAG, "A no-answer timer fired after ${SystemClock.elapsedRealtime() - secondCallPlacedAt} ms")
            endSecondCallAndResume(ctx, body)
        }.also { main.postDelayed(it, NO_ANSWER_MS) }
    }

    private fun cancelTimer() {
        noAnswerTimer?.let { main.removeCallbacks(it) }
        noAnswerTimer = null
    }

    private fun endSecondCallAndResume(ctx: Context, body: TextView) {
        val tm = ctx.getSystemService(TelecomManager::class.java)
        val ended = try {
            if (has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) tm.endCall() else false
        } catch (e: Exception) {
            SpikeLog.log(TAG, "A endCall threw $e"); false
        }
        SpikeLog.log(TAG, "A endCall (second call) result=$ended")
        main.postDelayed({
            clickByWords(ctx, "A-swap/resume", listOf("swap", "resume", "unhold", "स्वैप", "फिर से शुरू"), body)
        }, 1_000)
    }

    /** Finds a node whose text, description or view id contains one of [words] and clicks it. Dumps nodes when absent. */
    private fun clickByWords(ctx: Context, step: String, words: List<String>, body: TextView) {
        val svc = S2Bridge.accessibilityContext as? AccessibilityService
        if (svc == null) {
            SpikeLog.log(TAG, "$step accessibility service not connected")
            body.text = "Accessibility service not connected"
            return
        }
        val root = svc.rootInActiveWindow
        if (root == null) {
            SpikeLog.log(TAG, "$step no active window")
            body.text = "No active window. Bring the in-call screen to the front."
            return
        }
        val dump = mutableListOf<String>()
        var found: AccessibilityNodeInfo? = null
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val s = "${n.text ?: ""} ${n.contentDescription ?: ""} ${n.viewIdResourceName ?: ""}".lowercase()
            dump += "cls=${n.className} id=${n.viewIdResourceName} text=${n.text} desc=${n.contentDescription} clickable=${n.isClickable}"
            if (found == null && words.any { s.contains(it) }) found = n
            for (i in 0 until n.childCount) n.getChild(i)?.let(stack::add)
        }
        if (found == null) {
            SpikeLog.log(TAG, "$step button not found pkg=${root.packageName} nodes=${dump.size}")
            dump.take(60).forEach { SpikeLog.log(TAG, "node $it") }
            body.text = "$step: button not found in ${root.packageName} (nodes dumped to log)"
            return
        }
        var target: AccessibilityNodeInfo? = found
        while (target != null && !target.isClickable) target = target.parent
        val ok = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        SpikeLog.log(TAG, "$step click pkg=${root.packageName} id=${found.viewIdResourceName} desc=${found.contentDescription} text=${found.text} clicked=$ok")
        body.text = "$step: clicked=$ok"
    }

    private fun postAlert(ctx: Context, body: TextView) {
        val t0 = SystemClock.elapsedRealtime()
        val state = S6Alert.post(ctx)
        body.text = "Alert posted (${SystemClock.elapsedRealtime() - t0} ms). $state"
    }

    private fun openFsiSettings(ctx: Context) {
        val i = if (Build.VERSION.SDK_INT >= 34) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        }
        try { ctx.startActivity(i) } catch (e: Exception) { SpikeLog.log(TAG, "open FSI settings failed: $e") }
    }

    private fun alertState(ctx: Context): String {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val fsi = if (Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent().toString() else "n/a (API<34)"
        val ch = nm.getNotificationChannel(S6Alert.CHANNEL_ID)
        return "fullScreenIntentAccess=$fsi channelImportance=${ch?.importance} notificationsEnabled=${nm.areNotificationsEnabled()} " +
            "interruptionFilter=${nm.currentInterruptionFilter}"
    }

    private fun dataState(ctx: Context): String {
        val on = try { Settings.Global.getInt(ctx.contentResolver, "mobile_data", -1) } catch (e: Exception) { -1 }
        return "mobileData=${when (on) { 1 -> "on"; 0 -> "off"; else -> "unknown" }}"
    }

    private fun record(path: String, pass: Boolean, body: TextView) {
        tries[path] = tries.getValue(path) + 1
        if (pass) passes[path] = passes.getValue(path) + 1
        val n = tries.getValue(path)
        SpikeLog.log(TAG, "Path $path try $n/$TRIES ${if (pass) "PASS" else "FAIL"} (passes ${passes[path]}/$n)")
        if (n >= TRIES) SpikeLog.log(TAG, "Path $path RESULT ${passes[path]}/$n passed")
        body.text = summary()
    }

    private fun summary() = "Path A ${passes["A"]}/${tries["A"]} passed, Path B ${passes["B"]}/${tries["B"]} passed"

    private fun btn(ctx: Context, label: String, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}

/** Path B alert: notification plus App-played tone. Cleared by the "Take over" action in [SpikeForegroundService]. */
object S6Alert {
    const val TAG = "S6"
    const val CHANNEL_ID = "takeover_alert"
    const val NOTIF_ID = 4601
    const val ACTION_TAKE_OVER = "com.callanswer.spike.S6_TAKE_OVER"
    private var player: AudioPlayer? = null

    fun post(ctx: Context): String {
        val app = ctx.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "Takeover alert", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 300, 500, 300, 500)
                setSound(
                    Settings.System.DEFAULT_RINGTONE_URI,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }
        val fsiAccess = if (Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent().toString() else "n/a"
        val fsiPi = PendingIntent.getActivity(
            app, 61,
            Intent(app, CheckActivity::class.java).putExtra(CheckActivity.EXTRA_CHECK, "S6")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val takePi = PendingIntent.getService(
            app, 62, Intent(app, SpikeForegroundService::class.java).setAction(ACTION_TAKE_OVER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(null, "Take over", takePi).apply {
            if (Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(false)
        }.build()
        val n = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Takeover needed")
            .setContentText("Caller is waiting. Tap Take over.")
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(fsiPi)
            .setFullScreenIntent(fsiPi, true)
            .addAction(action)
            .build()
        try {
            nm.notify(NOTIF_ID, n)
        } catch (e: Exception) {
            SpikeLog.log(TAG, "B notify FAILED: $e")
        }
        playTone(app)
        val s = "fullScreenIntentAccess=$fsiAccess notificationsEnabled=${nm.areNotificationsEnabled()}"
        SpikeLog.log(TAG, "B Takeover_Alert posted; $s")
        return s
    }

    private fun playTone(ctx: Context) {
        try {
            val p = player ?: AudioPlayer(ctx).also { player = it }
            val ms = p.play("s3/en_01.wav", AudioAttributes.USAGE_VOICE_COMMUNICATION)
            SpikeLog.log(TAG, "B alert tone via S3 AudioPlayer started, ${ms} ms")
        } catch (e: Exception) {
            SpikeLog.log(TAG, "B alert tone FAILED: $e")
        }
    }

    /** Clears the notification and stops the tone. */
    fun cancel(ctx: Context) {
        player?.stop()
        ctx.applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }
}
