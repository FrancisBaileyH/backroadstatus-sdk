import Foundation
import AppAuth

/// Persists an OIDAuthState across launches.
///
/// NOTE: this uses UserDefaults for brevity during SDK validation.
/// Before shipping, migrate to Keychain (kSecAttrAccessibleAfterFirstUnlock).
enum TokenStore {
    private static let key = "com.backroadstatus.sdk.authState"

    static func save(_ state: OIDAuthState) {
        guard let data = try? NSKeyedArchiver.archivedData(
            withRootObject: state, requiringSecureCoding: true
        ) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }

    static func load() -> OIDAuthState? {
        guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
        return try? NSKeyedUnarchiver.unarchivedObject(ofClass: OIDAuthState.self, from: data)
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: key)
    }
}
