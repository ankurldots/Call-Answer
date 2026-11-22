package com.callanswer.agent.sonic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.reactivestreams.Publisher
import org.reactivestreams.Subscription
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamInput
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamRequest
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamResponseHandler
import java.time.Duration

/** Receives what comes from one bidirectional stream. Calls may come from SDK threads. */
interface SonicStreamListener {
    /** One output event as JSON text. */
    fun onEvent(text: String)

    /** The stream failed (for example `ModelTimeoutException` at the 8-minute hard cut). */
    fun onError(t: Throwable)

    /** The stream ended without an error. */
    fun onClosed()
}

/** The write side of one open stream. */
interface SonicStream {
    /** Queues one input event (JSON text). Never blocks. */
    fun send(event: String)

    /** Completes the input side of the stream. */
    fun close()
}

/** Opens streams. The unit tests inject a fake; production uses [SonicClient]. */
fun interface SonicStreamFactory {
    /** Opens a stream; throws when the start fails. */
    fun open(listener: SonicStreamListener): SonicStream
}

/**
 * Wraps `InvokeModelWithBidirectionalStream` of Bedrock Runtime. The model is `amazon.nova-2-sonic-v1:0`
 * in `ap-northeast-1`; no inference profile is needed (spike C-5).
 */
class SonicClient(
    private val client: BedrockRuntimeAsyncClient,
    private val scope: CoroutineScope,
    private val modelId: String = MODEL_ID,
) : SonicStreamFactory {

    override fun open(listener: SonicStreamListener): SonicStream {
        val outbox = Channel<InvokeModelWithBidirectionalStreamInput>(Channel.UNLIMITED)
        val publisher = Publisher<InvokeModelWithBidirectionalStreamInput> { sub ->
            val demand = Channel<Long>(Channel.UNLIMITED)
            sub.onSubscribe(object : Subscription {
                override fun request(n: Long) { demand.trySend(n) }
                override fun cancel() { demand.close() }
            })
            scope.launch(Dispatchers.IO) {
                try {
                    for (n in demand) {
                        var left = n
                        while (left > 0) {
                            val r = outbox.receiveCatching()
                            if (r.isClosed) { sub.onComplete(); return@launch }
                            sub.onNext(r.getOrThrow())
                            if (left != Long.MAX_VALUE) left--
                        }
                    }
                } catch (_: Throwable) {
                    // the response handler reports the stream failure
                }
            }
        }
        val visitor = InvokeModelWithBidirectionalStreamResponseHandler.Visitor.builder()
            .onChunk { chunk -> listener.onEvent(chunk.bytes().asUtf8String()) }
            .build()
        val handler = InvokeModelWithBidirectionalStreamResponseHandler.builder()
            .onResponse { }
            .onError { t -> listener.onError(t) }
            .subscriber(visitor)
            .build()
        val request = InvokeModelWithBidirectionalStreamRequest.builder().modelId(modelId).build()
        val future = client.invokeModelWithBidirectionalStream(request, publisher, handler)
        future.whenComplete { _, t -> if (t == null) listener.onClosed() else listener.onError(t) }
        return object : SonicStream {
            override fun send(event: String) {
                outbox.trySend(
                    InvokeModelWithBidirectionalStreamInput.chunkBuilder().bytes(SdkBytes.fromUtf8String(event)).build()
                )
            }

            override fun close() { outbox.close() }
        }
    }

    companion object {
        const val MODEL_ID = "amazon.nova-2-sonic-v1:0"
        const val REGION = "ap-northeast-1"

        /** A Bedrock client with timeouts that suit an 8-minute stream. */
        fun buildBedrockClient(region: String = REGION): BedrockRuntimeAsyncClient =
            BedrockRuntimeAsyncClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(
                    NettyNioAsyncHttpClient.builder()
                        .connectionAcquisitionTimeout(Duration.ofSeconds(30))
                        .readTimeout(Duration.ofMinutes(15))
                        .writeTimeout(Duration.ofMinutes(15))
                        .connectionTimeout(Duration.ofSeconds(15))
                )
                .build()
    }
}
