package com.callanswer.app.push

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Registers the FCM token of this device with `PUT /devices/{deviceId}`. The device id is a random id kept on the
 * device. Without `google-services.json` Firebase is not initialised and every call does nothing.
 */
class PushRegistration(
    context: Context,
    private val put: suspend (deviceId: String, token: String) -> Unit,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("push", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also { id -> prefs.edit { putString(KEY_DEVICE_ID, id) } }

    val firebaseAvailable: Boolean get() = FirebaseApp.getApps(app).isNotEmpty()

    /** Registers [token]. Returns false when the server could not be reached or the User is signed out. */
    suspend fun register(token: String): Boolean = try {
        put(deviceId, token)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        runCatching { Log.w(TAG, "device registration failed: ${t.javaClass.simpleName}") }
        false
    }

    /** Fetches the current token and registers it. Call after sign-in and at app start. */
    suspend fun registerCurrent(): Boolean {
        if (!firebaseAvailable) return false
        val token = currentToken() ?: return false
        return register(token)
    }

    private suspend fun currentToken(): String? = suspendCancellableCoroutine { cont ->
        runCatching {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t -> cont.resume(t.result.takeIf { t.isSuccessful }) }
        }.onFailure { cont.resume(null) }
    }

    private companion object {
        const val KEY_DEVICE_ID = "deviceId"
        const val TAG = "PushRegistration"
    }
}
