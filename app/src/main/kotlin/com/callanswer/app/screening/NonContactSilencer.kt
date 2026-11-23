package com.callanswer.app.screening

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.edit
import com.callanswer.app.MainActivity
import com.callanswer.app.R
import com.callanswer.core.tenant.PhoneNumbers

/** The phone numbers saved in the User's contacts, read on this device only. */
fun interface ContactNumberSource {
    /** Raw contact numbers, or null when they cannot be read (no `READ_CONTACTS`, or the lookup failed). */
    suspend fun numbers(): List<String>?
}

/** Decides whether a number is a Known_Caller, comparing normalized numbers (Requirement 34). */
object ContactMatcher {
    /**
     * True when [rawNumber] equals one of [contactNumbers] after `PhoneNumbers.normalize`, so that formatting characters,
     * a leading plus, a trunk zero and the default country code do not matter. A number that does not parse (for example
     * a short code) matches only a contact number with the same digits. A hidden number (null or blank) never matches.
     */
    fun isKnown(rawNumber: String?, contactNumbers: List<String>): Boolean {
        val key = key(rawNumber) ?: return false
        return contactNumbers.any { key(it) == key }
    }

    private fun key(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        PhoneNumbers.normalize(raw)?.let { return it.value }
        val stripped = raw.filter { it.isDigit() || it == '+' }
        return stripped.takeIf { it.isNotEmpty() }?.let { "raw:$it" }
    }
}

/**
 * Decides whether the Screening_Observer silences the ringer (Requirement 34). Silences only while the setting is on, the
 * contacts were read, the caller is not a Repeat_Caller and the number is not in the contacts. Any failure to read the
 * contacts leaves the ringer on and calls [onContactsUnavailable].
 */
class NonContactSilencer(
    private val enabled: () -> Boolean,
    private val contacts: ContactNumberSource,
    private val isRepeatCaller: suspend (String?) -> Boolean,
    private val onContactsUnavailable: () -> Unit,
) {
    /** Whether the setting is on, so the caller can skip any work while it is off. */
    fun isEnabled(): Boolean = enabled()

    suspend fun shouldSilence(rawNumber: String?): Boolean {
        if (!enabled()) return false
        val numbers = try {
            contacts.numbers()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (numbers == null) {
            onContactsUnavailable()
            return false
        }
        if (ContactMatcher.isKnown(rawNumber, numbers)) return false
        val repeat = try {
            isRepeatCaller(rawNumber)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The Ringing_Alert may depend on this check, so a failure leaves the ringer on.
            return false
        }
        return !repeat
    }
}

/** Reads the contact numbers with the `READ_CONTACTS` permission; null without it or when the query fails. */
class AndroidContactNumbers internal constructor(
    private val hasPermission: () -> Boolean,
    private val query: () -> List<String>?,
) : ContactNumberSource {
    constructor(context: Context) : this(
        hasPermission = { context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED },
        query = {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null,
            )?.use { c ->
                val out = ArrayList<String>(c.count)
                while (c.moveToNext()) c.getString(0)?.let(out::add)
                out
            }
        },
    )

    override suspend fun numbers(): List<String>? {
        if (!hasPermission()) return null
        return try {
            query()
        } catch (e: RuntimeException) {
            logWarning("contacts lookup failed: ${e.javaClass.simpleName}")
            null
        }
    }
}

/** Logs a warning; a missing logger (plain JVM unit tests) must never change the outcome. */
internal fun logWarning(message: String) {
    runCatching { Log.w("CallAnswer", message) }
}

/** A single boolean kept on this device. */
internal interface BooleanStore {
    fun get(key: String, default: Boolean): Boolean
    fun set(key: String, value: Boolean)
}

/** The "Silence calls from numbers not in my contacts" setting. It stays on this device and is never sent to the server. */
class LocalScreeningSettings internal constructor(private val store: BooleanStore) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("screening_local", Context.MODE_PRIVATE).let { prefs ->
            object : BooleanStore {
                override fun get(key: String, default: Boolean) = prefs.getBoolean(key, default)
                override fun set(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }
            }
        },
    )

    /** Default off. */
    var silenceNonContacts: Boolean
        get() = store.get(KEY_SILENCE, false)
        set(value) = store.set(KEY_SILENCE, value)

    private companion object {
        const val KEY_SILENCE = "silence_non_contacts"
    }
}

/** Tells the User that contacts access is missing, so calls are not silenced. One notification, replaced on repeat. */
class ContactsAccessNotifier(private val context: Context) {
    fun notifyAccessRequired() {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(context.getString(R.string.notif_title_attention))
                    .setContentText(context.getString(R.string.notif_contacts_access_required))
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
        } catch (e: RuntimeException) {
            Log.w("CallAnswer", "contacts notification failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val CHANNEL_ID = "ALERTS"
        const val NOTIFICATION_ID = 3302
    }
}
