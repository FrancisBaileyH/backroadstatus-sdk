import Foundation
import Security
import AppAuth

/// Persists an OIDAuthState across launches.
///
/// Stored as a `kSecClassGenericPassword` item with
/// `kSecAttrAccessibleAfterFirstUnlock` so AppAuth can refresh tokens from
/// background tasks after a reboot (before the user has entered their
/// passcode the first time). The item is not synced to iCloud.
enum TokenStore {
    private static let service = "com.backroadstatus.sdk"
    private static let account = "authState"

    static func save(_ state: OIDAuthState) {
        let data: Data
        do {
            data = try NSKeyedArchiver.archivedData(
                withRootObject: state, requiringSecureCoding: true
            )
        } catch {
            // Silently swallowing this is what caused rotated refresh tokens
            // to be lost on-disk — the in-memory state would move forward,
            // the next launch would load the previous (retired) token, and
            // the server would revoke the authorization as a reuse attempt.
            // Log loudly so any future regression is visible.
            print("[BackroadStatus TokenStore] ERROR: failed to archive OIDAuthState — token rotation will not persist: \(error)")
            return
        }

        // Delete-then-add is simpler than SecItemUpdate-or-insert and avoids
        // the attribute-mismatch pitfalls of SecItemUpdate.
        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(deleteQuery as CFDictionary)

        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
            kSecValueData as String: data,
        ]
        let status = SecItemAdd(addQuery as CFDictionary, nil)
        if status != errSecSuccess {
            print("[BackroadStatus TokenStore] ERROR: failed to write OIDAuthState to Keychain (OSStatus \(status)) — token rotation will not persist.")
        }
    }

    static func load() -> OIDAuthState? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else {
            if status != errSecItemNotFound {
                print("[BackroadStatus TokenStore] WARNING: Keychain read failed (OSStatus \(status)).")
            }
            return nil
        }
        guard let data = result as? Data else { return nil }
        do {
            return try NSKeyedUnarchiver.unarchivedObject(ofClass: OIDAuthState.self, from: data)
        } catch {
            print("[BackroadStatus TokenStore] WARNING: failed to unarchive OIDAuthState: \(error)")
            return nil
        }
    }

    static func clear() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
