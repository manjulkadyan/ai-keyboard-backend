package com.manjul.kbapi

import com.manjul.kbapi.testing.Contract
import com.manjul.kbapi.testing.TestKeys
import com.manjul.kbapi.testing.authed
import com.manjul.kbapi.testing.contractCall
import com.manjul.kbapi.testing.testApp
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenApiContractTest {

    @Test
    fun `spec declares every sprint 3 endpoint`() {
        val spec = OpenApiContractTest::class.java.getResource("/openapi.yaml")!!.readText()
        val paths = listOf("/v1/rewrite:", "/v1/polish:", "/v1/ai/report:", "/v1/quota:", "/healthz:")
            .filter { p -> !spec.contains(p) }
        assertTrue(paths.isEmpty(), "missing paths in openapi.yaml: $paths")
    }

    @Test
    fun `healthz matches the contract`() = testApp {
        val (response, _) = contractCall(HttpMethod.Get, "/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `quota matches the contract`() = testApp {
        val (response, body) = contractCall(HttpMethod.Get, "/v1/quota") { authed() }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.contains("\"ai_left_today\""))
    }

    @Test
    fun `401 responses match the contract`() = testApp {
        val (noAppCheck, _) = contractCall(HttpMethod.Get, "/v1/quota", requestMustBeValid = false)
        assertEquals(HttpStatusCode.Unauthorized, noAppCheck.status)
        val (noBearer, _) = contractCall(HttpMethod.Get, "/v1/quota", requestMustBeValid = false) {
            header("X-Firebase-AppCheck", TestKeys.appCheckToken())
        }
        assertEquals(HttpStatusCode.Unauthorized, noBearer.status)
    }

    @Test
    fun `request bodies in the spec reject bad shapes`() {
        fun validate(path: String, body: String) = Contract.validator.validateRequest(
            com.atlassian.oai.validator.model.SimpleRequest.Builder.post(path)
                .withHeader("X-Firebase-AppCheck", "t")
                .withHeader("Authorization", "Bearer t")
                .withContentType("application/json")
                .withBody(body)
                .build(),
        )
        assertTrue(
            !validate("/v1/rewrite", """{"mode":"fix","locale":"en","text":"hi","client_req_id":"r1"}""").hasErrors(),
        )
        assertTrue(
            !validate(
                "/v1/rewrite",
                """{"mode":"translate","target_lang":"es","modes":["formal","friendly"],"locale":"en","text":"hi","client_req_id":"r1"}""",
            ).hasErrors(),
        )
        assertTrue(validate("/v1/rewrite", """{"mode":"shout","locale":"en","text":"hi","client_req_id":"r1"}""").hasErrors())
        assertTrue(validate("/v1/rewrite", """{"mode":"fix","locale":"en","client_req_id":"r1"}""").hasErrors())
        assertTrue(
            !validate(
                "/v1/polish",
                """{"transcript":"hi","target_lang":"es","script":"ROMAN","client_req_id":"r1"}""",
            ).hasErrors(),
        )
        assertTrue(validate("/v1/polish", """{"transcript":"hi","target_lang":"es","script":"KLINGON","client_req_id":"r1"}""").hasErrors())
        assertTrue(
            validate(
                "/v1/ai/report",
                """{"client_req_id":"r1","prompt_id":"rewrite@1","reason":"harmful","include_text":false}""",
            ).hasErrors(),
        )
        assertTrue(
            !validate(
                "/v1/ai/report",
                """{"client_req_id":"r1","prompt_id":"rewrite@1","reason":"unsafe","include_text":false}""",
            ).hasErrors(),
        )
    }
}
