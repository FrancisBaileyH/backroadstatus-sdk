import Foundation
import UIKit
import AppAuth

/// Handles the OAuth 2.1 authorization-code + PKCE flow via AppAuth-iOS.
///
/// Tokens are persisted via ``TokenStore`` (Keychain).
///
/// AppAuth's recommended persistence pattern is wired up here: this class
/// is both the ``OIDAuthStateChangeDelegate`` and ``OIDAuthStateErrorDelegate``
/// for the current `OIDAuthState`, which means **every** internal state
/// mutation — including the refresh-token rotation PathBulletin's server
/// uses (`reuseRefreshTokens(false)`) — gets written to disk immediately.
/// Without this, rotated refresh tokens can be lost on crashes or background
/// kills, and the next launch sends the retired token on refresh. The server
/// treats that as reuse and revokes the entire authorization, forcing the
/// user to sign in again.
final class Auth: NSObject, OIDAuthStateChangeDelegate, OIDAuthStateErrorDelegate {

    private var authState: OIDAuthState? {
        didSet {
            authState?.stateChangeDelegate = self
            authState?.errorDelegate = self
        }
    }
    private var currentFlow: OIDExternalUserAgentSession?

    /// Coalesces concurrent `accessToken(...)` callers onto a single
    /// `performAction` so only one refresh-token POST can be in flight at a
    /// time. Without this, two requests that race an expired access token
    /// both trigger AppAuth refreshes with the same refresh_token; with
    /// `reuseRefreshTokens(false)` on the server the later arrival looks
    /// like reuse and the whole grant is revoked.
    private let refreshLock = NSLock()
    private var inflightToken: Task<String, Error>?

    /// AppAuth uses a strong reference on the app delegate for in-flight flows;
    /// we hold ours locally. When iOS returns to the app via the redirect URL,
    /// `resume(with:)` should be called from SceneDelegate / AppDelegate.
    func resume(with url: URL) -> Bool {
        guard let flow = currentFlow else { return false }
        if flow.resumeExternalUserAgentFlow(with: url) {
            currentFlow = nil
            return true
        }
        return false
    }

    var hasValidToken: Bool {
        authState?.isAuthorized ?? false
    }

    func restore() {
        authState = TokenStore.load()
    }

    func signOut() {
        authState = nil
        TokenStore.clear()
    }

    func signIn(
        presenting viewController: UIViewController,
        config: BackroadStatus.Config,
        scopes: [String]
    ) async throws {
        let serviceConfig = OIDServiceConfiguration(
            authorizationEndpoint: config.issuer.appendingPathComponent("oauth2/authorize"),
            tokenEndpoint: config.issuer.appendingPathComponent("oauth2/token")
        )

        let request = OIDAuthorizationRequest(
            configuration: serviceConfig,
            clientId: config.clientId,
            clientSecret: nil,
            scopes: scopes,
            redirectURL: config.redirectUri,
            responseType: OIDResponseTypeCode,
            additionalParameters: nil
        )

        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.currentFlow = OIDAuthState.authState(
                    byPresenting: request,
                    presenting: viewController
                ) { state, error in
                    self.currentFlow = nil
                    if let state {
                        // Setting authState installs the delegates via didSet,
                        // which in turn routes the initial persist through
                        // `didChange(_:)` below.
                        self.authState = state
                        TokenStore.save(state)
                        cont.resume()
                    } else {
                        cont.resume(throwing: BackroadStatusError.oauthFailed(underlying: error))
                    }
                }
            }
        }
    }

    /// Returns a fresh access token, refreshing if necessary.
    ///
    /// Pass `forceRefresh: true` from the 401-retry path in `APIClient`: it
    /// calls `setNeedsTokenRefresh()` so AppAuth refreshes against the token
    /// endpoint even if its own `isTokenFresh` check thinks the stored access
    /// token is still good. Covers both the server-side mid-session revoke
    /// case and the (observed) AppAuth edge case where `performAction` hands
    /// back a visibly-expired token without refreshing.
    ///
    /// Concurrent callers share a single in-flight `performAction`; see
    /// `refreshLock` / `inflightToken` above for why.
    func accessToken(config: BackroadStatus.Config, forceRefresh: Bool = false) async throws -> String {
        guard let state = authState else { throw BackroadStatusError.notAuthenticated }

        refreshLock.lock()
        if let existing = inflightToken {
            refreshLock.unlock()
            return try await existing.value
        }
        let task = Task<String, Error> { [weak self, state] in
            defer { self?.clearInflightToken() }
            if forceRefresh { state.setNeedsTokenRefresh() }
            return try await Self.performTokenAction(state: state)
        }
        inflightToken = task
        refreshLock.unlock()
        return try await task.value
    }

    private func clearInflightToken() {
        refreshLock.lock()
        inflightToken = nil
        refreshLock.unlock()
    }

    private static func performTokenAction(state: OIDAuthState) async throws -> String {
        try await withCheckedThrowingContinuation { cont in
            state.performAction { accessToken, _, error in
                if let token = accessToken {
                    TokenStore.save(state)
                    cont.resume(returning: token)
                } else {
                    cont.resume(throwing: BackroadStatusError.oauthFailed(underlying: error))
                }
            }
        }
    }

    // MARK: - OIDAuthStateChangeDelegate

    /// Fires on every mutation AppAuth makes to the `OIDAuthState` —
    /// including refresh-token rotation. Writing through on each change is
    /// the only way to guarantee the rotated refresh token survives a
    /// background kill before the next `accessToken()` save would have run.
    func didChange(_ state: OIDAuthState) {
        TokenStore.save(state)
    }

    // MARK: - OIDAuthStateErrorDelegate

    /// Called when AppAuth decides a token error is terminal (typically
    /// `invalid_grant` from the refresh endpoint — the server-side symptom
    /// of a retired or revoked refresh token). We clear local state so the
    /// next `isAuthenticated` check reports false and the host app re-
    /// prompts sign-in cleanly instead of looping on a dead refresh token.
    func authState(_ state: OIDAuthState, didEncounterAuthorizationError error: Error) {
        print("[BackroadStatus Auth] Terminal authorization error: \(error.localizedDescription). Clearing stored tokens.")
        authState = nil
        TokenStore.clear()
    }
}
