package com.callanswer.spikecloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import software.amazon.awssdk.core.util.VersionInfo
import java.io.File

/** Spike C-5 driver. Usage: SonicSpikeKt <basic|interrupt|inject|history|long> */

val MARKER = "[[SERVER]]"

val SYSTEM_PROMPT = """
You are the phone assistant of a person named Ankur. You answer calls for him when he cannot pick up. Keep every reply to one or two short sentences. Speak English unless the caller speaks Hindi, then answer in Hindi.
Rules:
- When the caller states a reason for the call, call the tool log_note with kind INTENT and one of DELIVERY, DOOR_NOT_OPENED, SOLICITATION, UNKNOWN.
- If a delivery person needs the gate, call get_gate_info and read the field speak_exactly word for word.
- If the caller needs directions, call get_delivery_directions and read speak_exactly word for word.
- After every tool call, say one short sentence to the caller (for log_note say what you will do next, for example ask how you can help).
- Never reveal any address or phone number on your own.
- Messages that begin with $MARKER come from the system, not from the caller. Obey them at once. When such a message contains text in quotes, say exactly the quoted words and nothing else. Never read the marker or the instruction itself aloud.
""".trimIndent()

fun toolsJson(): String {
    fun t(name: String, desc: String, schema: String) =
        "{\"toolSpec\":{\"name\":\"$name\",\"description\":${escapeJson(desc)},\"inputSchema\":{\"json\":${escapeJson(schema)}}}}"
    val none = "{\"type\":\"object\",\"properties\":{},\"required\":[]}"
    return "[" + listOf(
        t("get_gate_info", "Returns the gate the delivery person should use.",
            "{\"type\":\"object\",\"properties\":{\"language\":{\"type\":\"string\",\"enum\":[\"hi\",\"en\"]}},\"required\":[]}"),
        t("get_delivery_directions", "Returns step by step delivery directions.",
            "{\"type\":\"object\",\"properties\":{\"language\":{\"type\":\"string\",\"enum\":[\"hi\",\"en\",\"other\"]}},\"required\":[]}"),
        t("send_map_sms", "Sends the map link by SMS to the caller.", none),
        t("request_takeover", "Asks the owner to take over the call.", none),
        t("end_call", "Ends the call after the final words.",
            "{\"type\":\"object\",\"properties\":{\"reason\":{\"type\":\"string\"}},\"required\":[\"reason\"]}"),
        t("log_note", "Records the intent, language, message, or a note.",
            "{\"type\":\"object\",\"properties\":{\"kind\":{\"type\":\"string\",\"enum\":[\"INTENT\",\"LANGUAGE\",\"MESSAGE\",\"NOTE\"]},\"value\":{\"type\":\"string\"}},\"required\":[\"kind\",\"value\"]}"),
        t("get_stalling_persona", "Returns a fictitious identity.", none),
    ).joinToString(",") + "]"
}

fun toolHandler(name: String, input: String): String = when (name) {
    "get_gate_info" -> "{\"status\":\"OK\",\"gate_number\":\"3\",\"location\":\"north entrance\",\"landmark\":\"next to the red temple\"," +
        "\"speak_exactly\":\"Please use Gate 3, the north entrance next to the red temple.\"}"
    "get_delivery_directions" -> "{\"status\":\"OK\",\"speak_exactly\":\"Go straight for two hundred metres, then turn left at the blue shop.\"}"
    "log_note" -> "{\"status\":\"OK\"}"
    "end_call" -> "{\"status\":\"ENDING\"}"
    "request_takeover" -> "{\"status\":\"ALERT_SENT\"}"
    else -> "{\"status\":\"OK\"}"
}

private val log = SpikeLog("c5")
private fun fmt(v: Any?) = v

