package com.callanswer.core.report

/** Pure helper computing the flags passed to [ReportEvent.ReportSpamTapped] (14.13, 14.16, 14.17). */
object ReportFlowPolicy {

    /** Build-time feature flag `SILENT_SMS_ENABLED`; the app passes its BuildConfig value. */
    const val SILENT_SMS_ENABLED_DEFAULT: Boolean = true

    data class Flags(val senderAvailable: Boolean, val silentSend: Boolean, val sendSmsGranted: Boolean)

    fun senderAvailable(callerNumber: String?): Boolean = SenderNumber.normalize(callerNumber) != null

    /** Silent send applies only when the setting is on and the build flag is enabled. */
    fun silentSend(silentSendSetting: Boolean, silentSmsEnabled: Boolean = SILENT_SMS_ENABLED_DEFAULT): Boolean =
        silentSendSetting && silentSmsEnabled

    fun sendSmsGranted(permissionGranted: Boolean): Boolean = permissionGranted

    fun flags(
        callerNumber: String?,
        silentSendSetting: Boolean,
        permissionGranted: Boolean,
        silentSmsEnabled: Boolean = SILENT_SMS_ENABLED_DEFAULT,
    ): Flags = Flags(
        senderAvailable = senderAvailable(callerNumber),
        silentSend = silentSend(silentSendSetting, silentSmsEnabled),
        sendSmsGranted = sendSmsGranted(permissionGranted),
    )
}
