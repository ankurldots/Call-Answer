package com.callanswer.app

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Logcat logger with tag `CallAnswer`. In debug builds every line is also appended to
 * `<external files>/traces/trace-<sessionId>.log` once [begin] has been called, so the lines survive logcat
 * rotation. Lines logged before [begin] (e.g. by the screener) are kept in a small buffer and written first.
 */
object CallLog {
    const val TAG = "CallAnswer"
    private const val MAX_PENDING = 200
    private val lock = Any()
    private val pending = ArrayDeque<String>()
    private var file: File? = null

    fun i(msg: String) {
        Log.i(TAG, msg)
        trace("I", msg)
    }

    fun w(msg: String, t: Throwable? = null) {
        if (t != null) Log.w(TAG, msg, t) else Log.w(TAG, msg)
        trace("W", if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg)
    }

    /** Starts the trace file of a call. Debug builds only. */
    fun begin(context: Context, sessionId: String) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            val dir = runCatching { File(context.getExternalFilesDir(null) ?: context.filesDir, "traces").apply { mkdirs() } }
                .getOrNull() ?: return
            val f = File(dir, "trace-$sessionId.log")
            file = f
            runCatching { f.appendText(pending.joinToString("") { it + "\n" }) }
            pending.clear()
        }
    }

    /** Stops writing to the trace file. */
    fun end() {
        synchronized(lock) { file = null }
    }

    private fun trace(level: String, msg: String) {
        if (!BuildConfig.DEBUG) return
        val line = "${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $level $msg"
        synchronized(lock) {
            val f = file
            if (f == null) {
                pending.addLast(line)
                while (pending.size > MAX_PENDING) pending.removeFirst()
            } else {
                runCatching { f.appendText(line + "\n") }
            }
        }
    }
}
