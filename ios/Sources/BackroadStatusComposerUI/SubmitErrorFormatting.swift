import Foundation
import BackroadStatusCore

/// Turns a raw submit-time error into something the user can act on. Shared
/// between the composer's inline error card and the Pending Bulletins row
/// subtitle so both surfaces describe the same failure in the same words.
///
/// API error bodies are usually JSON like `{"detail": "..."}` — pull that out;
/// fall back to a status-code-based message, then the raw description.
func friendlySubmitError(_ error: Error) -> String {
    if let api = error as? BackroadStatusError {
        switch api {
        case .apiError(let status, let body):
            if let body, let parsed = extractErrorDetail(from: body) {
                return parsed
            }
            switch status {
            case 401, 403: return "You're not authorized to submit this report. Try signing in again."
            case 400..<500: return "The server couldn't accept this report. Please check your inputs and try again."
            case 500..<600: return "The server ran into a problem. Please try again shortly."
            default: return "Couldn't reach the server. Check your connection and try again."
            }
        case .notAuthenticated: return "You're not signed in."
        case .simulatedOffline: return "You're offline."
        case .notConfigured, .oauthFailed: return api.errorDescription ?? error.localizedDescription
        }
    }
    return error.localizedDescription
}

/// Same contract as `friendlySubmitError`, but for a previously-stored
/// `lastError` string pulled off a queued/failed submission. We don't have
/// the original `Error` by then, so we do a lightweight JSON probe and
/// otherwise return the string verbatim.
func friendlyLastError(_ raw: String) -> String {
    if let parsed = extractErrorDetail(from: raw), !parsed.isEmpty {
        return parsed
    }
    return raw
}

private func extractErrorDetail(from body: String) -> String? {
    guard let data = body.data(using: .utf8),
          let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    else { return nil }
    for key in ["detail", "message", "error"] {
        if let value = obj[key] as? String, !value.isEmpty { return value }
    }
    return nil
}

extension JSONEncoder {
    /// Matches the decoder DraftStore installs in `peekDraftOnDisk()` / load
    /// paths. The composer uses this to serialize a `Draft` for the
    /// queue's opaque `draftSnapshot` blob — keeping dates ISO-8601 on
    /// both sides means a resumed draft parses cleanly.
    static var iso8601: JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }
}

extension JSONDecoder {
    static var iso8601: JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }
}
