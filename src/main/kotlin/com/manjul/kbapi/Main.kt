package com.manjul.kbapi

import com.manjul.kbapi.auth.AppCheckVerifier
import com.manjul.kbapi.auth.AuthChain
import com.manjul.kbapi.auth.FirebaseIdTokenVerifier
import com.manjul.kbapi.auth.IdTokenVerifier
import com.manjul.kbapi.auth.JwksAppCheckVerifier
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

/** Everything the module needs; tests swap verifiers and the clock. */
data class Services(
    val config: Config,
    val appCheck: AppCheckVerifier,
    val idTokens: IdTokenVerifier,
    val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        fun production(config: Config, clock: Clock = Clock.systemUTC()): Services = when (config.authMode) {
            AuthMode.FIREBASE -> Services(
                config = config,
                appCheck = JwksAppCheckVerifier(JwksAppCheckVerifier.DEFAULT_JWKS_URL, config.firebaseProjectNumber, clock),
                idTokens = FirebaseIdTokenVerifier.create(config.firebaseProjectId),
                clock = clock,
            )
            // Dev mode never consults the verifiers; reject everything to be safe.
            AuthMode.DEV -> Services(config, AppCheckVerifier { false }, IdTokenVerifier { null }, clock)
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
    log.info("starting kb-api auth_mode={} ai_enabled={}", config.authMode, config.aiEnabled)
    val services = Services.production(config)
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { kbApi(services) }.start(wait = true)
}
