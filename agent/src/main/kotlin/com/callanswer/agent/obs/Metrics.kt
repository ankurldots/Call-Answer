package com.callanswer.agent.obs

import com.callanswer.agent.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Metrics as CloudWatch embedded metric format lines. No dimension carries a user or call identifier. */
class Metrics(
    private val clock: Clock,
    private val out: (String) -> Unit = { System.out.println(it) },
    private val namespace: String = "CallAnswer/Agent",
) {
    enum class Metric(val unit: String) {
        CallCount("Count"),
        TimeToFirstAudioMs("Milliseconds"),
        ReplyLatencyMs("Milliseconds"),
        FallbackCount("Count"),
        SonicErrorCount("Count"),
        ActiveCalls("Count"),
        CapRefusals("Count"),
    }

    fun callStarted() = emit(Metric.CallCount, 1.0)
    fun timeToFirstAudio(ms: Long) = emit(Metric.TimeToFirstAudioMs, ms.toDouble())
    fun replyLatency(ms: Long) = emit(Metric.ReplyLatencyMs, ms.toDouble())
    fun fallback() = emit(Metric.FallbackCount, 1.0)
    fun sonicError() = emit(Metric.SonicErrorCount, 1.0)
    fun activeCalls(n: Int) = emit(Metric.ActiveCalls, n.toDouble())
    fun capRefusal() = emit(Metric.CapRefusals, 1.0)

    fun emit(metric: Metric, value: Double) {
        val obj = buildJsonObject {
            put(
                "_aws",
                buildJsonObject {
                    put("Timestamp", JsonPrimitive(clock.nowMillis()))
                    put(
                        "CloudWatchMetrics",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("Namespace", JsonPrimitive(namespace))
                                    put("Dimensions", JsonArray(listOf(JsonArray(emptyList()))))
                                    put(
                                        "Metrics",
                                        JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put("Name", JsonPrimitive(metric.name))
                                                    put("Unit", JsonPrimitive(metric.unit))
                                                },
                                            ),
                                        ),
                                    )
                                },
                            ),
                        ),
                    )
                },
            )
            put(metric.name, JsonPrimitive(value))
        }
        out(Json.encodeToString(JsonObject.serializer(), obj))
    }
}
