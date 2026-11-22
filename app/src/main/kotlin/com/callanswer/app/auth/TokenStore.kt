package com.callanswer.app.auth
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
/** Persists the signed-in user's email and refresh token. */
interface TokenStore {
    fun load(): StoredSession?
    fun save(session: StoredSession)
    fun clear()
}
data class StoredSession(val email: String, val refreshToken: String)
/** Refresh token encrypted with an AES-GCM key that never leaves the Android Keystore. */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.applicationContext.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
    override fun load(): StoredSession? {
        val email = prefs.getString("email", null) ?: return null
        val blob = prefs.getString("token", null) ?: return null
        return runCatching { StoredSession(email, decrypt(blob)) }.getOrElse { clear(); null }
    }
    override fun save(session: StoredSession) {
        prefs.edit().putString("email", session.email).putString("token", encrypt(session.refreshToken)).apply()
    }
    override fun clear() {
        prefs.edit().clear().apply()
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }
    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_BYTES)) }
        return String(c.doFinal(raw, IV_BYTES, raw.size - IV_BYTES))
    }
    private companion object {
        const val ALIAS = "call_answer_refresh_token"
        const val IV_BYTES = 12
    }
}
