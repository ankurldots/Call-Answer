package com.callanswer.app.telecom

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import com.callanswer.core.phone.ContactIndex
import com.callanswer.core.phone.PhoneNumberMatcher

/**
 * In-memory set of [PhoneNumberMatcher.matchKey] keys built from
 * [ContactsContract.CommonDataKinds.Phone]. Call [load] at App start and when the screening role
 * is granted; a [ContentObserver] reloads the set when contacts change. [readable] is false after a
 * [SecurityException] or any query error, and true again after a successful load.
 */
class ContentContactIndex(context: Context) : ContactIndex {

    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val lock = Any()

    @Volatile private var keys: Set<String> = emptySet()
    @Volatile private var _readable: Boolean = false
    private var observing = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            load()
        }
    }

    override val readable: Boolean get() = _readable

    override fun isKnown(rawNumber: String): Boolean {
        val key = PhoneNumberMatcher.matchKey(rawNumber)
        return key.isNotEmpty() && key in keys
    }

    /** Reloads the key set synchronously and registers the change observer (once). */
    fun load() {
        synchronized(lock) {
            try {
                val loaded = HashSet<String>()
                val cursor = resolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, null,
                )
                if (cursor == null) {
                    _readable = false
                    return
                }
                cursor.use {
                    val col = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (col < 0) {
                        _readable = false
                        return
                    }
                    while (it.moveToNext()) {
                        val key = PhoneNumberMatcher.matchKey(it.getString(col).orEmpty())
                        if (key.isNotEmpty()) loaded.add(key)
                    }
                }
                keys = loaded
                _readable = true
            } catch (e: SecurityException) {
                keys = emptySet()
                _readable = false
            } catch (e: Exception) {
                keys = emptySet()
                _readable = false
            }
            registerObserver()
        }
    }

    private fun registerObserver() {
        if (observing) return
        try {
            resolver.registerContentObserver(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, true, observer)
            observing = true
        } catch (_: Exception) {
            // Observer registration failure leaves the last loaded set in place.
        }
    }

    fun close() {
        synchronized(lock) {
            if (observing) resolver.unregisterContentObserver(observer)
            observing = false
        }
    }
}
