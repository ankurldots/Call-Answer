package com.callanswer.core.settings

import com.callanswer.core.model.CoreJson
import com.callanswer.core.model.FallbackRoute
import com.callanswer.core.model.Settings
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class UserMobileValidationTest : StringSpec({
    "accepts E.164 and rejects other formats" {
        SettingsValidator.validateUserMobile("+919876543210").isValid shouldBe true
        listOf("9876543210", "+0123456789", "+91 98765", "+91abc", "").forEach {
            SettingsValidator.validateUserMobile(it).isValid shouldBe false
        }
    }
    "rejects a number that is an assigned virtual number" {
        val r = SettingsValidator.validateUserMobile("+919876543210") { it == "+919876543210" }
        (r as ValidationResult.Invalid).errors shouldBe listOf(SettingsError.UserMobileIsVirtualNumber)
        SettingsValidator.validateUserMobile("+919876543211") { false }.isValid shouldBe true
    }
    "settings JSON without the cloud fields decodes with defaults" {
        val s = CoreJson.decodeFromString<Settings>("""{"agentName":"A"}""")
        s.userMobile shouldBe null
        s.version shouldBe 0L
        s.fallbackRoute shouldBe FallbackRoute.VOICEMAIL
        s.retentionDays shouldBe 30
        s.maxStallingMinutes shouldBe 5
    }
})
