package com.callanswer.app.push

import android.content.Context
import android.content.Intent
import com.callanswer.app.MainActivity

/** Asks [MainActivity] to open the live call screen of [callId], and to take the call over when [takeOver] is set. */
data class LiveCallRequest(val callId: String, val takeOver: Boolean) {
    fun toIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(if (takeOver) ACTION_TAKE_OVER else ACTION_OPEN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(EXTRA_CALL_ID, callId)

    companion object {
        const val ACTION_OPEN = "com.callanswer.app.action.OPEN_LIVE_CALL"
        const val ACTION_TAKE_OVER = "com.callanswer.app.action.TAKE_OVER"
        const val EXTRA_CALL_ID = "callId"

        fun from(intent: Intent?): LiveCallRequest? {
            val action = intent?.action
            if (action != ACTION_OPEN && action != ACTION_TAKE_OVER) return null
            val id = intent.getStringExtra(EXTRA_CALL_ID)?.takeIf { it.isNotBlank() } ?: return null
            return LiveCallRequest(id, takeOver = action == ACTION_TAKE_OVER)
        }
    }
}
