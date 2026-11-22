package com.callanswer.app.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.TextView
import com.callanswer.app.R
import com.callanswer.app.CallLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Small pill shown over other apps while Call Answer handles a call. With `SYSTEM_ALERT_WINDOW` granted, a
 * visible overlay window is what lets the app start its foreground service from the background on Android 15+
 * (and keeps the app out of the plain "background" state on earlier versions). It is not focusable and does not
 * block touches outside itself; tapping it opens the Live_Call_Screen.
 */
object CallOverlay {
    private const val TAG = "CallAnswer"

    /** Longest wait for the overlay's first draw before the service is started anyway. */
    const val DRAW_TIMEOUT_MS = 1_000L

    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null

    @Volatile var isShowing = false
        private set
    @Volatile private var firstDraw: CompletableDeferred<Unit>? = null

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * Adds the overlay and waits (at most [DRAW_TIMEOUT_MS]) until it is attached, laid out and has drawn once.
     * Returns false when the permission is missing or the window could not be added.
     */
    suspend fun show(context: Context): Boolean {
        val app = context.applicationContext
        if (!canShow(app)) {
            CallLog.i("overlay not shown: permission not granted")
            return false
        }
        val added = suspendCancellableCoroutine { cont ->
            main.post {
                val ok = runCatching { addOnMain(app) }.onFailure { Log.w(TAG, "overlay add failed", it) }.getOrDefault(false)
                cont.resume(ok)
            }
        }
        if (!added) return false
        val drawn = withTimeoutOrNull(DRAW_TIMEOUT_MS) { firstDraw?.await() } != null
        val v = view
        isShowing = v != null && v.isAttachedToWindow && v.windowToken != null && v.width > 0 && v.height > 0
        CallLog.i("overlay shown: firstDraw=$drawn attached=${v?.isAttachedToWindow} tokenValid=${v?.windowToken != null} " +
            "size=${v?.width}x${v?.height} visible=$isShowing")
        return isShowing
    }

    /** Removes the overlay. Safe to call when it is not showing, from any thread. */
    fun hide(context: Context) {
        val app = context.applicationContext
        main.post {
            val v = view ?: return@post
            view = null
            isShowing = false
            runCatching { app.getSystemService(WindowManager::class.java).removeViewImmediate(v) }
            Log.i(TAG, "overlay removed")
        }
    }

    private fun addOnMain(app: Context): Boolean {
        if (view != null) return true
        val density = app.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val pill = TextView(app).apply {
            text = app.getString(R.string.call_overlay_text)
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(0xE6263238.toInt())
                cornerRadius = dp(16).toFloat()
            }
            setOnClickListener {
                runCatching {
                    app.startActivity(
                        Intent(app, LiveCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
        }
        val drawn = CompletableDeferred<Unit>()
        firstDraw = drawn
        pill.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                drawn.complete(Unit)
                pill.post { runCatching { pill.viewTreeObserver.removeOnDrawListener(this) } }
            }
        })
        app.getSystemService(WindowManager::class.java).addView(pill, params)
        view = pill
        return true
    }
}
