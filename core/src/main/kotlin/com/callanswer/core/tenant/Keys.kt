package com.callanswer.core.tenant

/** Builds every DynamoDB and S3 key from a [UserId], so no caller assembles a raw key. */
object Keys {
    fun user(userId: UserId): String = "USER#${userId.sub}"

    fun number(e164: E164): String = "NUMBER#${e164.value}"

    fun call(callId: String): String = "CALL#${segment(callId)}"

    fun device(deviceId: String): String = "DEVICE#${segment(deviceId)}"

    const val PROFILE_SK: String = "PROFILE"
    const val SETTINGS_SK: String = "SETTINGS"
    const val ASSIGNMENT_SK: String = "ASSIGNMENT"

    fun s3Prefix(userId: UserId): String = "users/${userId.sub}/"

    fun recording(userId: UserId, callId: String): String = "${s3Prefix(userId)}recordings/${segment(callId)}.wav"

    fun transcript(userId: UserId, callId: String): String = "${s3Prefix(userId)}transcripts/${segment(callId)}.json"

    /** An id that came from a request becomes part of a sort key or an object key, so it must not escape its place. */
    private fun segment(id: String): String {
        require(id.isNotBlank() && id.none { it == '/' || it == '\\' || it.isWhitespace() } && ".." !in id) {
            "Invalid identifier segment"
        }
        return id
    }
}

/** Sort keys and index keys of the single table, kept next to [Keys] so that no caller assembles a key by hand. */
object TableKeys {
    const val CONSENT_SK: String = "CONSENT"
    const val FWDTEST_SK: String = "FWDTEST"
    const val CALL_PREFIX: String = "CALL#"
    const val DEVICE_PREFIX: String = "DEVICE#"
    const val TRACE_PREFIX: String = "TRACE#"
    const val COST_PREFIX: String = "COST#"
    const val OPEN_PREFIX: String = "OPEN#"
    const val RECENT_PREFIX: String = "RECENT#"
    const val GSI_USERS: String = "USERS"
    const val GSI_ASSIGNMENTS: String = "ASSIGNMENTS"
    const val GSI_OPEN_CALLS: String = "OPENCALLS"
    private const val MS_DIGITS = 13

    /** `CALL#<startEpochMs>#<callId>` with the time zero-padded so that string order is time order. */
    fun callSk(startEpochMs: Long, callId: String): String {
        require(startEpochMs >= 0) { "Negative start time" }
        val ms = startEpochMs.toString().padStart(MS_DIGITS, '0')
        return "$CALL_PREFIX$ms#${Keys.call(callId).removePrefix(CALL_PREFIX)}"
    }

    /** Upper bound of the call sort keys for calls that started at or before [startEpochMs]. */
    fun callSkUpTo(startEpochMs: Long): String = "$CALL_PREFIX${startEpochMs.coerceAtLeast(0).toString().padStart(MS_DIGITS, '0')}#\uFFFF"
    fun callSkFrom(): String = "$CALL_PREFIX${"0".repeat(MS_DIGITS)}#"

    /** Parses a sort key made by [callSk]; null if it is not a call key. */
    fun parseCallSk(sk: String): Pair<Long, String>? {
        if (!sk.startsWith(CALL_PREFIX)) return null
        val rest = sk.removePrefix(CALL_PREFIX)
        val i = rest.indexOf('#')
        if (i <= 0) return null
        val ms = rest.substring(0, i).toLongOrNull() ?: return null
        return ms to rest.substring(i + 1)
    }

    fun traceSk(callId: String): String = "$TRACE_PREFIX${Keys.call(callId).removePrefix(CALL_PREFIX)}"
    fun costSk(yearMonth: String): String {
        require(Regex("""\d{4}-\d{2}""").matches(yearMonth)) { "Invalid month" }
        return "$COST_PREFIX$yearMonth"
    }
    fun openSk(callId: String): String = "$OPEN_PREFIX${Keys.call(callId).removePrefix(CALL_PREFIX)}"
    fun deviceSk(deviceId: String): String = Keys.device(deviceId)
    fun recentSk(e164: E164): String = "$RECENT_PREFIX${e164.value}"
    fun parseRecentSk(sk: String): E164? =
        if (sk.startsWith(RECENT_PREFIX)) runCatching { E164(sk.removePrefix(RECENT_PREFIX)) }.getOrNull() else null

    fun userGsiSk(userId: UserId): String = Keys.user(userId)
    fun openGsiSk(startEpochMs: Long, userId: UserId, callId: String): String =
        "${startEpochMs.toString().padStart(MS_DIGITS, '0')}#${userId.sub}#${Keys.call(callId).removePrefix(CALL_PREFIX)}"

    fun parseOpenGsiSk(sk: String): Triple<Long, UserId, String>? {
        val p = sk.split('#', limit = 3)
        if (p.size != 3) return null
        val ms = p[0].toLongOrNull() ?: return null
        return runCatching { Triple(ms, UserId(p[1]), p[2]) }.getOrNull()
    }

    /** Reads the `sub` out of a `USER#<sub>` partition key. */
    fun parseUserPk(pk: String): UserId? =
        if (pk.startsWith("USER#")) runCatching { UserId(pk.removePrefix("USER#")) }.getOrNull() else null
}
