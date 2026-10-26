package com.manjul.kbapi.testing

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
import com.manjul.kbapi.AuthMode
import com.manjul.kbapi.Config
import com.manjul.kbapi.Services
import com.manjul.kbapi.kbApi
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.routing.Route
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.fail

fun testConfig(authMode: AuthMode = AuthMode.FIREBASE) = Config(
    authMode = authMode,
    firebaseProjectId = TestKeys.PROJECT_ID,
    firebaseProjectNumber = TestKeys.PROJECT_NUMBER,
    anthropicApiKey = null,
)

fun testServices(config: Config = testConfig()) = Services(
    config = config,
    appCheck = TestKeys.appCheckVerifier(),
    idTokens = TestKeys.idTokenVerifier(),
    clock = TestKeys.clock,
)

/** Headers for a request that passes the full auth chain. */
fun HttpRequestBuilder.authed(uid: String = "test-uid-1") {
    headers.append("X-Firebase-AppCheck", TestKeys.appCheckToken())
    headers.append("Authorization", "Bearer ${TestKeys.idToken(uid)}")
}

/**
 * Runs the real kb-api module in Ktor's test host. [extraRoutes] adds test-only routes
 * (outside the OpenAPI contract) for exercising error paths.
 */
fun testApp(
    services: Services = testServices(),
    extraRoutes: Route.() -> Unit = {},
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application { kbApi(services, extraRoutes) }
    block()
}

/** Validates request/response pairs made through the test app against `openapi.yaml`. */
object Contract {
    val validator: OpenApiInteractionValidator by lazy {
        val spec = Contract::class.java.getResource("/openapi.yaml") ?: fail("openapi.yaml is not on the classpath")
        OpenApiInteractionValidator.createForSpecificationUrl(spec.toString()).build()
    }
}

/**
 * Sends a request and checks the exchange against the contract.
 * [requestMustBeValid] is false for negative tests that deliberately omit credentials;
 * the response is still validated.
 */
suspend fun ApplicationTestBuilder.contractCall(
    method: HttpMethod,
    path: String,
    body: String? = null,
    requestMustBeValid: Boolean = true,
    configure: HttpRequestBuilder.() -> Unit = {},
): Pair<HttpResponse, String> {
    val response = client.request(path) {
        this.method = method
        configure()
        if (body != null) {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(body)
        }
    }
    val text = response.bodyAsText()

    val reqBuilder = SimpleRequest.Builder(Request.Method.valueOf(method.value), path)
    response.call.request.headers.forEach { name, values -> values.forEach { reqBuilder.withHeader(name, it) } }
    if (body != null) reqBuilder.withContentType("application/json").withBody(body)
    val request = reqBuilder.build()

    val respBuilder = SimpleResponse.Builder(response.status.value)
    response.headers.forEach { name, values -> values.forEach { respBuilder.withHeader(name, it) } }
    if (text.isNotEmpty()) respBuilder.withBody(text)
    val apiResponse = respBuilder.build()

    if (requestMustBeValid) {
        val report = Contract.validator.validate(request, apiResponse)
        if (report.hasErrors()) fail("Contract violation for ${method.value} $path:\n$report")
    } else {
        val report = Contract.validator.validateResponse(path, Request.Method.valueOf(method.value), apiResponse)
        if (report.hasErrors()) fail("Response contract violation for ${method.value} $path:\n$report")
    }
    return response to text
}
