package com.manjul.kbapi

import com.manjul.kbapi.auth.AppCheckVerifier
import com.manjul.kbapi.auth.AuthChain
import com.manjul.kbapi.auth.FirebaseIdTokenVerifier
import com.manjul.kbapi.auth.IdTokenVerifier
import com.manjul.kbapi.auth.JwksAppCheckVerifier
import com.google.cloud.firestore.FirestoreOptions
import com.manjul.kbapi.limits.Limits
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

/** Everything the module needs; tests swap verifiers, the clock and the limit stores. */
data class Services(
    val config: Config,
    val appCheck: AppCheckVerifier,
    val idTokens: IdTokenVerifier,
    val clock: Clock = Clock.systemUTC(),
    val limits: Limits = Limits.inMemory(config, clock),
) {
    companion object {
        /**
         * Limit stores are Firestore when [gcpProject] (`GOOGLE_CLOUD_PROJECT`) is set, else in-memory
         * (single instance, local runs only).
         */
        fun production(config: Config, clock: Clock = Clock.systemUTC(), gcpProject: String? = null): Services {
            val limits = if (gcpProject.isNullOrBlank()) {
                Limits.inMemory(config, clock)
            } else {
                val db = FirestoreOptions.getDefaultInstance().toBuilder().setProjectId(gcpProject).build().service
                Limits.firestore(config, clock, db)
            }
            return when (config.authMode) {
                AuthMode.FIREBASE -> Services(
                    config = config,
                    appCheck = JwksAppCheckVerifier(JwksAppCheckVerifier.DEFAULT_JWKS_URL, config.firebaseProjectNumber, clock),
                    idTokens = FirebaseIdTokenVerifier.create(config.firebaseProjectId),
                    clock = clock,
                    limits = limits,
                )
                // Dev mode never consults the verifiers; reject everything to be safe.
                AuthMode.DEV -> Services(config, AppCheckVerifier { false }, IdTokenVerifier { null }, clock, limits)
            }
        }
    }
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
    }
    routing {
        healthRoutes()
        quotaRoutes(services)
        extraRoutes()
    }
}

fun main() {
    val config = Config.fromEnv(System.getenv())
    val log = LoggerFactory.getLogger("kbapi")
    val gcpProject = System.getenv("GOOGLE_CLOUD_PROJECT")?.trim()?.takeIf { it.isNotEmpty() }
    log.info(
        "starting kb-api auth_mode={} ai_enabled={} limits_store={}",
        config.authMode, config.aiEnabled, if (gcpProject == null) "memory" else "firestore",
    )
    val services = Services.production(config, gcpProject = gcpProject)
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { kbApi(services) }.start(wait = true)
}
