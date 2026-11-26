package com.backroadstatus.sdk.core

/**
 * Error surface for the SDK. Mirrors the iOS `BackroadStatusError` enum one
 * case per subclass.
 */
sealed class BackroadStatusError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** `BackroadStatus.configure(...)` was not called. */
    object NotConfigured : BackroadStatusError("BackroadStatus.configure(...) was not called.")

    /** No cached auth state and the caller asked for an authenticated operation. */
    object NotAuthenticated : BackroadStatusError("Not signed in.")

    /** Something on the OAuth round-trip failed (cancelled by user, bad redirect, …). */
    class OAuthFailed(underlying: Throwable? = null) :
        BackroadStatusError("OAuth flow failed: ${underlying?.localizedMessage ?: "unknown"}", underlying)

    /**
     * HTTP error from the server. `status` is the response code; `body` is the
     * raw response body if we could read one (or a decode-failure preview).
     */
    class ApiError(val status: Int, val body: String?) : BackroadStatusError("API error $status: ${body ?: ""}")

    /** Sentinel thrown when `BackroadStatus.simulateOffline = true`. */
    object SimulatedOffline : BackroadStatusError("Offline (simulated via BackroadStatus.simulateOffline).")
}
