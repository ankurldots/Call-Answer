package com.callanswer.agent.store

import com.callanswer.core.tenant.Keys
import com.callanswer.core.tenant.UserId
import java.time.Duration
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest

/** Status of a call's recording. */
enum class RecordingStatus { AVAILABLE, NOT_FOUND }

/** A presigned download URL and its lifetime. */
data class PresignedRecording(val url: String, val expiresInSeconds: Long)

/** Makes presigned GET URLs. The S3 implementation is [S3RecordingSigner]; tests use a fake. */
fun interface RecordingSigner {
    fun presignGet(key: String, expires: Duration): String
}

/**
 * Recordings under `users/<sub>/recordings/<callId>.wav`. Every key is built from a [UserId] through [Keys];
 * a key outside the User's recordings prefix is never presigned. Objects are encrypted by the bucket's KMS key.
 */
class RecordingStore(
    private val blobs: BlobStore,
    private val signer: RecordingSigner,
) {
    suspend fun upload(userId: UserId, callId: String, wav: ByteArray): String {
        val key = Keys.recording(userId, callId)
        blobs.put(key, wav, WAV_CONTENT_TYPE)
        return key
    }

    suspend fun delete(userId: UserId, callId: String) {
        blobs.delete(Keys.recording(userId, callId))
    }

    suspend fun status(userId: UserId, callId: String): RecordingStatus {
        val key = Keys.recording(userId, callId)
        return if (blobs.listKeys(key).contains(key)) RecordingStatus.AVAILABLE else RecordingStatus.NOT_FOUND
    }

    /** Presigned URL for the call's recording, or null when there is no recording (404 for the caller). */
    suspend fun presign(userId: UserId, callId: String): PresignedRecording? =
        presignKey(userId, Keys.recording(userId, callId))

    /** Presigns [key] only when it lies under the User's recordings prefix and the object exists. */
    suspend fun presignKey(userId: UserId, key: String): PresignedRecording? {
        require(isUnderRecordingsPrefix(userId, key)) { "Key is outside the user's recordings prefix" }
        if (!blobs.listKeys(key).contains(key)) return null
        return PresignedRecording(signer.presignGet(key, EXPIRY), EXPIRY.seconds)
    }

    companion object {
        val EXPIRY: Duration = Duration.ofMinutes(10)
        const val WAV_CONTENT_TYPE = "audio/wav"

        fun isUnderRecordingsPrefix(userId: UserId, key: String): Boolean {
            val prefix = "${Keys.s3Prefix(userId)}recordings/"
            val rest = key.removePrefix(prefix)
            return key.startsWith(prefix) && rest.isNotEmpty() && '/' !in rest && ".." !in rest
        }
    }
}

/** S3 presigner for the recordings bucket. */
class S3RecordingSigner(private val presigner: S3Presigner, private val bucket: String) : RecordingSigner {
    override fun presignGet(key: String, expires: Duration): String =
        presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(expires)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build(),
        ).url().toString()
}
