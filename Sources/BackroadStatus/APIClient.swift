import Foundation

final class APIClient {

    private let session: URLSession
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder

    init(session: URLSession = .shared) {
        self.session = session
        self.decoder = JSONDecoder()
        self.encoder = JSONEncoder()
    }

    func me(token: String, issuer: URL) async throws -> MeResponse {
        let url = integrationsV1(issuer: issuer).appendingPathComponent("me")
        return try await get(url: url, token: token)
    }

    func createBulletin(token: String, issuer: URL, input: CreateBulletinInput) async throws -> Bulletin {
        let url = integrationsV1(issuer: issuer).appendingPathComponent("bulletins")
        return try await post(url: url, token: token, body: input)
    }

    private func integrationsV1(issuer: URL) -> URL {
        issuer
            .appendingPathComponent("api")
            .appendingPathComponent("integrations")
            .appendingPathComponent("v1")
    }

    private func get<T: Decodable>(url: URL, token: String) async throws -> T {
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        applyCommonHeaders(&req, token: token)
        return try await send(req)
    }

    private func post<Body: Encodable, Response: Decodable>(url: URL, token: String, body: Body) async throws -> Response {
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        applyCommonHeaders(&req, token: token)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try encoder.encode(body)
        return try await send(req)
    }

    private func applyCommonHeaders(_ req: inout URLRequest, token: String) {
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
    }

    private func send<T: Decodable>(_ req: URLRequest) async throws -> T {
        let (data, response) = try await session.data(for: req)
        guard let http = response as? HTTPURLResponse else {
            throw BackroadStatusError.apiError(status: -1, body: nil)
        }
        guard (200..<300).contains(http.statusCode) else {
            throw BackroadStatusError.apiError(status: http.statusCode, body: String(data: data, encoding: .utf8))
        }
        return try decoder.decode(T.self, from: data)
    }
}
