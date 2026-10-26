package com.manjul.kbapi.evals

import com.manjul.kbapi.limits.ModelPrice
import com.manjul.kbapi.limits.TokenUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
data class TokenTotals(
    val input: Long = 0,
    val output: Long = 0,
    @SerialName("cache_read") val cacheRead: Long = 0,
    @SerialName("cache_write") val cacheWrite: Long = 0,
) {
    operator fun plus(o: TokenTotals) =
        TokenTotals(input + o.input, output + o.output, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite)

    fun toUsage() = TokenUsage(input, output, cacheRead, cacheWrite)

    companion object {
        fun of(u: TokenUsage) = TokenTotals(u.input, u.output, u.cacheRead, u.cacheWrite)
    }
}

/** The judge's verdict on one output. [reason] is a code from [Judge.REASONS], never free text. */
@Serializable
data class JudgeVerdict(val pass: Boolean, val score: Int, val reason: String)

/**
 * One case's result. [output] is the model's answer to our authored input, kept so a reviewer can read
 * failures; eval cases are not user content.
 */
@Serializable
data class CaseResult(
    val id: String,
    val lang: String,
    val mode: String? = null,
    val passed: Boolean,
    val checks: Map<String, Boolean> = emptyMap(),
    val error: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("latency_ms") val latencyMs: Long? = null,
    @SerialName("first_text_ms") val firstTextMs: Long? = null,
    val tokens: TokenTotals? = null,
    val judge: JudgeVerdict? = null,
    @SerialName("judge_tokens") val judgeTokens: TokenTotals? = null,
    val output: String? = null,
)

@Serializable
data class PassStats(val cases: Int, val passed: Int, @SerialName("pass_rate") val passRate: Double)

@Serializable
data class LatencyStats(val median: Long, val p90: Long)

/**
 * One eval run (decision 15): per-language pass rates, per-check pass rates, median and p90 latency of
 * the whole call and of the first text delta, the cache-read share of input tokens, and the cost from
 * measured usage at list prices.
 */
