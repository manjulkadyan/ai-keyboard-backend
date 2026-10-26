package com.manjul.kbapi.ai

import com.manjul.kbapi.limits.TokenUsage
import com.manjul.kbapi.testing.started
import com.manjul.kbapi.testing.text
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

class AiRunnerTest {
    private class Recorder : CallHooks {
        val events = CopyOnWriteArrayList<String>()
        val secondText = CompletableDeferred<Unit>()
        private var texts = 0

        override suspend fun onDone(model: String, done: GatewayEvent.Done) { events += "done" }
        override fun onError(code: GatewayErrorCode) { events += "error" }
        override fun onUsage(model: String, usage: TokenUsage) { events += "usage" }
        override fun onText(chars: Int) {
            events += "text"
            if (++texts == 2) secondText.complete(Unit)
        }
        override suspend fun onUnfinished(model: String) { events += "unfinished:$model" }
    }

    private val req = GatewayRequest("claude-haiku-5-5", "sys", "user", 100, null)

    @Test
    fun `a stream cancelled by a client disconnect reports the call as unfinished`() = runBlocking {
        val gateway = ModelGateway {
            flow {
                emit(started())
                emit(text("a"))
                emit(text("b"))
                awaitCancellation()
            }
        }
        val runner = AiRunner(gateway, "claude-sonnet-5-5")
        val hooks = Recorder()
        val job = launch {
            val opened = runner.openStreaming(this, req, hooks)
            runner.drain(opened, hooks) { }
        }
        hooks.secondText.await()
        job.cancelAndJoin()
        assertEquals(listOf("usage", "text", "text", "unfinished:claude-haiku-5-5"), hooks.events)
    }

    @Test
    fun `a completed stream is not unfinished`() = runBlocking {
        val gateway = FakeGateway(listOf(started(), text("a"), GatewayEvent.Done("end_turn", TokenUsage(1, 2, 0, 0))))
        val runner = AiRunner(gateway, "claude-sonnet-5-5")
        val hooks = Recorder()
        launch {
            val opened = runner.openStreaming(this, req, hooks)
            runner.drain(opened, hooks) { }
        }.join()
        assertEquals(listOf("usage", "text", "done"), hooks.events)
    }

    @Test
    fun `a refused attempt that steps up is not unfinished`() = runBlocking {
        val gateway = FakeGateway.calls(
            FakeCall(listOf(started(), GatewayEvent.Done("refusal", TokenUsage(1, 2, 0, 0)))),
            FakeCall(listOf(started(), text("ok"), GatewayEvent.Done("end_turn", TokenUsage(1, 2, 0, 0)))),
        )
        val runner = AiRunner(gateway, "claude-sonnet-5-5")
        val hooks = Recorder()
        launch {
            val opened = runner.openStreaming(this, req, hooks)
            runner.drain(opened, hooks) { }
        }.join()
        assertEquals(listOf("usage", "done", "usage", "text", "done"), hooks.events)
    }
}
