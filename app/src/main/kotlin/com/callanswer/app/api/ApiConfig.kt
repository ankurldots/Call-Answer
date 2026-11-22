package com.callanswer.app.api

/** Where the companion API runs. [baseUrl] has no trailing slash. The WebSocket lives at `<baseUrl>/live`. */
data class ApiConfig(val baseUrl: String = DEFAULT_BASE_URL) {
    val liveUrl: String get() = baseUrl.trimEnd('/') + "/live"

    companion object {
        const val DEFAULT_BASE_URL = "https://api.leadingdots.com/api/v1"
    }
}
