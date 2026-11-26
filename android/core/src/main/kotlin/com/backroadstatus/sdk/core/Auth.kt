package com.backroadstatus.sdk.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import java.util.UUID

/**
 * Handles the OAuth 2.1 authorization-code + PKCE flow via AppAuth-Android.
 *
 * Tokens are persisted via [TokenStore].
 *
 * AppAuth's recommended persistence pattern is wired up: every mutation
 * AppAuth makes to [AuthState] is routed through [persist], so refresh-token
 * rotation (`reuseRefreshTokens(false)` on the server) writes to disk
 * synchronously. Without this, rotated refresh tokens can be lost on a
 * background kill, and the next launch sends the retired token on refresh —
 * the server treats that as reuse and revokes the entire authorization.
 */
internal class Auth {

    @Volatile
    private var authState: AuthState? = null

    /** Serializes concurrent token-refresh callers onto one in-flight request. */
    private val refreshMutex = Mutex()

    val hasValidToken: Boolean
        get() = authState?.isAuthorized == true

    fun restore(context: Context) {
        authState = TokenStore.load(context)
    }

    fun signOut(context: Context) {
        authState = null
        TokenStore.clear(context)
    }

    suspend fun signIn(
        activity: ComponentActivity,
        config: BackroadStatus.Config,
        scopes: List<String>,
    ) {
        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse(config.issuer).buildUpon().appendPath("oauth2").appendPath("authorize").build(),
            Uri.parse(config.issuer).buildUpon().appendPath("oauth2").appendPath("token").build(),
        )

        val request = AuthorizationRequest.Builder(
            serviceConfig,
            config.clientId,
            ResponseTypeValues.CODE,
            Uri.parse(config.redirectUri),
        )
            .setScope(scopes.joinToString(" "))
            .build()

        val service = AuthorizationService(activity.applicationContext)
        try {
            val response = launchAuthorizationRequest(activity, service, request)
            val tokenResponse = withContext(Dispatchers.IO) {
                performTokenRequest(service, response)
            }
            val state = AuthState(serviceConfig).apply {
                update(response, null)
                update(tokenResponse, null)
            }
            authState = state
            TokenStore.save(activity.applicationContext, state)
        } finally {
            service.dispose()
        }
    }

    /**
     * Runs a single authorization intent through [ComponentActivity]'s result
     * registry and resumes when the system returns. Registering via
     * [androidx.activity.result.ActivityResultRegistry] works regardless of
     * lifecycle state, so the SDK can be called from anywhere in the host
     * app without requiring the caller to pre-wire launchers at `onCreate`.
     */
    private suspend fun launchAuthorizationRequest(
        activity: ComponentActivity,
        service: AuthorizationService,
        request: AuthorizationRequest,
    ): AuthorizationResponse {
        val key = "backroadstatus-oauth-${UUID.randomUUID()}"
        val deferred = CompletableDeferred<AuthorizationResponse>()
        var launcher: ActivityResultLauncher<Intent>? = null
        launcher = activity.activityResultRegistry.register(
            key,
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            try {
                val data = result.data
                if (data == null) {
                    deferred.completeExceptionally(BackroadStatusError.OAuthFailed(null))
                } else {
                    val response = AuthorizationResponse.fromIntent(data)
                    val exception = AuthorizationException.fromIntent(data)
                    when {
                        response != null -> deferred.complete(response)
                        else -> deferred.completeExceptionally(BackroadStatusError.OAuthFailed(exception))
                    }
                }
            } finally {
                launcher?.unregister()
            }
        }

        val intent = service.getAuthorizationRequestIntent(request)
        launcher.launch(intent)
        return try {
            deferred.await()
        } catch (t: Throwable) {
            launcher.unregister()
            throw t
        }
    }

    private suspend fun performTokenRequest(
        service: AuthorizationService,
        response: AuthorizationResponse,
    ): net.openid.appauth.TokenResponse {
        val deferred = CompletableDeferred<net.openid.appauth.TokenResponse>()
        service.performTokenRequest(response.createTokenExchangeRequest()) { token, ex ->
            if (token != null) deferred.complete(token)
            else deferred.completeExceptionally(BackroadStatusError.OAuthFailed(ex))
        }
        return deferred.await()
    }

    /**
     * Returns a fresh access token, refreshing if necessary.
     *
     * Pass `forceRefresh = true` from the 401-retry path in [ApiClient]: it
     * calls [AuthState.needsTokenRefresh] = true so AppAuth refreshes
     * against the token endpoint even if the stored access token is still
     * "fresh" by its internal clock. Covers both server-side mid-session
     * revoke and the (observed) AppAuth edge case where
     * `performActionWithFreshTokens` hands back a visibly-expired token.
     *
     * Concurrent callers share a single in-flight refresh — see
     * [refreshMutex] — because two parallel refreshes with the same refresh
     * token would look like a reuse attempt under `reuseRefreshTokens(false)`.
     */
    suspend fun accessToken(
        context: Context,
        forceRefresh: Boolean = false,
    ): String = refreshMutex.withLock {
        val state = authState ?: throw BackroadStatusError.NotAuthenticated
        if (forceRefresh) state.needsTokenRefresh = true

        val service = AuthorizationService(context.applicationContext)
        try {
            val deferred = CompletableDeferred<String>()
            state.performActionWithFreshTokens(service) { accessToken, _, ex ->
                try {
                    // persist on every mutation — AppAuth's performAction
                    // internally updates the stored AuthState when it
                    // rotates tokens; writing through keeps a crash from
                    // losing a rotated refresh_token on disk.
                    TokenStore.save(context.applicationContext, state)
                } catch (_: Throwable) {}

                if (accessToken != null) {
                    deferred.complete(accessToken)
                } else {
                    if (ex != null && isAuthorizationError(ex)) {
                        // Terminal — clear local state so the next
                        // `isAuthenticated` reads false and the host app
                        // re-prompts cleanly instead of looping on a dead
                        // refresh token.
                        Log.w(
                            "BackroadStatus",
                            "Terminal authorization error: ${ex.message}. Clearing stored tokens.",
                        )
                        authState = null
                        TokenStore.clear(context.applicationContext)
                    }
                    deferred.completeExceptionally(BackroadStatusError.OAuthFailed(ex))
                }
            }
            deferred.await()
        } finally {
            service.dispose()
        }
    }

    private fun isAuthorizationError(ex: AuthorizationException): Boolean {
        return ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR &&
            (ex.code == AuthorizationException.TokenRequestErrors.INVALID_GRANT.code ||
                ex.code == AuthorizationException.TokenRequestErrors.INVALID_CLIENT.code ||
                ex.code == AuthorizationException.TokenRequestErrors.UNAUTHORIZED_CLIENT.code)
    }
}
