package com.yazsras.astravar.data

/** Fixed diagnostics only. Provider messages, documents and credentials must never enter errors. */
enum class OwnerAuthorizationError(val safeMessage: String) {
    MALFORMED_BUNDLE("This is not a valid encrypted authorization document. Select the JSON downloaded from the authorization page."),
    WRONG_SCOPE("This authorization belongs to another service or campaign. Open a new request from this installation for the selected campaign."),
    REQUEST_EXPIRED("This authorization expired. Start a new request and import its download within ten minutes."),
    REQUEST_SUPERSEDED("This file belongs to an older request, another installation, or a request already completed. Import the newest download or start a new request."),
    UNSUPPORTED_CRYPTO_PARAMETERS("This document uses unsupported encryption parameters. Download a fresh authorization from the page opened by Astravar."),
    STRONG_CRYPTO_UNAVAILABLE("Android could not use the required installation encryption key. Unlock the phone and retry a new request. Existing credentials are retained; do not clear app data."),
    CRYPTO_VERIFICATION_FAILED("The encrypted file could not be authenticated for this installation. Download a fresh authorization from a new request and select that file."),
    LOCAL_STORAGE_FAILED("Authorization could not be saved securely. Existing credentials and the pending request are retained where available. Unlock the phone and retry the newest file before it expires.")
}

class OwnerAuthorizationException(val code: OwnerAuthorizationError) : IllegalArgumentException(code.safeMessage)
