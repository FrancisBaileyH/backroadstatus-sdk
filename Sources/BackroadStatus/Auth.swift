import Foundation
import UIKit
import AppAuth

/// Handles the OAuth 2.1 authorization-code + PKCE flow via AppAuth-iOS.
///
/// Tokens are persisted via ``TokenStore``. For this first pass we use
/// UserDefaults; migrate to Keychain before shipping (see TokenStore.swift).
final class Auth {

    private var authState: OIDAuthState?
    private var currentFlow: OIDExternalUserAgentSession?

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
    func accessToken(config: BackroadStatus.Config) async throws -> String {
        guard let state = authState else { throw BackroadStatusError.notAuthenticated }

        return try await withCheckedThrowingContinuation { cont in
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
}
