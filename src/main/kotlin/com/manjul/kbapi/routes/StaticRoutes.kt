package com.manjul.kbapi.routes

import com.manjul.kbapi.Services
import io.ktor.http.ContentType
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /delete-account`: the public data deletion page Play requires (decision 21). It is static, has no
 * form and no script, and is served with a CSP that blocks everything except its own inline style.
 */
fun Route.staticRoutes(services: Services) {
    val template = Services::class.java.getResource("/static/delete-account.html")
        ?.readText() ?: error("static/delete-account.html is missing from the classpath")
    // The address is validated in Config.fromEnv, so it carries no markup.
    val page = template.replace("{{CONTACT_EMAIL}}", services.config.deletionContactEmail)
    get("/delete-account") {
        call.response.header(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
        )
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("Cache-Control", "public, max-age=3600")
        call.respondText(page, ContentType.Text.Html.withParameter("charset", "utf-8"))
    }
}