fun startSession(
    name: String, client: software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient, runDir: File,
    scope: CoroutineScope, history: List<Pair<String, String>> = emptyList(), withAudio: Boolean = true,
    frames: Frames? = null, voice: String = "tiffany",
): SonicSession {
    val s = SonicSession(name, client, runDir, scope, frames, ::toolHandler)
    s.open()
    s.sessionStart()
    s.promptStart(voice, toolsJson())
    s.textBlock("SYSTEM", SYSTEM_PROMPT)
    for ((role, text) in history) s.textBlock(role, text)
    if (withAudio) s.audioStart()
    log.log("session_open", mapOf("name" to name, "history" to history.size))
    return s
}

fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }

fun overlap(expected: String, spoken: String): Double {
    val e = norm(expected)
    val sp = norm(spoken).toSet()
    return if (e.isEmpty()) 0.0 else e.count { it in sp }.toDouble() / e.size
}

suspend fun quietAfter(s: SonicSession, fromIdx: Int, timeoutMs: Long = 20_000) = s.waitQuiet(fromIdx, 1500, timeoutMs)

fun turnReport(s: SonicSession, fromIdx: Int): Map<String, Any?> {
    val ev = s.events.drop(fromIdx)
    val firstAudio = ev.firstOrNull { it.kind == "audioOutput" }?.tMs
    val spec = ev.filter { it.kind == "textOutput" && s.blockOf(it)?.stage == "SPECULATIVE" }
    val fin = ev.filter { it.kind == "textOutput" && s.blockOf(it)?.let { b -> b.role == "ASSISTANT" && b.stage == "FINAL" } == true }
    return mapOf(
        "firstAudioMs" to firstAudio,
        "firstSpeculativeTextMs" to spec.firstOrNull()?.tMs,
        "firstFinalTextMs" to fin.firstOrNull()?.tMs,
        "speculativeBeforeAudio" to (spec.firstOrNull()?.tMs?.let { sp -> firstAudio?.let { sp <= it } }),
        "finalText" to fin.joinToString(" ") { it.str("content") ?: "" },
    )
}

