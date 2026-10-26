package com.manjul.kbapi

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.header
import io.ktor.util.AttributeKey
import java.util.UUID

const val REQUEST_ID_HEADER = "X-Request-Id"

internal val RequestIdKey = AttributeKey<String>("kbapi.request_id")

val ApplicationCall.requestId: String get() = attributes[RequestIdKey]

/**
 * Gives every call a server-generated id and sets `X-Request-Id` before anything can respond,
 * so success, auth failures, 404s and 500s all carry it. Client-supplied ids are ignored.
 */
val RequestId = createApplicationPlugin("RequestId") {
    onCall { call ->
        val id = UUID.randomUUID().toString()
        call.attributes.put(RequestIdKey, id)
        call.response.header(REQUEST_ID_HEADER, id)
    }
}
