package com.callanswer.agent.store

import com.callanswer.core.model.CoreJson
import com.callanswer.core.model.FrozenSettings
import com.callanswer.core.model.Settings
import com.callanswer.core.settings.SettingsValidator
import com.callanswer.core.settings.ValidationResult
import com.callanswer.core.tenant.E164
import com.callanswer.core.tenant.Keys
import com.callanswer.core.tenant.SettingsUpdate
import com.callanswer.core.tenant.UserId
import kotlinx.serialization.encodeToString

/** The settings used for one call, with the version they were read at. */
data class SettingsSnapshot(val frozen: FrozenSettings, val version: Long)

/**
 * Per-User settings with optimistic concurrency. The item holds the document as JSON and a numeric `version`.
 * [update] writes only when the stored version equals the expected one, so two writers with the same expected
 * version cannot both succeed.
 */
class SettingsStore(private val store: ItemStore, private val directory: DirectoryRepository) {

    /** The current settings and version. Defaults with version 0 when the User has no settings item. */
    suspend fun get(userId: UserId): SettingsUpdate.Saved = read(userId) ?: SettingsUpdate.Saved(Settings(), 0L)

    /** A strongly consistent snapshot, taken once at call start. */
    suspend fun freeze(userId: UserId): SettingsSnapshot {
        val s = get(userId)
        return SettingsSnapshot(s.document.freeze(), s.version)
    }

    suspend fun update(userId: UserId, expectedVersion: Long, candidate: Settings): SettingsUpdate {
        if (expectedVersion < 0) return SettingsUpdate.Invalid("version")
        invalidField(candidate)?.let { return SettingsUpdate.Invalid(it) }
        val newVersion = expectedVersion + 1
        val doc = candidate.copy(version = newVersion)
        val item = StoreItem(
            Keys.user(userId), Keys.SETTINGS_SK,
            mapOf("json" to AttrValue.S(CoreJson.encodeToString(doc)), "version" to AttrValue.N(newVersion)),
        )
        val condition = if (expectedVersion == 0L) Condition.NotExists else Condition.AttrEquals("version", AttrValue.N(expectedVersion))
        return try {
            store.put(item, condition)
            SettingsUpdate.Saved(doc, newVersion)
        } catch (e: ConditionFailedException) {
            val current = read(userId) ?: SettingsUpdate.Saved(Settings(), 0L)
            SettingsUpdate.Conflict(current.document, current.version)
        }
    }

    private suspend fun read(userId: UserId): SettingsUpdate.Saved? {
        val item = store.get(ItemKey(Keys.user(userId), Keys.SETTINGS_SK), consistent = true) ?: return null
        val version = item.num("version") ?: 0L
        val doc = CoreJson.decodeFromString(Settings.serializer(), item.str("json") ?: return null).copy(version = version)
        return SettingsUpdate.Saved(doc, version)
    }

    /** The name of the first rejected field, or null when the candidate is valid. */
    private suspend fun invalidField(c: Settings): String? {
        c.transferTarget?.let { if (!SettingsValidator.validateTransferTarget(it).isValid) return "transferTarget" }
        c.mapLocation?.let { if (!SettingsValidator.validateLocationUrl(it).isValid) return "mapLocation" }
        if (!SettingsValidator.validateMaxStalling(c.maxStallingSeconds.toString()).isValid) return "maxStallingSeconds"
        val g = c.gates
        if (!SettingsValidator.validateGate(g.gate1).isValid) return "gates.gate1"
        if (!SettingsValidator.validateGate(g.gate3).isValid) return "gates.gate3"
        if (!SettingsValidator.validateSteps(c.deliverySteps).isAccepted) return "deliverySteps"
        c.userMobile?.let { mobile ->
            if (!SettingsValidator.validateUserMobile(mobile).isValid) return "userMobile"
            // Cross-record rule: the number must not be an assigned Virtual_Number.
            val assigned = directory.lookupNumber(E164(mobile)) != null
            val r: ValidationResult = SettingsValidator.validateUserMobile(mobile) { assigned }
            if (!r.isValid) return "userMobile"
        }
        return null
    }
}
