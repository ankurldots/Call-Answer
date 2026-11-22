package com.callanswer.core.model

import kotlinx.serialization.json.Json

/** Shared JSON configuration. Enums are stored by name (the kotlinx default). */
val CoreJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}
