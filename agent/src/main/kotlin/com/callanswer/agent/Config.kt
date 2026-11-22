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
) {
    companion object {
        const val DEFAULT_REGION = "ap-northeast-1"
        const val DEFAULT_MODEL_ID = "amazon.nova-2-sonic-v1:0"
        const val DEFAULT_MAX_SESSION_SECONDS = 480
        const val DEFAULT_IDLE_SECONDS = 60
        const val DEFAULT_PORT = 8080

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
            )
        }
    }
}
