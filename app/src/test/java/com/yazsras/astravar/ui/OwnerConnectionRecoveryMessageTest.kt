package com.yazsras.astravar.ui

import com.yazsras.astravar.data.OwnerAuthorizationError
import org.junit.Assert.*
import org.junit.Test

class OwnerConnectionRecoveryMessageTest {
    @Test fun renewalKeepsDeviceGrantAndUnknownProviderCodesAreNeverRendered() {
        val renewal = requireNotNull(ownerConnectionRecoveryMessage("SERVICE_AUTHORIZATION_REQUIRED"))
        assertTrue(renewal.contains("device grant"))
        assertTrue(renewal.contains("newest download"))
        assertNull(ownerConnectionRecoveryMessage("SOME_PRIVATE_PROVIDER_SECRET"))
        assertTrue(requireNotNull(ownerConnectionRecoveryMessage("CREDENTIAL_STORAGE_UNAVAILABLE")).contains("do not clear app data"))
    }
    @Test fun authorizationCategoriesHaveDistinctRecoveryInstructions() {
        val messages = OwnerAuthorizationError.entries.map { it.safeMessage }
        assertEquals(messages.size, messages.toSet().size)
        assertTrue(OwnerAuthorizationError.REQUEST_EXPIRED.safeMessage.contains("ten minutes"))
        assertTrue(OwnerAuthorizationError.REQUEST_SUPERSEDED.safeMessage.contains("newest"))
        assertTrue(OwnerAuthorizationError.LOCAL_STORAGE_FAILED.safeMessage.contains("retry"))
    }
}
