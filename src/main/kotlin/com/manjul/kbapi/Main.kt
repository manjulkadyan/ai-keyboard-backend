package com.manjul.kbapi

import com.manjul.kbapi.auth.AppCheckVerifier
import com.manjul.kbapi.auth.AuthChain
import com.manjul.kbapi.auth.FirebaseIdTokenVerifier
import com.manjul.kbapi.auth.IdTokenVerifier
import com.manjul.kbapi.auth.JwksAppCheckVerifier
import com.google.cloud.firestore.FirestoreOptions
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.manjul.kbapi.ai.AiRunner
import com.manjul.kbapi.ai.AnthropicGateway
import com.manjul.kbapi.ai.FakeGateway
import com.manjul.kbapi.ai.ModelGateway
import com.manjul.kbapi.ai.PolishService
import com.manjul.kbapi.ai.PromptRegistry
import com.manjul.kbapi.ai.RewriteService
import com.manjul.kbapi.ai.UnavailableGateway
import com.manjul.kbapi.account.FirebaseAuthUserDeleter
import com.manjul.kbapi.account.FirebaseUserDeleter
import com.manjul.kbapi.billing.EntitlementService
import com.manjul.kbapi.billing.EntitlementSigner
import com.manjul.kbapi.billing.GooglePlayDeveloperApi
import com.manjul.kbapi.billing.PlayDeveloperApi
import com.manjul.kbapi.billing.PubSubOidcVerifier
import com.manjul.kbapi.limits.Limits
import com.manjul.kbapi.routes.accountRoutes
import com.manjul.kbapi.routes.entitlementRoutes
import com.manjul.kbapi.routes.rtdnRoutes
import com.manjul.kbapi.routes.staticRoutes
import com.manjul.kbapi.report.FirestoreReportStore
import com.manjul.kbapi.report.InMemoryReportStore
import com.manjul.kbapi.report.ReportStore
import com.manjul.kbapi.routes.polishRoutes
import com.manjul.kbapi.routes.reportRoutes
import com.manjul.kbapi.routes.rewriteRoutes
import com.manjul.kbapi.routes.healthRoutes
import com.manjul.kbapi.routes.quotaRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Everything the module needs; tests swap verifiers, the clock, the limit stores, the model gateway and the
 * billing fakes. The billing dependencies are nullable: a missing one makes its routes fail closed (503 for
 * entitlement and delete, 401 for RTDN) instead of failing startup.
 */
data class Services(
    val config: Config,
    val appCheck: AppCheckVerifier,
    val idTokens: IdTokenVerifier,
    val clock: Clock = Clock.systemUTC(),
    val limits: Limits = Limits.inMemory(config, clock),
    val gateway: ModelGateway = UnavailableGateway,
    val prompts: PromptRegistry = PromptRegistry(),
    val reports: ReportStore = InMemoryReportStore(),
    val play: PlayDeveloperApi? = null,
    val signer: EntitlementSigner? = null,
    val pushAuth: PubSubOidcVerifier? = null,
    val userDeleter: FirebaseUserDeleter? = null,
    val playRetryDelaysMs: List<Long> = listOf(200L, 1000L),
) {
    private val entitlementService: EntitlementService? by lazy {
        val api = play ?: return@lazy null
        val key = signer ?: return@lazy null
        EntitlementService(api, limits.entitlements, key, clock, retryDelaysMs = playRetryDelaysMs)
    }

    /** The shared service, or null when Play or the signing key is not configured. */
    fun entitlementServiceOrNull(): EntitlementService? = entitlementService

    /** The shared service. Throws [ApiException] 503 `unavailable` when billing is not configured. */
    fun entitlementService(): EntitlementService = entitlementService ?: throw ApiException(Errors.unavailable())

    companion object {
        /**
         * Limit stores are Firestore when [gcpProject] (`GOOGLE_CLOUD_PROJECT`) is set, else in-memory
         * (single instance, local runs only).
         */
        fun production(config: Config, clock: Clock = Clock.systemUTC(), gcpProject: String? = null): Services {
            val db = if (gcpProject.isNullOrBlank()) {
                null
            } else {
                FirestoreOptions.getDefaultInstance().toBuilder().setProjectId(gcpProject).build().service
            }
            val limits = db?.let { Limits.firestore(config, clock, it) } ?: Limits.inMemory(config, clock)
            val reports = db?.let { FirestoreReportStore(it) } ?: InMemoryReportStore()
            val gateway = gateway(config)
            val billing = billing(config, clock, db != null)
            return when (config.authMode) {
                AuthMode.FIREBASE -> Services(
                    config = config,
                    appCheck = JwksAppCheckVerifier(JwksAppCheckVerifier.DEFAULT_JWKS_URL, config.firebaseProjectNumber, clock),
                    idTokens = FirebaseIdTokenVerifier.create(config.firebaseProjectId),
                    clock = clock,
                    limits = limits,
                    gateway = gateway,
                    reports = reports,
                    play = billing.play,
                    signer = billing.signer,
                    pushAuth = billing.pushAuth,
                    userDeleter = FirebaseAuthUserDeleter(FirebaseIdTokenVerifier.firebaseAuth(config.firebaseProjectId)),
                )
                // Dev mode never consults the verifiers; reject everything to be safe.
                AuthMode.DEV -> Services(
                    config, AppCheckVerifier { false }, IdTokenVerifier { null }, clock, limits,
                    gateway = gateway, reports = reports,
                    play = billing.play, signer = billing.signer, pushAuth = billing.pushAuth,
                )
            }
        }
    }
}