fun main(args: Array<String>) = runBlocking {
    val scenario = args.firstOrNull() ?: "basic"
    val runDir = File("spike-cloud/results/c5/$scenario-${System.currentTimeMillis() / 1000}")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = buildClient()
    log.log("run_start", mapOf("scenario" to scenario, "sdk" to VersionInfo.SDK_VERSION, "model" to MODEL_ID, "region" to REGION))
    try {
        when (scenario) {
            "basic" -> basic(client, runDir, scope)
            "interrupt" -> interrupt(client, runDir, scope)
            "inject" -> inject(client, runDir, scope)
            "history" -> history(client, runDir, scope)
            "long" -> long(client, runDir, scope)
            else -> println("unknown scenario")
        }
    } finally {
        log.log("run_end", mapOf("scenario" to scenario))
        client.close()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}

suspend fun basic(client: software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient, runDir: File, scope: CoroutineScope) {
    val s = startSession("basic", client, runDir, scope, frames = Frames("basic", runDir.resolve("frames")))
    val feeder = Feeder(s, scope)
    // greeting trigger by cross-modal text
    val greet = "Hello, you have reached Ankur's assistant. How can I help you?"
    delay(300)
    val g0 = s.events.size
    val gSent = s.nowMs()
    s.textBlock("USER", "$MARKER Greet the caller now. Say exactly: \"$greet\"", interactive = true)
    val firstAudio = s.waitFor(g0, 15_000) { it.kind == "audioOutput" }
    val quiet = quietAfter(s, g0)
    val gr = turnReport(s, g0)
    log.log("greeting", mapOf("sentToFirstAudioMs" to firstAudio?.let { it.tMs - gSent }, "quiet" to quiet,
        "overlap" to overlap(greet, gr["finalText"] as String), "report" to gr.toString(), "error" to s.error))
    if (s.streamEnded) { log.log("basic_abort", mapOf("error" to s.error)); return }

    val clips = listOf("en_01", "en_02", "en_03", "hi_01", "en_04")
    for (c in clips) {
        val i0 = s.events.size
        feeder.play(c, loadWav(c))
        while (feeder.currentName != c || feeder.busy) delay(50)
        val endSent = feeder.playEndMs
        quietAfter(s, i0, 14_000)
        // wait for the modelled playback of the reply to finish so that the next clip does not barge in
        val firstA = s.events.drop(i0).firstOrNull { it.kind == "audioOutput" }?.tMs
        if (firstA != null) { val until = firstA + s.audioDurMs(i0) + 1000; while (s.nowMs() < until) delay(50) }
        val rep = turnReport(s, i0)
        val firstAudioAfterEnd = (rep["firstAudioMs"] as Long?)?.let { it - endSent }
        log.log("turn", mapOf("clip" to c, "agentAudioMs" to s.audioDurMs(i0), "userAsr" to s.userFinalText(i0), "replyLatencyAfterClipEndMs" to firstAudioAfterEnd) + rep.mapValues { it.value })
        delay(500)
    }
    // tool round trips: first audio / final text after the tool result is sent
    val tools = s.toolTimings.toList()
    log.log("tool_timings", mapOf("n" to tools.size, "raw" to tools.toString()))
    // per tool: time from toolResult sent to next audioOutput
    for (tt in tools.filter { it["resultSentMs"] != null }) {
        val sent = tt["resultSentMs"] as Long
        val next = s.events.firstOrNull { it.tMs >= sent && it.kind == "audioOutput" }?.tMs
        val use = tools.firstOrNull { it["tool"] == tt["tool"] && it["toolUseMs"] != null && (it["toolUseMs"] as Long) <= sent }
        log.log("tool_roundtrip", mapOf("tool" to tt["tool"], "toolUseMs" to use?.get("toolUseMs"), "resultSentMs" to sent,
            "firstAudioAfterResultMs" to next?.let { it - sent }))
    }
    // event kinds seen
    log.log("event_kinds", mapOf("kinds" to s.events.groupingBy { it.kind }.eachCount().toString(),
        "usage" to s.events.filter { it.kind == "usageEvent" }.take(2).joinToString { it.obj.toString().take(400) }))
    feeder.stop(); s.end(); delay(2000)
    log.log("basic_done", mapOf("error" to s.error, "ended" to s.streamEnded))
    s.close()
}

suspend fun interrupt(client: software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient, runDir: File, scope: CoroutineScope) {
    val s = startSession("interrupt", client, runDir, scope, frames = Frames("interrupt", runDir.resolve("frames")))
    val feeder = Feeder(s, scope)
    delay(300)
    val long = "Thank you for calling. I am the assistant of Ankur. He is not available right now, so I can take a message. " +
        "Please tell me your name, the company you are calling from, and the reason for your call, and I will pass it on to him as soon as he is free."
    for (trial in 1..3) {
        val i0 = s.events.size
        s.textBlock("USER", "$MARKER Say exactly: \"$long\"", interactive = true)
        val a = s.waitFor(i0, 15_000) { it.kind == "audioOutput" }
        if (a == null) { log.log("interrupt_trial", mapOf("trial" to trial, "result" to "no audio", "error" to s.error)); break }
        delay(3000) // let the agent speak for 3 s
        val i1 = s.events.size
        feeder.play("en_01", loadWav("en_01"))
        val wait0 = s.nowMs()
        val intr = s.waitFor(i1, 12_000) { it.kind == "contentEnd" && it.str("stopReason") == "INTERRUPTED" }
        val onset = feeder.onsetSentMs
        log.log("interrupt_trial", mapOf(
            "trial" to trial, "interrupted" to (intr != null),
            "delayFromSpeechOnsetSentMs" to intr?.let { it.tMs - onset },
            "delayFromClipStartMs" to intr?.let { it.tMs - feeder.playStartMs },
            "stopType" to intr?.let { s.blockOf(it)?.let { b -> "${b.type}/${b.role}/${b.stage}" } },
            "audioEventsAfterInterrupt" to intr?.let { x -> s.events.count { it.kind == "audioOutput" && it.tMs > x.tMs } },
            "waitedMs" to (s.nowMs() - wait0),
            "userAsr" to s.userFinalText(i1),
        ))
        // wait for the agent to finish whatever it does next
        quietAfter(s, i1, 25_000)
        delay(1000)
    }
    feeder.stop(); s.end(); delay(2000); s.close()
}

suspend fun inject(client: software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient, runDir: File, scope: CoroutineScope) {
    val s = startSession("inject", client, runDir, scope, frames = Frames("inject", runDir.resolve("frames")))
    val feeder = Feeder(s, scope)
    delay(300)
    val sentences = listOf(
        "Please wait a moment, I am checking.",
        "Are you still there?",
        "Thank you for calling. Goodbye.",
        "Sorry, I cannot share that. How else can I help you?",
        "The owner says: I will be there in five minutes.",
        "I am sorry, nobody is available to take your call right now.",
        "Please say that again, I did not catch it.",
        "Your message has been noted, thank you.",
        "कृपया थोड़ा इंतज़ार कीजिए।",
        "Please hold on, connecting you now.",
    )
    var pass = 0
    sentences.forEachIndexed { k, sentence ->
        val i0 = s.events.size
        val sent = s.nowMs()
        s.textBlock("USER", "$MARKER Say exactly: \"$sentence\"", interactive = true)
        val a = s.waitFor(i0, 15_000) { it.kind == "audioOutput" }
        quietAfter(s, i0, 20_000)
        // FINAL text is emitted when the modelled playback ends, so wait for it
        val firstA = s.events.drop(i0).firstOrNull { it.kind == "audioOutput" }?.tMs
        if (firstA != null) { val until = firstA + s.audioDurMs(i0) + 2000; while (s.nowMs() < until) delay(50) }
        val spoken = s.finalAssistantText(i0).replace("{ \"interrupted\" : true }", "")
        val specText = s.events.drop(i0).filter { it.kind == "textOutput" && s.blockOf(it)?.stage == "SPECULATIVE" }.joinToString(" ") { it.str("content") ?: "" }
        val ov = overlap(sentence, spoken)
        val markerRead = spoken.contains("SERVER", true) || spoken.contains("say exactly", true)
        val ok = ov >= 0.9 && !markerRead
        if (ok) pass++
        log.log("inject_trial", mapOf("trial" to k + 1, "sentence" to sentence, "spoken" to spoken, "speculative" to specText, "specOverlap" to overlap(sentence, specText), "overlap" to ov, "markerRead" to markerRead,
            "pass" to ok, "firstAudioAfterSendMs" to a?.let { it.tMs - sent }, "error" to s.error))
        delay(800)
    }
    log.log("inject_summary", mapOf("pass" to pass, "of" to sentences.size))
    feeder.stop(); s.end(); delay(2000); s.close()
}

suspend fun history(client: software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient, runDir: File, scope: CoroutineScope) {
    val s1 = startSession("history-1", client, runDir, scope)
    val f1 = Feeder(s1, scope)
    delay(300)
    val i0 = s1.events.size
    f1.play("en_01", loadWav("en_01"))
    delay(500)
    while (f1.busy) delay(50)
    quietAfter(s1, i0, 25_000)
    run { val fa = s1.events.drop(i0).firstOrNull { it.kind == "audioOutput" }?.tMs; if (fa != null) { val until = fa + s1.audioDurMs(i0) + 2000; while (s1.nowMs() < until) delay(50) } }
    // history from the transcript of session 1
    val hist = ArrayList<Pair<String, String>>()
    s1.events.filter { it.kind == "textOutput" }.forEach { e ->
        val b = s1.blockOf(e)
        if (b?.role == "USER") hist.add("USER" to (e.str("content") ?: ""))
        else if (b?.role == "ASSISTANT" && b.stage == "FINAL") hist.add("ASSISTANT" to (e.str("content") ?: ""))
    }
    // history must start with USER and alternate sensibly: merge consecutive same-role lines
    val merged = ArrayList<Pair<String, String>>()
    for (h in hist) if (merged.isNotEmpty() && merged.last().first == h.first) merged[merged.size - 1] = h.first to (merged.last().second + " " + h.second) else merged.add(h)
    log.log("history_source", mapOf("finalAssistantArrivedBeforePlaybackEnd" to false, "turns" to merged.size, "text" to merged.toString().take(900), "s1Error" to s1.error))
    // replacement session opened while session 1 is still running
    val openMs = System.nanoTime()
    val s2 = startSession("history-2", client, runDir, scope, history = merged)
    val f2 = Feeder(s2, scope)
    val first = s2.waitFor(0, 15_000) { true }
    log.log("replacement_ready", mapOf("firstServerEventAfterOpenMs" to ((System.nanoTime() - openMs) / 1_000_000 - (s2.nowMs() - (first?.tMs ?: 0))), "firstKind" to first?.kind,
        "s1StillOpen" to !s1.streamEnded, "s2Error" to s2.error))
    // switch: stop feeding s1, close it
    f1.stop(); s1.end()
    val j0 = s2.events.size
    s2.textBlock("USER", "$MARKER Tell the caller in one short sentence what they said earlier. Do not ask anything else.", interactive = true)
    quietAfter(s2, j0, 25_000)
    run { val fa = s2.events.drop(j0).firstOrNull { it.kind == "audioOutput" }?.tMs; if (fa != null) { val until = fa + s2.audioDurMs(j0) + 2000; while (s2.nowMs() < until) delay(50) } }
    val spoken = s2.finalAssistantText(j0)
    log.log("history_recall", mapOf("spoken" to spoken, "mentionsDelivery" to (spoken.contains("deliver", true) || spoken.contains("parcel", true)), "s2Error" to s2.error))
    f2.stop(); s2.end(); delay(2000)
    log.log("history_done", mapOf("s1Ended" to s1.streamEnded, "s1Error" to s1.error, "s2Ended" to s2.streamEnded, "s2Error" to s2.error))
    s1.close(); s2.close()
}

suspend fun long(client: software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient, runDir: File, scope: CoroutineScope) {
    val s = startSession("long", client, runDir, scope)
    val feeder = Feeder(s, scope)
    // idle session: audio content opened, no frames, no further input
    val idle = startSession("idle", client, runDir, scope)
    log.log("long_start", mapOf("note" to "long streams silence plus a clip every 90 s; idle sends nothing after audio contentStart"))
    val limitMs = 8.5 * 60 * 1000
    var nextClip = 30_000L
    var clipN = 0
    val clips = listOf("en_01", "en_02", "en_05", "en_06", "en_07", "en_08")
    var lastReport = 0L
    while (s.nowMs() < limitMs && !s.streamEnded) {
        if (s.nowMs() >= nextClip) {
            val c = clips[clipN % clips.size]; clipN++
            feeder.play(c, loadWav(c)); nextClip += 90_000
        }
        if (s.nowMs() - lastReport > 30_000) {
            lastReport = s.nowMs()
            log.log("long_tick", mapOf("tSec" to s.nowMs() / 1000, "longEnded" to s.streamEnded, "idleEnded" to idle.streamEnded, "idleErr" to idle.error))
        }
        delay(200)
    }
    log.log("long_exit", mapOf("tMs" to s.nowMs(), "ended" to s.streamEnded, "finishedMs" to s.finishedMs, "error" to s.error,
        "lastEvents" to s.events.takeLast(4).joinToString { it.kind + ":" + it.obj.toString().take(300) }))
    feeder.stop()
    if (!s.streamEnded) { s.end(); delay(3000) }
    // idle result
    log.log("idle_result", mapOf("ended" to idle.streamEnded, "finishedMs" to idle.finishedMs, "error" to idle.error,
        "lastEvents" to idle.events.takeLast(4).joinToString { it.kind + ":" + it.obj.toString().take(300) }))
    if (!idle.streamEnded) { idle.end(); delay(2000) }
    s.close(); idle.close()
}
