package com.callanswer.spike

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared spike log. Appends timestamped lines to one text file that can be exported. */
object SpikeLog {
    private const val FILE_NAME = "spike-log.txt"
    private val lock = Any()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (file == null) {
                val dir = context.applicationContext.getExternalFilesDir(null)
                    ?: context.applicationContext.filesDir
                file = File(dir, FILE_NAME)
            }
        }
    }

    /** Writes `<timestamp> [check] message`. [check] is e.g. "S0" to "S8". */
    fun log(check: String, message: String) {
        val line = "${fmt.format(Date())} [$check] $message"
        android.util.Log.i("Spike", line)
        synchronized(lock) {
            try {
                file?.appendText(line + "\n")
            } catch (e: Exception) {
                android.util.Log.e("Spike", "log write failed", e)
            }
        }
    }

    fun readAll(): String = synchronized(lock) {
        file?.takeIf { it.exists() }?.readText() ?: ""
    }

    fun clear() = synchronized(lock) { file?.delete(); Unit }

    fun logFile(): File? = synchronized(lock) { file }

    /** Builds a share intent for the log file. Returns null if nothing has been logged. */
    fun exportIntent(context: Context): Intent? {
        val f = logFile()?.takeIf { it.exists() && it.length() > 0 } ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Spike log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Export spike log")
    }
}
