package com.callanswer.spike

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Spike S5: Samsung recording folder probe.
 * The User picks the Samsung call recording folder (SAF, persisted permission). When a call ends the
 * app lists the folder (name, MIME type, size, last-modified, delay after call end) and logs it.
 * The User then records the file name pattern and which sides are audible in the note field.
 */
class S5Activity : Activity() {
    private lateinit var folderView: TextView
    private lateinit var resultView: TextView
    private lateinit var note: EditText
    private lateinit var caller: CheckBox
    private lateinit var agent: CheckBox
    private lateinit var user: CheckBox

    private var treeUri: Uri? = null
    private var callEndMs: Long? = null
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var callback: Any? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpikeLog.init(this)
        title = "S5 Samsung recording folder probe"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        folderView = TextView(this).apply { textSize = 14f }
        resultView = TextView(this).apply { textSize = 13f }
        note = EditText(this).apply {
            hint = "Note: file name pattern, remarks"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        caller = CheckBox(this).apply { text = "Caller audible" }
        agent = CheckBox(this).apply { text = "Agent audible" }
        user = CheckBox(this).apply { text = "User audible" }

        root.addView(button("Pick recording folder") { pickFolder() })
        root.addView(folderView)
        root.addView(button("Mark call end now (manual)") { markCallEnd("manual") })
        root.addView(button("List files now") { listAndLog() })
        root.addView(resultView)
        root.addView(note)
        root.addView(caller)
        root.addView(agent)
        root.addView(user)
        root.addView(button("Log note and sides") { logNote() })
        setContentView(ScrollView(this).apply { addView(root) })

        treeUri = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE, null)?.let(Uri::parse)
            ?.takeIf { uri -> contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission } }
        showFolder()
        registerCallEndWatcher()
    }

    override fun onDestroy() {
        unregisterCallEndWatcher()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            SpikeLog.log("S5", "folder pick cancelled")
            return
        }
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).apply()
        treeUri = uri
        SpikeLog.log("S5", "folder picked uri=$uri path=${folderPath(uri)} persisted=true")
        showFolder()
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        startActivityForResult(intent, REQ_TREE)
    }

    private fun showFolder() {
        val uri = treeUri
        folderView.text = if (uri == null) "No folder picked" else "Folder: ${folderPath(uri)}"
    }

    /** Tree document ID, e.g. `primary:Recordings/Call`: volume plus relative path. */
    private fun folderPath(uri: Uri): String = try {
        DocumentsContract.getTreeDocumentId(uri)
    } catch (e: Exception) {
        uri.toString()
    }

    private fun markCallEnd(source: String) {
        callEndMs = System.currentTimeMillis()
        SpikeLog.log("S5", "call end marked source=$source at ${fmt.format(Date(callEndMs!!))}")
        resultView.text = "Call end marked ($source) at ${fmt.format(Date(callEndMs!!))}. Recording files may appear a few seconds later; tap List files now (repeat to see the delay)."
    }

    private fun listAndLog() {
        val tree = treeUri
        if (tree == null) {
            resultView.text = "Pick a folder first"
            return
        }
        val end = callEndMs
        val listedAt = System.currentTimeMillis()
        val rows = mutableListOf<String>()
        try {
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val modified = if (c.isNull(3)) null else c.getLong(3)
                    val delay = if (modified != null && end != null) "${(modified - end) / 1000.0} s" else "n/a"
                    val modText = modified?.let { fmt.format(Date(it)) } ?: "n/a"
                    rows += "name=${c.getString(0)} mime=${c.getString(1)} size=${if (c.isNull(2)) "n/a" else c.getLong(2)} modified=$modText delayAfterCallEnd=$delay modifiedMs=$modified"
                }
            }
        } catch (e: Exception) {
            SpikeLog.log("S5", "list failed: $e")
            resultView.text = "List failed: $e"
            return
        }
        // Newest first so the file for the last call is on top.
        val sorted = rows.sortedByDescending { it.substringAfter("modifiedMs=").toLongOrNull() ?: 0L }
        SpikeLog.log(
            "S5",
            "listing folder=${folderPath(tree)} at ${fmt.format(Date(listedAt))} " +
                "sinceCallEnd=${end?.let { "${(listedAt - it) / 1000.0} s" } ?: "n/a"} files=${sorted.size}"
        )
        sorted.forEach { SpikeLog.log("S5", "file $it") }
        resultView.text = "Files: ${sorted.size}\n" + sorted.take(20).joinToString("\n\n") { it.substringBefore(" modifiedMs=") }
    }

    private fun logNote() {
        val sides = listOfNotNull(
            "Caller".takeIf { caller.isChecked },
            "Agent".takeIf { agent.isChecked },
            "User".takeIf { user.isChecked },
        )
        val path = treeUri?.let(::folderPath) ?: "none"
        SpikeLog.log(
            "S5",
            "result folder=$path audibleSides=${sides.ifEmpty { listOf("none") }.joinToString(",")} " +
                "note=${note.text.toString().replace('\n', ' ')}"
        )
        resultView.text = "Logged: folder=$path sides=${sides.joinToString(",")}"
    }

    // Call end detection: transition from OFFHOOK to IDLE.
    private fun onCallState(state: Int) {
        if (lastState == TelephonyManager.CALL_STATE_OFFHOOK && state == TelephonyManager.CALL_STATE_IDLE) {
            runOnUiThread { markCallEnd("telephony") }
        }
        lastState = state
    }

    private fun registerCallEndWatcher() {
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            SpikeLog.log("S5", "READ_PHONE_STATE not granted; use manual call end button")
            return
        }
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= 31) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = onCallState(state)
            }
            tm.registerTelephonyCallback(mainExecutor, cb)
            callback = cb
        } else {
            @Suppress("DEPRECATION")
            val l = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = onCallState(state)
            }
            @Suppress("DEPRECATION")
            tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            callback = l
        }
    }

    private fun unregisterCallEndWatcher() {
        val cb = callback ?: return
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= 31 && cb is TelephonyCallback) {
            tm.unregisterTelephonyCallback(cb)
        } else if (cb is PhoneStateListener) {
            @Suppress("DEPRECATION")
            tm.listen(cb, PhoneStateListener.LISTEN_NONE)
        }
        callback = null
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    companion object {
        private const val REQ_TREE = 51
        private const val PREFS = "s5"
        private const val KEY_TREE = "tree_uri"
    }
}
