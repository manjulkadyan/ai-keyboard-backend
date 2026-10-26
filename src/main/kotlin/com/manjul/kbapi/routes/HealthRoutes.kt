package com.manjul.kbapi.routes

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Unauthenticated liveness probe for Cloud Run. */
fun Route.healthRoutes() {
    get("/healthz") {
        call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
    }
}
