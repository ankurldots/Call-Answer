package com.callanswer.core.tenant

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Merged document plus the fields changed on both sides with different values (the User resolves those). */
data class MergeResult(val merged: JsonObject, val conflicts: List<String>)

object SettingsMerge {
    /**
     * [base] is the server document the edit started from, [server] the current one, [edits] only the locally changed fields.
     * A field edited locally and unchanged on the server takes the local value. A field changed only on the server keeps the
     * server value. A field changed on both with different values goes to the conflict list and keeps the server value.
     */
    fun threeWay(base: JsonObject, server: JsonObject, edits: JsonObject): MergeResult {
        val merged = LinkedHashMap<String, JsonElement>(server)
        val conflicts = mutableListOf<String>()
        for ((field, local) in edits) {
            val serverChanged = base[field] != server[field]
            when {
                !serverChanged || server[field] == local -> merged[field] = local
                else -> conflicts += field
            }
        }
        return MergeResult(JsonObject(merged), conflicts)
    }
}
