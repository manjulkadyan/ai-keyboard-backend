package com.manjul.kbapi

import com.manjul.kbapi.auth.Principal
import com.manjul.kbapi.testing.TestKeys
import com.manjul.kbapi.testing.authed
import com.manjul.kbapi.testing.contractCall
import com.manjul.kbapi.testing.testApp
import com.manjul.kbapi.testing.testConfig
import com.manjul.kbapi.testing.testServices
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthChainTest {

    private fun errorCode(body: String) = Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content

    @Test
    fun `valid app check and id token reach quota`() = testApp {
        val (response, _) = contractCall(HttpMethod.Get, "/v1/quota") { authed() }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `missing app check is 401 app_check`() = testApp {
        val (response, body) = contractCall(HttpMethod.Get, "/v1/quota", requestMustBeValid = false) {
            header("Authorization", "Bearer ${TestKeys.idToken()}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("app_check", errorCode(body))
    }

    @Test
    fun `app check signed by another key is 401 app_check`() = testApp {
        val response = client.get("/v1/quota") {
            header("X-Firebase-AppCheck", TestKeys.appCheckToken(key = TestKeys.otherKey))
            header("Authorization", "Bearer ${TestKeys.idToken()}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("app_check", errorCode(response.bodyAsText()))
    }

    @Test
    fun `expired app check is 401 app_check`() = testApp {
        val response = client.get("/v1/quota") {
            header("X-Firebase-AppCheck", TestKeys.appCheckToken(expiresIn = Duration.ofSeconds(-5)))
            header("Authorization", "Bearer ${TestKeys.idToken()}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("app_check", errorCode(response.bodyAsText()))
    }

    @Test
    fun `expired id token is 401 auth`() = testApp {
        val response = client.get("/v1/quota") {
            header("X-Firebase-AppCheck", TestKeys.appCheckToken())
            header("Authorization", "Bearer ${TestKeys.idToken(expiresIn = Duration.ofSeconds(-5))}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("auth", errorCode(response.bodyAsText()))
    }

    @Test
    fun `wrong app check audience is 401 app_check`() = testApp {
        val response = client.get("/v1/quota") {
            header("X-Firebase-AppCheck", TestKeys.appCheckToken(audience = listOf("projects/999999999999")))
            header("Authorization", "Bearer ${TestKeys.idToken()}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("app_check", errorCode(response.bodyAsText()))
    }

    @Test
    fun `wrong app check issuer is 401 app_check`() = testApp {
        val response = client.get("/v1/quota") {
            header("X-Firebase-AppCheck", TestKeys.appCheckToken(issuer = "https://firebaseappcheck.googleapis.com/999"))
            header("Authorization", "Bearer ${TestKeys.idToken()}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("app_check", errorCode(response.bodyAsText()))
    }

    @Test
    fun `wrong id token audience is 401 auth`() = testApp {
        val response = client.get("/v1/quota") {
            header("X-Firebase-AppCheck", TestKeys.appCheckToken())
            header("Authorization", "Bearer ${TestKeys.idToken(audience = "other-project")}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("auth", errorCode(response.bodyAsText()))
    }

    @Test
    fun `missing bearer is 401 auth`() = testApp {
        val (response, body) = contractCall(HttpMethod.Get, "/v1/quota", requestMustBeValid = false) {
            header("X-Firebase-AppCheck", TestKeys.appCheckToken())
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("auth", errorCode(body))
    }

    @Test
    fun `non bearer authorization is 401 auth`() = testApp {
        val response = client.get("/v1/quota") {
            header("X-Firebase-AppCheck", TestKeys.appCheckToken())
            header("Authorization", "Basic ${TestKeys.idToken()}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("auth", errorCode(response.bodyAsText()))
    }

    @Test
    fun `unknown v1 path is still behind the chain`() = testApp {
        val response = client.get("/v1/does-not-exist")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `healthz is open`() = testApp {
        val (response, _) = contractCall(HttpMethod.Get, "/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `dev mode accepts X-Dev-Uid instead of tokens`() = testApp(testServices(testConfig(AuthMode.DEV))) {
        val ok = client.get("/v1/quota") { header("X-Dev-Uid", "dev-user-1") }
        assertEquals(HttpStatusCode.OK, ok.status)
        val missing = client.get("/v1/quota")
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals("auth", errorCode(missing.bodyAsText()))
    }

    @Test
    fun `firebase mode ignores X-Dev-Uid`() = testApp {
        val response = client.get("/v1/quota") { header("X-Dev-Uid", "dev-user-1") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("app_check", errorCode(response.bodyAsText()))
    }

    @Test
    fun `principal hash is sha256 prefix`() {
        val p = Principal.of("abc")
        // sha256("abc") = ba7816bf8f01cfea414140de5dae2223...
        assertEquals("ba7816bf8f01cfea", p.uidHash)
    }
}
