package com.manjul.kbapi.billing

import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * The Play Developer API over REST, authenticated with Application Default Credentials (the kb-api service
 * account, invited in Play Console with "View financial data" and "Manage orders and subscriptions").
 * Response and error bodies are parsed for the fields kb-api needs and never logged or kept: they carry the
 * purchase token and the order id.
 */
class GooglePlayDeveloperApi(
    private val packageName: String,
    private val credentials: GoogleCredentials,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val baseUrl: String = "https://androidpublisher.googleapis.com/androidpublisher/v3",
) : PlayDeveloperApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun subscription(token: String): PlaySubscription? {
        val url = "$baseUrl/applications/${enc(packageName)}/purchases/subscriptionsv2/tokens/${enc(token)}"
        val response = send(HttpRequest.newBuilder(URI(url)).GET())
        return when (response.statusCode()) {
            200 -> parse(response.body())
            // 400 is a malformed token, 404 an unknown one, 410 one too old to read: all "no such purchase".
            400, 404, 410 -> null
            else -> throw failure(response.statusCode())
        }
    }

    override suspend fun acknowledge(productId: String, token: String) {
        val url = "$baseUrl/applications/${enc(packageName)}/purchases/subscriptions/${enc(productId)}" +
            "/tokens/${enc(token)}:acknowledge"
        val response = send(
            HttpRequest.newBuilder(URI(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")),
        )
        if (response.statusCode() !in 200..299) throw failure(response.statusCode())
    }

    private suspend fun send(builder: HttpRequest.Builder): HttpResponse<String> {
        val bearer = withContext(Dispatchers.IO) {
            credentials.refreshIfExpired()
            credentials.accessToken?.tokenValue ?: throw PlayApiException(retryable = true, status = 0)
        }
        val request = builder.timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer $bearer").build()
        return try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (_: IOException) {
            throw PlayApiException(retryable = true, status = 0)
        } catch (_: java.util.concurrent.CompletionException) {
            throw PlayApiException(retryable = true, status = 0)
        }
    }

    private fun failure(status: Int) = PlayApiException(retryable = status == 429 || status >= 500, status = status)

    private fun parse(body: String): PlaySubscription {
        val o = try {
            json.parseToJsonElement(body) as JsonObject
        } catch (_: Exception) {
            throw PlayApiException(retryable = true, status = 200)
        }
        val items = (o["lineItems"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val latest = items.maxByOrNull { it.str("expiryTime")?.let(::millis) ?: 0L }
        return PlaySubscription(
            state = PlayState.fromApi(o.str("subscriptionState")),
            productId = latest?.str("productId").orEmpty(),
            expiryTimeMs = latest?.str("expiryTime")?.let(::millis) ?: 0L,
            acknowledged = o.str("acknowledgementState") == "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
            linkedPurchaseToken = o.str("linkedPurchaseToken"),
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun millis(rfc3339: String): Long = runCatching { Instant.parse(rfc3339).toEpochMilli() }.getOrDefault(0L)

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/androidpublisher"

        fun fromApplicationDefault(packageName: String) =
            GooglePlayDeveloperApi(packageName, GoogleCredentials.getApplicationDefault().createScoped(listOf(SCOPE)))
    }
}
