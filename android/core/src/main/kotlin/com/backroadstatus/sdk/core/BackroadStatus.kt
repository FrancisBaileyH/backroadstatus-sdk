package com.backroadstatus.sdk.core

import android.content.Context
import androidx.activity.ComponentActivity

/**
 * Entry point for the BackroadStatus SDK.
 *
 * Call [configure] once at app startup (typically from `Application.onCreate`),
 * then use [signIn] to kick off OAuth and [me] / other methods to read data.
 */
class BackroadStatus private constructor() {

    data class Config(
        val clientId: String,
        /** e.g. `com.yourcompany.yourapp://oauth/callback` */
        val redirectUri: String,
        /** e.g. `https://backroadstatus.com` */
        val issuer: String,
    )

    internal var config: Config? = null
        private set

    internal val auth = Auth()
    internal val api = ApiClient()

    val isAuthenticated: Boolean get() = auth.hasValidToken

    /**
     * Configures the SDK and restores any persisted auth state from disk.
     * Pass an application context — a longer-lived context (e.g. an
     * Activity) is fine too but the context is retained internally for
     * encrypted-prefs and queue initialization, so prefer the app context.
     */
    fun configure(context: Context, config: Config) {
        this.config = config
        auth.restore(context.applicationContext)
    }

    suspend fun signIn(
        activity: ComponentActivity,
        scopes: List<String> = defaultScopes,
    ) {
        val config = requireConfig()
        auth.signIn(activity, config, scopes)
    }

    /**
     * Convenience gate for host-app "sign in to continue" buttons. Returns
     * immediately if the user is already signed in; otherwise presents the
     * OAuth flow via [activity] and awaits its result. Rethrows whatever
     * [signIn] would — including the user-cancelled-the-OAuth-sheet case —
     * so callers can distinguish "user bailed, don't proceed" from a happy
     * path.
     *
     * Use this at action-trigger points (e.g. a "Create Report" tap) rather
     * than at view-open time: it lets unauthenticated users browse the
     * surrounding UI and only prompts them when they try to do something
     * that actually needs an identity.
     */
    suspend fun ensureAuthenticated(
        activity: ComponentActivity,
        scopes: List<String> = defaultScopes,
    ) {
        if (isAuthenticated) return
        signIn(activity, scopes)
    }

    fun signOut(context: Context) {
        auth.signOut(context.applicationContext)
    }

    suspend fun me(context: Context): MeResponse {
        val config = requireConfig()
        return api.me(tokenProvider(context), config.issuer)
    }

    suspend fun createBulletin(context: Context, input: CreateBulletinInput): Bulletin {
        val config = requireConfig()
        return api.createBulletin(tokenProvider(context), config.issuer, input)
    }

    /**
     * Fetches full road geometry for a path. The composer uses this as the
     * primary source and falls back to tile-based stitching if this throws.
     */
    suspend fun road(context: Context, id: String): RoadDetail {
        val config = requireConfig()
        return api.road(tokenProvider(context), config.issuer, id)
    }

    /**
     * Captures `auth` and `config` in a closure the API layer invokes to
     * fetch a bearer token — once at the start of each request, and again
     * (with `forceRefresh = true`) only when the first attempt comes back
     * 401. See [ApiClient] for the retry contract.
     */
    internal fun tokenProvider(context: Context): TokenProvider {
        val appContext = context.applicationContext
        return { forceRefresh -> auth.accessToken(appContext, forceRefresh) }
    }

    /**
     * Canonical geometry for [id] out of the offline cache, if the host app
     * pre-downloaded the containing region via [downloadGeometry].
     * Non-blocking — safe to call before falling back to [road].
     */
    fun cachedRoad(id: String): RoadDetail? = GeometryStore.sharedOrNull()?.road(id)

    /**
     * Fetches canonical road geometry for every road inside [bounds] by
     * walking the slippy-map tiles at [zoom] and hitting
     * `/api/paths/bulk/{z}/{x}/{y}.json` for each. Roads are stored in the
     * on-disk cache, keyed by road id, and are then served by [cachedRoad] —
     * call this alongside your MapLibre offline pack download so the
     * composer has full-resolution geometry when the user taps a road
     * offline.
     *
     * The bulk endpoint returns unsimplified canonical geometry, so
     * condition-rating points produced from cached coords match the
     * server's record exactly — unlike anything derived from tile-rendered
     * (DP-simplified) MVT.
     *
     * [onProgress] fires off the main thread; hop to Main to update UI.
     */
    suspend fun downloadGeometry(
        context: Context,
        bounds: GeographicBounds,
        zoom: Int = GeometryDownloader.defaultZoom,
        onProgress: (GeometryDownloader.Progress) -> Unit = {},
    ) {
        requireConfig()
        GeometryDownloader().download(context, bounds, zoom, onProgress = onProgress)
    }

    /**
     * Uploads raw image bytes as a draft photo. The returned `reference` is
     * what you include in `CreateBulletinInput.photos[].reference` on submit.
     */
    suspend fun uploadDraftPhoto(
        context: Context,
        data: ByteArray,
        fileName: String = "photo.jpg",
    ): DraftPhoto {
        val config = requireConfig()
        return api.uploadDraftPhoto(tokenProvider(context), config.issuer, data, fileName)
    }

    internal fun requireConfig(): Config = config ?: throw BackroadStatusError.NotConfigured

    companion object {
        /** Default OAuth scopes requested at sign-in. */
        val defaultScopes: List<String> = listOf("bulletins:read", "bulletins:write", "profile:read")

        /**
         * Testing-only knob: when true, every API call throws immediately
         * as if the network were unreachable. Useful for validating offline
         * fallback paths without actually airplane-moding the device.
         */
        @JvmStatic
        var simulateOffline: Boolean = false

        @JvmStatic
        val shared: BackroadStatus = BackroadStatus()

        /**
         * One-shot bootstrap matching the iOS `BackroadStatus.configure(...)`
         * static entry point.
         */
        @JvmStatic
        fun configure(context: Context, clientId: String, redirectUri: String, issuer: String) {
            shared.configure(context, Config(clientId, redirectUri, issuer))
        }
    }
}
