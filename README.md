# BackroadStatus iOS SDK (v0 scaffold)

Minimum viable SDK for validating the OAuth + API dev loop from a real iOS app. No overlay, no WebView, no offline queue yet.

## What's in this cut

- `BackroadStatus.configure(clientId:redirectUri:issuer:)`
- `BackroadStatus.shared.signIn(presenting:)` — PKCE via AppAuth-iOS, system browser (`ASWebAuthenticationSession`)
- `BackroadStatus.shared.me()` — calls `/api/integrations/v1/me`
- Token persistence via UserDefaults (TODO: Keychain before shipping)

## Setting up the demo app in Xcode

1. **Create a new project** — `File → New → Project → iOS App`. SwiftUI / Swift.
2. **Save it anywhere** — suggestion: `sdk/ios/Example/BackroadStatusDemo/` relative to this repo.
3. **Add this package as a local dependency** — `File → Add Package Dependencies → Add Local… → select the `sdk/ios/` folder`.
4. **Register your OAuth redirect URI as a URL scheme**:
   - Target → Info → "URL Types" → `+`
   - Identifier: `com.backroadstatus.demo.oauth`
   - URL Schemes: `com.backroadstatus.demo` (this is the scheme portion of the redirect URI you register on the server)
5. **Allow HTTP to localhost** (dev-only). In the target's Info.plist, add:
   ```xml
   <key>NSAppTransportSecurity</key>
   <dict>
     <key>NSAllowsLocalNetworking</key>
     <true/>
   </dict>
   ```
6. **Register an OAuth app on the local backend**:
   - Start the backend (`./gradlew bootRun`)
   - Visit `/profile/{yourId}/settings`, request developer access
   - Approve yourself via `/admin/users` (set `developerEnabled`)
   - Go to `/developer`, register an application with redirect URI `com.backroadstatus.demo://oauth/callback` and scopes `bulletins:read`, `bulletins:write`, `profile:read`
   - Copy the client ID

## Demo app code

```swift
import SwiftUI
import BackroadStatus

@main
struct BackroadStatusDemoApp: App {
    init() {
        BackroadStatus.configure(
            clientId: "<PASTE_CLIENT_ID_HERE>",
            redirectUri: URL(string: "com.backroadstatus.demo://oauth/callback")!,
            issuer: URL(string: "http://localhost:8080")!
        )
    }
    var body: some Scene { WindowGroup { ContentView() } }
}

struct ContentView: View {
    @State private var status = "Not signed in"

    var body: some View {
        VStack(spacing: 16) {
            Text(status).multilineTextAlignment(.center).padding()

            Button("Sign in") {
                Task {
                    do {
                        try await BackroadStatus.shared.signIn(presenting: rootViewController())
                        let me = try await BackroadStatus.shared.me()
                        status = "Signed in as \(me.email)"
                    } catch {
                        status = "Error: \(error.localizedDescription)"
                    }
                }
            }.buttonStyle(.borderedProminent)

            Button("Sign out") {
                BackroadStatus.shared.signOut()
                status = "Not signed in"
            }
        }.padding()
    }

    private func rootViewController() -> UIViewController {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }?
            .rootViewController ?? UIViewController()
    }
}
```

## What "works" means for this validation

- Tap "Sign in" → system browser opens pointing at `localhost:8080/oauth2/authorize?…`
- You log in via the normal web login page
- Consent screen appears styled to match the site
- Browser closes, control returns to the app
- Status label updates to "Signed in as `<your email>`"
- Backend's `oauth2_authorizations` collection has a new row

If all of that works, the full OAuth + API loop is validated end-to-end and we can start layering the overlay and WebView on top.

## Known gaps intentionally left out

- Token in Keychain (currently UserDefaults; 🚨 tokens recoverable from device backup)
- Native map overlay
- WebView submission form + bridge
- Offline submission queue
- Bulletin read/write methods (just `/me` for this validation)
- `ASPresentationAnchor` wiring for `ASWebAuthenticationSession` — AppAuth handles presentation with a `UIViewController`, but you may see a deprecation warning on iOS 13+
