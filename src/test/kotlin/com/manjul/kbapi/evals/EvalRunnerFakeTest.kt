package com.manjul.kbapi.evals

import com.manjul.kbapi.ai.GatewayErrorCode
import com.manjul.kbapi.ai.GatewayEvent
import com.manjul.kbapi.ai.GatewayException
import com.manjul.kbapi.ai.GatewayRequest
import com.manjul.kbapi.ai.ModelGateway
import com.manjul.kbapi.limits.TokenUsage
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvalRunnerFakeTest {
    private val usage = TokenUsage(input = 100, output = 30, cacheRead = 1500, cacheWrite = 0)

    private fun case(id: String, lang: String, input: String, mustKeep: List<String> = emptyList(), mode: String = "fix") =
        EvalCase(
            id = id, prompt = "rewrite@1", lang = lang, mode = mode, modes = null, target = null, script = null,
            input = input, mustKeep = mustKeep, expectLang = lang, notes = null,
        )

    private val cases = listOf(
        case("t-en-1", "en", "im late, be ther at 5pm", listOf("5pm")),
        case("t-en-2", "en", "call @sam about https://x.y", listOf("@sam", "https://x.y")),
        case("t-es-1", "es", "nos vemos mañana en casa de Ana", listOf("Ana")),
        case("t-es-2", "es", "llego tarde a la reunión de las 6", listOf("6")),
        case("t-ar-1", "ar", "مبروك على الوظيفة الجديدة يا سارة"),
    )

    /** A scripted model: echoes the text for most cases, prefaces t-en-2, fails t-es-2 upstream. Never touches a network. */
    private class ScriptedGateway : ModelGateway {
        val requests = CopyOnWriteArrayList<GatewayRequest>()
        override fun stream(req: GatewayRequest) = flow {
            requests += req
            val text = Regex("<text>(.*)</text>", RegexOption.DOT_MATCHES_ALL).find(req.user)!!.groupValues[1]
                .replace("&lt;", "<").replace("&gt;", ">")
            when {
                "@sam" in text -> emit(GatewayEvent.TextDelta("Sure! Here it is: $text"))
                "reunión" in text -> throw GatewayException(GatewayErrorCode.OVERLOADED)
                else -> {
                    emit(GatewayEvent.TextDelta(text.take(5)))
                    emit(GatewayEvent.TextDelta(text.drop(5)))
                }
            }
            emit(GatewayEvent.Done("end_turn", TokenUsage(input = 100, output = 30, cacheRead = 1500, cacheWrite = 0)))
        }
    }

    @Test
    fun `five cases on a fake gateway produce a report with pass rates`() = runBlocking {
        val gateway = ScriptedGateway()
        val report = EvalRunner(gateway, concurrency = 2).run(cases, prompt = "rewrite@1", model = "claude-haiku-5-5")

        assertEquals(5, gateway.requests.size)
        assertTrue(gateway.requests.all { it.model == "claude-haiku-5-5" && it.maxTokens == 1024L && it.jsonSchema == null })
        assertEquals(5, report.cases)
        assertEquals(3, report.passed)
        assertEquals(0.6, report.passRate, 1e-9)
        assertEquals(0.5, report.byLang.getValue("en").passRate, 1e-9)
        assertEquals(0.5, report.byLang.getValue("es").passRate, 1e-9)
        assertEquals(1.0, report.byLang.getValue("ar").passRate, 1e-9)
        assertEquals(mapOf("upstream_overloaded" to 1), report.errors)
        assertFalse(report.results.single { it.id == "t-en-2" }.checks.getValue("no_preface"))
        assertNotNull(report.latencyMs)
        assertEquals(1500.0 / 1600.0, report.cacheReadShare, 1e-9)
        assertTrue(report.costUsd > 0.0)
        assertNull(report.judge)

        val json = Json.parseToJsonElement(EvalReport.toJson(report)).jsonObject
        assertTrue("by_lang" in json && "latency_ms" in json && "cache_read_share" in json)
    }

    @Test
    fun `a judge verdict is part of the pass`() = runBlocking {
        val judge = Judge(
            ModelGateway { req ->
                flow {
                    val pass = "Ana" !in req.user
                    emit(GatewayEvent.TextDelta("""{"pass":$pass,"score":${if (pass) 5 else 2},"reason":"${if (pass) "ok" else "meaning_changed"}"}"""))
                    emit(GatewayEvent.Done("end_turn", usage))
                }
            },
            model = "claude-opus-5-5",
        )
        val report = EvalRunner(ScriptedGateway(), judge = judge).run(cases, prompt = "rewrite@1", model = "claude-haiku-5-5")
        assertEquals("claude-opus-5-5", report.judge)
        assertEquals(2, report.passed)
        assertFalse(report.results.single { it.id == "t-es-1" }.checks.getValue("judge"))
    }

    @Test
    fun `the CLI refuses without a key and spends nothing without --yes`() {
        var built = 0
        val factory = { _: String -> built++; ScriptedGateway() as ModelGateway }
        val casesDir = Files.createTempDirectory("evalcases").toFile()
        File(casesDir, "rewrite@1.jsonl").writeText(
            cases.joinToString("\n") { EvalCases.encode(it) } + "\n",
        )
        val outFile = File(casesDir, "out.json")
        fun run(vararg args: String, env: Map<String, String>): Pair<Int, String> {
            val buf = ByteArrayOutputStream()
            val code = EvalCli.run(
                arrayOf(*args), env, PrintStream(buf, true, Charsets.UTF_8), factory, casesDir, LocalDate.of(2026, 10, 26),
            )
            return code to buf.toString(Charsets.UTF_8)
        }

        val (noKey, noKeyOut) = run("--prompt", "rewrite@1", "--model", "claude-haiku-5-5", "--yes", env = emptyMap())
        assertEquals(EvalCli.EXIT_NO_KEY, noKey)
        assertTrue("ANTHROPIC_API_KEY" in noKeyOut)
        assertEquals(0, built)

        val env = mapOf("ANTHROPIC_API_KEY" to "test-not-a-key")
        val (noYes, noYesOut) = run("--prompt", "rewrite@1", "--model", "claude-haiku-5-5", "--limit", "3", env = env)
        assertEquals(EvalCli.EXIT_NEEDS_YES, noYes)
        assertTrue("Estimated cost" in noYesOut && "--yes" in noYesOut)
        assertEquals(0, built)

        val (ok, okOut) = run(
            "--prompt", "rewrite@1", "--model", "claude-haiku-5-5", "--limit", "3", "--yes", "--out", outFile.path, env = env,
        )
        assertEquals(0, ok, okOut)
        assertEquals(1, built)
        val written = Json.parseToJsonElement(outFile.readText()).jsonObject
        assertEquals("3", written["cases"].toString())
        assertFalse("test-not-a-key" in okOut || "test-not-a-key" in outFile.readText())
    }

    @Test
    fun `cost estimate uses the token profile and model price`() {
        val haiku = CostEstimate.usd("rewrite@1", "claude-haiku-5-5", cases = 300, judgeModel = null)
        val opus = CostEstimate.usd("rewrite@1", "claude-opus-5-5", cases = 300, judgeModel = null)
        val judged = CostEstimate.usd("rewrite@1", "claude-haiku-5-5", cases = 300, judgeModel = "claude-opus-5-5")
        assertTrue(haiku > 0.0 && opus > haiku * 10 && judged > haiku)
        assertEquals("evals/results/2026-10-26-rewrite@1-claude-haiku-5-5.json", EvalCli.defaultOut(LocalDate.of(2026, 10, 26), "rewrite@1", "claude-haiku-5-5"))
    }
}