private class Billing(val play: PlayDeveloperApi?, val signer: EntitlementSigner?, val pushAuth: PubSubOidcVerifier?)

/**
 * Play is called only on GCP (ADC is the kb-api service account). The signing key and the RTDN audience come
 * from the environment; any one unset leaves its routes failing closed.
 */
private fun billing(config: Config, clock: Clock, onGcp: Boolean): Billing = Billing(
    play = if (onGcp) GooglePlayDeveloperApi.fromApplicationDefault(config.playPackageName) else null,
    signer = config.entitlementSigningKey?.let { EntitlementSigner(it) },
    pushAuth = if (config.rtdnAudience != null && config.rtdnServiceAccount != null) {
        PubSubOidcVerifier(config.rtdnAudience, config.rtdnServiceAccount, PubSubOidcVerifier.GOOGLE_JWKS_URL, clock)
    } else {
        null
    },
)

/** The key is read from the environment only (Secret Manager in prod); without it AI calls fail closed. */
private fun gateway(config: Config): ModelGateway = when {
    config.gateway == GatewayMode.FAKE -> FakeGateway.demo()
    config.anthropicApiKey == null -> UnavailableGateway
    else -> AnthropicGateway(AnthropicOkHttpClient.builder().apiKey(config.anthropicApiKey).build())
}

val KbJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

fun Application.kbApi(services: Services, extraRoutes: Route.() -> Unit = {}) {
    // Order matters: the request id is set before anything can respond.
    install(RequestId)
    install(AccessLog)
    install(ContentNegotiation) { json(KbJson) }
    Errors.install(this)
    install(AuthChain) {
        mode = services.config.authMode
        appCheck = services.appCheck
        idTokens = services.idTokens
        // Decision 6: a real App Check pass refreshes the integrity verdict. Dev mode never reaches this.
        onVerified = { uid -> services.limits.integrity.record(uid, services.clock.millis()) }
    }
    routing {
        healthRoutes()
        quotaRoutes(services)
        val runner = AiRunner(services.gateway, services.config.stepUpModel)
        rewriteRoutes(RewriteService(services.config, services.prompts, services.limits, runner))
        polishRoutes(PolishService(services.config, services.prompts, services.limits, runner))
        reportRoutes(services)
        entitlementRoutes(services)
        accountRoutes(services)
        rtdnRoutes(services)
        staticRoutes(services)
        extraRoutes()
    }
}

fun main() {
    val config = Config.fromEnv(System.getenv())
    val log = LoggerFactory.getLogger("kbapi")
    val gcpProject = System.getenv("GOOGLE_CLOUD_PROJECT")?.trim()?.takeIf { it.isNotEmpty() }
    log.info(
        "starting kb-api auth_mode={} ai_enabled={} limits_store={} gateway={} anthropic_key={} " +
            "entitlement_key={} rtdn_push_auth={}",
        config.authMode, config.aiEnabled, if (gcpProject == null) "memory" else "firestore", config.gateway,
        if (config.anthropicApiKey == null) "unset" else "set",
        if (config.entitlementSigningKey == null) "unset" else "set",
        if (config.rtdnAudience == null) "unset" else "set",
    )
    val services = Services.production(config, gcpProject = gcpProject)
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { kbApi(services) }.start(wait = true)
}
