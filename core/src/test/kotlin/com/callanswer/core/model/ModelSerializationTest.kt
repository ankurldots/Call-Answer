package com.callanswer.core.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ModelSerializationTest : StringSpec({
    "Settings round-trip through CoreJson" {
        val s = Settings(mapLocation = "Flat 4", transferTarget = "+919999999999")
        CoreJson.decodeFromString<Settings>(CoreJson.encodeToString(Settings.serializer(), s)) shouldBe s
    }
    "CallLogEntry round-trip keeps recording and report fields" {
        val e = CallLogEntry(
            id = "1", callerNumber = null, startedAtEpochMs = 1L, zoneId = "Asia/Kolkata", durationSec = 5,
            language = Language.HI, intent = IntentCategory.SOLICITATION, outcome = Outcome.STALLED,
            endReason = EndReason.MAX_DURATION,
            transcript = listOf(Utterance(Speaker.CALLER, "hello", Language.EN, 0, 100)),
            gateStatements = listOf(GateStatement("3", true, false, 10)), voiceGender = VoiceGender.FEMALE,
            voiceSubstitutions = listOf(VoiceSubstitution(Language.HI, "v1")), stallingDurationSec = 4,
            takeovers = listOf(TakeoverRecord(2, "Asia/Kolkata", 3)), interruption = InterruptionRecord(1, 2),
            recording = RecordingInfo(RecordingStatus.LINKED, "content://x", 10, 20),
            notes = listOf(LogNoteKind.TIME_READ_FAILED),
            transfer = TransferRecord(TransferPath.PATH_B_ALERT, AlertReason.NO_ANSWER_30S, TransferOutcome.NO_TAKEOVER_30S, 1, 2),
            report = SpamReportInfo(ReportStatus.PREPARED, 5, "Asia/Kolkata", "a1", false,
                ReportStamp(ReportStatus.NOT_REPORTED, null, null)),
        )
        CoreJson.decodeFromString<CallLogEntry>(CoreJson.encodeToString(CallLogEntry.serializer(), e)) shouldBe e
    }
})
