package com.manjul.kbapi.ai

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The SSE contract of TRD §5.3:
 * - `event: delta` / `data: {"text": "..."}` per text delta;
 * - then `event: done` with the endpoint's summary (`quota_left`, `prompt`, ...);
 * - or `event: error` / `data: {"error": "refused"|"overloaded"|"internal"}` once streaming has started.
 */
object SseRelay {
    fun frame(event: String, data: JsonObject): String = "event: $event\ndata: $data\n\n"

    fun delta(text: String) = frame("delta", JsonObject(mapOf("text" to JsonPrimitive(text))))

    fun error(code: String) = frame("error", JsonObject(mapOf("error" to JsonPrimitive(code))))

    /**
     * Commits 200 `text/event-stream` and relays [opened]. [finish] turns the whole text and the final
     * `Done` into the `done` payload, or returns null when the output is unusable (sent as `internal`).
     */
    suspend fun respond(
        call: ApplicationCall,
        opened: Opened.Streaming,
        runner: AiRunner,
        hooks: CallHooks,
        finish: (text: String, done: GatewayEvent.Done) -> JsonObject?,
    ) {
        call.response.header("Cache-Control", "no-cache")
        call.response.header("X-Accel-Buffering", "no")
        // The writer may run after respondBytesWriter returns, so the stream is closed by drain(), not here.
        call.respondBytesWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
            val full = StringBuilder(opened.first)
            send(delta(opened.first))
            val last = runner.drain(opened, hooks) { text ->
                full.append(text)
                send(delta(text))
            }
            when (last) {
                is Outcome.Failed -> send(error(AiRunner.streamErrorCode(last.code)))
                is Outcome.Completed -> when {
                    // Text was already shown, so there is no step-up: the client keeps the original.
                    last.done.stopReason == StopReasons.REFUSAL -> send(error("refused"))
                    else -> send(finish(full.toString(), last.done)?.let { frame("done", it) } ?: error("internal"))
                }
            }
        }
    }

    private suspend fun ByteWriteChannel.send(frame: String) {
        writeStringUtf8(frame)
        flush()
    }
}
