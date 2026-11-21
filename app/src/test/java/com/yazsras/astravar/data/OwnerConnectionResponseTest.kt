package com.yazsras.astravar.data

import org.junit.Assert.*
import org.junit.Test

class OwnerConnectionResponseTest {
    @Test fun outerGatewayAuthorizationFailureDoesNotBecomeDeviceRevocation() {
        for (status in listOf(401, 403)) {
            val failure = runCatching { parseOwnerConnectionResponse(status, "text/html", "private error body".toByteArray()) }.exceptionOrNull() as OwnerSyncFailure
            assertEquals("SERVICE_AUTHORIZATION_REQUIRED", failure.code)
            assertFalse(failure.toString().contains("private error body"))
        }
    }
    @Test fun malformedOrRedirectedResponsesFailClosedWithoutProviderContents() {
        for (body in listOf("[]", "null", "broken secret-json")) {
            val failure = runCatching { parseOwnerConnectionResponse(200, "application/json", body.toByteArray()) }.exceptionOrNull() as OwnerSyncFailure
            assertEquals("MALFORMED_SERVICE_RESPONSE", failure.code)
            assertNull(failure.cause)
        }
        assertEquals("REDIRECT_DENIED", (runCatching {
            parseOwnerConnectionResponse(302, "text/html", ByteArray(0))
        }.exceptionOrNull() as OwnerSyncFailure).code)
    }
    @Test fun authenticatedProtocolErrorIsPreservedForDomainValidation() {
        val response = parseOwnerConnectionResponse(401, "Application/JSON; charset=utf-8",
            "{\"mode\":\"ASTRAVAR_CAMPAIGN_V2\",\"error\":{\"code\":\"CREDENTIAL_REVOKED_OR_EXPIRED\"}}".toByteArray())
        assertEquals(401, response.status)
        assertTrue(response.json.containsKey("mode"))
    }
}
