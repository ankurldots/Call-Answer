package com.callanswer.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.callanswer.app.BuildConfig
import com.callanswer.core.delivery.StepListError
import com.callanswer.core.delivery.StepListResult
import com.callanswer.core.model.FrozenSettings
import com.callanswer.core.model.Settings
import com.callanswer.core.settings.SettingsError
import com.callanswer.core.settings.SettingsValidator
import com.callanswer.core.settings.ValidationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** JSON serializer for the typed DataStore holding [Settings]. */
object SettingsSerializer : Serializer<Settings> {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override val defaultValue: Settings = Settings()

    override suspend fun readFrom(input: InputStream): Settings {
        val text = input.readBytes().decodeToString()
        if (text.isBlank()) return defaultValue
        // A corrupt file falls back to defaults rather than crashing the app.
        return runCatching { json.decodeFromString(Settings.serializer(), text) }.getOrDefault(defaultValue)
    }

    override suspend fun writeTo(t: Settings, output: OutputStream) {
        output.write(json.encodeToString(Settings.serializer(), t).encodeToByteArray())
    }
}

/** Holds one secret (the cloud API key) encrypted with an Android Keystore AES-GCM key. */
interface SecretStore {
    fun get(): String?
    fun set(value: String?)
}

class KeystoreSecretStore(private val file: File, private val keyAlias: String = "cloud_api_key") : SecretStore {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    @Synchronized
    override fun get(): String? {
        if (!file.exists()) return null
        return runCatching {
            val raw = Base64.decode(file.readText(), Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_LEN)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            c.doFinal(raw, IV_LEN, raw.size - IV_LEN).decodeToString()
        }.getOrNull()
    }

    @Synchronized
    override fun set(value: String?) {
        if (value.isNullOrEmpty()) {
            file.delete()
            return
        }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(value.encodeToByteArray())
        file.parentFile?.mkdirs()
        file.writeText(Base64.encodeToString(out, Base64.NO_WRAP))
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }
}

/** Result of [SettingsStore.update]. A rejected candidate leaves the stored value unchanged. */
sealed class UpdateResult {
    data object Saved : UpdateResult()
    data class Rejected(
        val errors: List<SettingsError>,
        val stepErrors: Set<StepListError> = emptySet(),
    ) : UpdateResult()
}

class SettingsStore(
    private val dataStore: DataStore<Settings>,
    private val secrets: SecretStore,
    private val accessibilityServiceEnabled: () -> Boolean,
    private val pathASupported: Boolean = BuildConfig.PATH_A_SUPPORTED,
) {
    val settings: Flow<Settings> get() = dataStore.data

    suspend fun current(): Settings = dataStore.data.first()

    /** Validates [candidate]; on success persists it, otherwise returns the errors and changes nothing. */
    suspend fun update(candidate: Settings): UpdateResult {
        val errors = validate(candidate)
        val steps = SettingsValidator.validateSteps(candidate.deliverySteps)
        val stepErrors = (steps as? StepListResult.Rejected)?.errors ?: emptySet()
        if (errors.isNotEmpty() || stepErrors.isNotEmpty()) return UpdateResult.Rejected(errors, stepErrors)
        dataStore.updateData { candidate }
        return UpdateResult.Saved
    }

    /** Builds the [FrozenSettings] used for one call; later changes apply from the next call. */
    suspend fun snapshot(): FrozenSettings {
        val s = current()
        val pathA = pathASupported && s.transferTarget != null && accessibilityServiceEnabled()
        return s.freeze(pathA)
    }

    fun getCloudApiKey(): String? = secrets.get()

    fun setCloudApiKey(key: String?) = secrets.set(key)

    companion object {
        fun validate(s: Settings): List<SettingsError> {
            val errors = mutableListOf<SettingsError>()
            fun add(r: ValidationResult) {
                if (r is ValidationResult.Invalid) errors += r.errors
            }
            s.transferTarget?.let { add(SettingsValidator.validateTransferTarget(it)) }
            add(SettingsValidator.validateMaxStalling(s.maxStallingSeconds.toString()))
            add(SettingsValidator.validateGate(s.gates.gate1))
            add(SettingsValidator.validateGate(s.gates.gate3))
            val w = s.gates.nightWindow
            add(SettingsValidator.validateNightWindow(w.start.toString(), w.end.toString()))
            return errors
        }

        @Volatile
        private var instance: SettingsStore? = null

        /**
         * Returns the single process-wide store. AndroidX DataStore forbids two active instances
         * for one file, so every caller must share this one. The accessibility check is resolved
         * from the application context inside the store.
         */
        fun create(context: Context): SettingsStore {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val app = context.applicationContext
                val ds = DataStoreFactory.create(
                    serializer = SettingsSerializer,
                    produceFile = { File(app.filesDir, "datastore/settings.json") },
                )
                val store = SettingsStore(
                    ds,
                    KeystoreSecretStore(File(app.filesDir, "secrets/cloud_api_key")),
                    accessibilityServiceEnabled = { isAccessibilityServiceEnabled(app) },
                )
                instance = store
                return store
            }
        }

        private fun isAccessibilityServiceEnabled(app: Context): Boolean {
            val component = "${app.packageName}/com.callanswer.app.audio.CallAudioAccessibilityService"
            val enabled = android.provider.Settings.Secure.getString(
                app.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            return enabled?.split(':')?.any { it.equals(component, ignoreCase = true) } == true
        }
    }
}