@Serializable
data class EvalReport(
    val prompt: String,
    val model: String,
    val judge: String? = null,
    @SerialName("created_at") val createdAt: String,
    val cases: Int,
    val passed: Int,
    @SerialName("pass_rate") val passRate: Double,
    @SerialName("by_lang") val byLang: Map<String, PassStats>,
    @SerialName("by_check") val byCheck: Map<String, Double>,
    @SerialName("latency_ms") val latencyMs: LatencyStats? = null,
    @SerialName("first_text_ms") val firstTextMs: LatencyStats? = null,
    @SerialName("cache_read_share") val cacheReadShare: Double,
    val tokens: TokenTotals,
    @SerialName("judge_tokens") val judgeTokens: TokenTotals? = null,
    @SerialName("cost_usd") val costUsd: Double,
    val errors: Map<String, Int>,
    val results: List<CaseResult>,
) {
    companion object {
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun toJson(report: EvalReport): String = json.encodeToString(serializer(), report)

        fun build(
            prompt: String,
            model: String,
            judge: String?,
            createdAt: String,
            results: List<CaseResult>,
            prices: Map<String, ModelPrice> = ModelPrice.DEFAULTS,
        ): EvalReport {
            fun stats(rs: List<CaseResult>) =
                PassStats(rs.size, rs.count { it.passed }, if (rs.isEmpty()) 0.0 else rs.count { it.passed }.toDouble() / rs.size)

            val all = stats(results)
            val checkNames = results.flatMap { it.checks.keys }.distinct()
            val byCheck = checkNames.associateWith { name ->
                val graded = results.mapNotNull { it.checks[name] }
                graded.count { it }.toDouble() / graded.size
            }
            val ok = results.filter { it.error == null }
            val tokens = results.mapNotNull { it.tokens }.fold(TokenTotals()) { a, b -> a + b }
            val judgeTokens = results.mapNotNull { it.judgeTokens }.takeIf { it.isNotEmpty() }?.fold(TokenTotals()) { a, b -> a + b }
            val inputSide = tokens.input + tokens.cacheRead + tokens.cacheWrite
            val cost = ModelPrice.lookup(prices, model).cost(tokens.toUsage()) +
                (if (judge != null && judgeTokens != null) ModelPrice.lookup(prices, judge).cost(judgeTokens.toUsage()) else 0.0)
            return EvalReport(
                prompt = prompt,
                model = model,
                judge = judge,
                createdAt = createdAt,
                cases = all.cases,
                passed = all.passed,
                passRate = all.passRate,
                byLang = results.groupBy { it.lang }.toSortedMap().mapValues { (_, rs) -> stats(rs) },
                byCheck = byCheck,
                latencyMs = latency(ok.mapNotNull { it.latencyMs }),
                firstTextMs = latency(ok.mapNotNull { it.firstTextMs }),
                cacheReadShare = if (inputSide == 0L) 0.0 else tokens.cacheRead.toDouble() / inputSide,
                tokens = tokens,
                judgeTokens = judgeTokens,
                costUsd = cost,
                errors = results.mapNotNull { it.error }.groupingBy { it }.eachCount().toSortedMap(),
                results = results,
            )
        }

        /** Nearest-rank percentiles. */
        fun latency(values: List<Long>): LatencyStats? {
            if (values.isEmpty()) return null
            val s = values.sorted()
            fun pct(p: Double) = s[(Math.ceil(p * s.size).toInt() - 1).coerceIn(0, s.lastIndex)]
            return LatencyStats(median = pct(0.5), p90 = pct(0.9))
        }

        fun summary(r: EvalReport): String = buildString {
            fun pct(d: Double) = String.format(Locale.ROOT, "%.1f%%", d * 100)
            appendLine("${r.prompt} on ${r.model}${r.judge?.let { " (judge $it)" } ?: ""}: ${r.passed}/${r.cases} passed (${pct(r.passRate)})")
            for ((lang, s) in r.byLang) appendLine("  $lang: ${s.passed}/${s.cases} (${pct(s.passRate)})")
            appendLine("  checks: " + r.byCheck.entries.joinToString(", ") { "${it.key} ${pct(it.value)}" })
            r.latencyMs?.let { appendLine("  latency ms: median ${it.median}, p90 ${it.p90}") }
            r.firstTextMs?.let { appendLine("  first text ms: median ${it.median}, p90 ${it.p90}") }
            appendLine("  cache-read share: ${pct(r.cacheReadShare)}")
            appendLine(String.format(Locale.ROOT, "  cost: \$%.4f", r.costUsd))
            if (r.errors.isNotEmpty()) appendLine("  errors: " + r.errors.entries.joinToString(", ") { "${it.key} ${it.value}" })
        }
    }
}

/**
 * The pre-run estimate from the planning token profile in TRD §4.4.4 (used by §11.4): per case the cached
 * prefix, fresh input and output (thinking included), plus one cache write of the prefix; the judge adds
 * a short uncached call per case.
 */
object CostEstimate {
    private data class Profile(val cached: Long, val fresh: Long, val output: Long)

    private val Profiles = mapOf(
        "rewrite" to Profile(1500, 120, 250),
        "polish_translate" to Profile(1800, 200, 500),
        "multi_tone" to Profile(1500, 150, 700),
    )
    private const val JUDGE_SYSTEM_TOKENS = 700L
    private const val JUDGE_OUTPUT_TOKENS = 300L

    fun usd(prompt: String, model: String, cases: Int, judgeModel: String?, prices: Map<String, ModelPrice> = ModelPrice.DEFAULTS): Double {
        val p = Profiles[prompt.substringBefore('@')] ?: throw IllegalArgumentException("unknown prompt")
        val price = ModelPrice.lookup(prices, model)
        val perCase = price.cost(TokenUsage(input = p.fresh, output = p.output, cacheRead = p.cached, cacheWrite = 0))
        val firstWrite = price.cost(TokenUsage(input = 0, output = 0, cacheRead = 0, cacheWrite = p.cached))
        val judge = judgeModel?.let {
            ModelPrice.lookup(prices, it).cost(
                TokenUsage(input = JUDGE_SYSTEM_TOKENS + 2 * p.fresh + p.output, output = JUDGE_OUTPUT_TOKENS, cacheRead = 0, cacheWrite = 0),
            )
        } ?: 0.0
        return cases * (perCase + judge) + if (cases > 0) firstWrite else 0.0
    }
}
