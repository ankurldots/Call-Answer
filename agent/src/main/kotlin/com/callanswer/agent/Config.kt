package com.callanswer.agent

/** Agent_Server configuration, read from environment variables. */
data class Config(
    val region: String,
    val tableName: String,
    val recordingsBucket: String,
    val exotelSecretName: String,
    val callTokenSecretName: String,
    val fcmSecretName: String,
    val sonicMaxSessionSeconds: Int,
    val sonicIdleSeconds: Int,
    val sonicModelId: String,
    val port: Int,
    val exotelStreamHost: String = DEFAULT_STREAM_HOST,
    val exotelSampleRate: Int = DEFAULT_SAMPLE_RATE,
    val exotelChunkBytes: Int = MIN_CHUNK_BYTES,
    val exotelSendAheadMs: Int = DEFAULT_SEND_AHEAD_MS,
    val exotelMediaTimeoutSeconds: Int = DEFAULT_MEDIA_TIMEOUT_SECONDS,
) {
    companion object {
        const val DEFAULT_REGION = "ap-northeast-1"
        const val DEFAULT_MODEL_ID = "amazon.nova-2-sonic-v1:0"
        const val DEFAULT_MAX_SESSION_SECONDS = 480
        const val DEFAULT_IDLE_SECONDS = 60
        const val DEFAULT_PORT = 8080
        const val DEFAULT_STREAM_HOST = "agent.leadingdots.com"
        const val DEFAULT_SAMPLE_RATE = 8000
        const val MIN_CHUNK_BYTES = 3200
        const val MAX_CHUNK_BYTES = 100_000
        const val CHUNK_MULTIPLE = 320
        const val DEFAULT_SEND_AHEAD_MS = 300
        const val DEFAULT_MEDIA_TIMEOUT_SECONDS = 10
        val SAMPLE_RATES = setOf(8000, 16000, 24000)

        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun str(name: String, default: String): String =
                env[name]?.takeIf { it.isNotBlank() } ?: default

            fun int(name: String, default: Int): Int {
                val raw = env[name]?.takeIf { it.isNotBlank() } ?: return default
                val value = raw.trim().toIntOrNull()
                    ?: throw IllegalArgumentException("$name must be an integer, was '$raw'")
                require(value > 0) { "$name must be positive, was $value" }
                return value
            }

            val sampleRate = int("EXOTEL_SAMPLE_RATE", DEFAULT_SAMPLE_RATE)
            require(sampleRate in SAMPLE_RATES) { "EXOTEL_SAMPLE_RATE must be one of $SAMPLE_RATES, was $sampleRate" }
            val chunkBytes = int("EXOTEL_CHUNK_BYTES", MIN_CHUNK_BYTES)
            val allowSmall = env["EXOTEL_ALLOW_SMALL_CHUNKS"]?.trim().equals("true", ignoreCase = true)
            require(chunkBytes % CHUNK_MULTIPLE == 0) {
                "EXOTEL_CHUNK_BYTES must be a multiple of $CHUNK_MULTIPLE, was $chunkBytes"
            }
            require(chunkBytes <= MAX_CHUNK_BYTES) { "EXOTEL_CHUNK_BYTES must be at most $MAX_CHUNK_BYTES, was $chunkBytes" }
            require(allowSmall || chunkBytes >= MIN_CHUNK_BYTES) {
                "EXOTEL_CHUNK_BYTES must be at least $MIN_CHUNK_BYTES unless EXOTEL_ALLOW_SMALL_CHUNKS=true, was $chunkBytes"
            }

            return Config(
                region = str("AWS_REGION", DEFAULT_REGION),
                tableName = str("AGENT_TABLE", "call-answer"),
                recordingsBucket = str("RECORDINGS_BUCKET", "call-answer-recordings"),
                exotelSecretName = str("EXOTEL_SECRET_NAME", "call-answer/exotel"),
                callTokenSecretName = str("CALL_TOKEN_SECRET_NAME", "call-answer/call-token"),
                fcmSecretName = str("FCM_SECRET_NAME", "call-answer/fcm"),
                sonicMaxSessionSeconds = int("SONIC_MAX_SESSION_SECONDS", DEFAULT_MAX_SESSION_SECONDS),
                sonicIdleSeconds = int("SONIC_IDLE_SECONDS", DEFAULT_IDLE_SECONDS),
                sonicModelId = str("SONIC_MODEL_ID", DEFAULT_MODEL_ID),
                port = int("PORT", DEFAULT_PORT),
                exotelStreamHost = str("EXOTEL_STREAM_HOST", DEFAULT_STREAM_HOST),
                exotelSampleRate = sampleRate,
                exotelChunkBytes = chunkBytes,
                exotelSendAheadMs = int("EXOTEL_SEND_AHEAD_MS", DEFAULT_SEND_AHEAD_MS),
                exotelMediaTimeoutSeconds = int("EXOTEL_MEDIA_TIMEOUT_SECONDS", DEFAULT_MEDIA_TIMEOUT_SECONDS),
            )
        }
    }
}
