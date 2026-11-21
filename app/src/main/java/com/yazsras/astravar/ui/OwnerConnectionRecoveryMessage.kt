package com.yazsras.astravar.ui

/** Fixed recovery text; never render a provider exception, response body, or unknown error code. */
internal fun ownerConnectionRecoveryMessage(code: String): String? = when (code) {
    "SERVICE_AUTHORIZATION_REQUIRED", "PRIVATE_GATEWAY_AUTH_REQUIRED" -> "Private service authorization needs renewal. Your device grant and queued work are preserved. Select Renew private service authorization and import its newest download."
    "CREDENTIAL_STORAGE_UNAVAILABLE" -> "Android could not read the encrypted credential. Unlock the phone and retry. Existing credentials and queued work are retained; do not clear app data."
    "CREDENTIAL_PERSISTENCE_FAILED" -> "The renewed credential could not be saved securely. Unlock the phone, check available storage, and retry the newest authorization file before it expires."
    "NETWORK_TIMEOUT", "NETWORK_UNAVAILABLE", "REMOTE_UNAVAILABLE" -> "The service could not be reached. Check connectivity and retry Sync now. Saved records and pending work remain on this device."
    "TLS_FAILURE" -> "The secure service connection could not be verified. Check the phone's date, time and network, then retry. Saved records and queued work are preserved."
    "MALFORMED_SERVICE_RESPONSE", "NON_JSON_RESPONSE", "EMPTY_RESPONSE", "RESPONSE_TOO_LARGE", "REDIRECT_DENIED" -> "The service response could not be verified. Retry Sync now; if it continues, retain this status for support. Saved records and pending work are preserved."
    "INVALID_AUTHORIZATION_BUNDLE" -> "Select the encrypted JSON file downloaded from the newest authorization request opened by Astravar."
    else -> null
}
