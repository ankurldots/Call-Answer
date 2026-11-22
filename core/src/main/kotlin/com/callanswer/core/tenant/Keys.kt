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
