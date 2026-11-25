import Foundation

/// Hands back a fresh bearer token. The `forceRefresh` flag is set when the
/// server rejects the previous token with 401 — ``APIClient`` retries each
/// request exactly once with `forceRefresh: true` so stale/revoked access
/// tokens get rotated transparently instead of surfacing as auth errors.
typealias TokenProvider = @Sendable (_ forceRefresh: Bool) async throws -> String

final class APIClient {

    private let session: URLSession
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder

    init(session: URLSession = .shared) {
        self.session = session
        self.decoder = JSONDecoder()
        self.encoder = JSONEncoder()
    }

    /// Fetches canonical geometry for every road intersecting the given
    /// slippy-map tile at `z`. The server returns a flat GeoJSON
    /// FeatureCollection — same shape as `/paths/{id}` but with multiple
    /// road geometries and their Point road-features intermixed. Point
    /// features carry a `pathId` property that identifies their parent
    /// road; we partition on that and feed one bundle at a time into the
    /// shared per-road decoder.
    func bulkPaths(issuer: URL, tokenProvider: TokenProvider, z: Int, x: Int, y: Int) async throws -> [RoadDetail] {
        let url = integrationsV1(issuer: issuer)
            .appendingPathComponent("paths")
            .appendingPathComponent("geometry")
            .appendingPathComponent("\(z)")
            .appendingPathComponent("\(x)")
            .appendingPathComponent("\(y).json")

        let (data, http) = try await fetchWithAuthRetry(tokenProvider: tokenProvider) { token in
            var req = URLRequest(url: url)
            req.httpMethod = "GET"
            self.applyCommonHeaders(&req, token: token)
            return req
        }

        guard (200..<300).contains(http.statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? "<non-UTF8 body>"
            throw BackroadStatusError.apiError(status: http.statusCode, body: body)
        }

        let dto = try decoder.decode(RoadFeatureCollectionDTO.self, from: data)
        return Self.decodeRoads(from: dto.features)
    }

    /// Groups a flat bulk-response feature list into per-road bundles
    /// (road geometry + its Point features) and decodes each as a
    /// `RoadDetail`. Features whose owning road is missing from the
    /// response are dropped.
    static func decodeRoads(from features: [RoadFeatureCollectionDTO.FeatureDTO]) -> [RoadDetail] {
        var featuresByRoad: [String: [RoadFeatureCollectionDTO.FeatureDTO]] = [:]
        for feature in features {
            let roadId: String?
            switch feature.geometry.type {
            case "LineString", "MultiLineString":
                roadId = feature.properties.id
            case "Point":
                roadId = feature.properties.pathId
            default:
                roadId = nil
            }
            guard let id = roadId else { continue }
            featuresByRoad[id, default: []].append(feature)
        }
        return featuresByRoad.compactMap { id, bundle in
            decodeRoad(from: bundle, fallbackId: id)
        }
    }

    func me(tokenProvider: TokenProvider, issuer: URL) async throws -> MeResponse {
        let url = integrationsV1(issuer: issuer).appendingPathComponent("me")
        return try await get(url: url, tokenProvider: tokenProvider)
    }

    func createBulletin(tokenProvider: TokenProvider, issuer: URL, input: CreateBulletinInput) async throws -> Bulletin {
        let url = integrationsV1(issuer: issuer).appendingPathComponent("bulletins")
        return try await post(url: url, tokenProvider: tokenProvider, body: input)
    }

    func road(tokenProvider: TokenProvider, issuer: URL, id: String) async throws -> RoadDetail {
        let url = integrationsV1(issuer: issuer).appendingPathComponent("paths").appendingPathComponent(id)
        let dto: RoadFeatureCollectionDTO = try await get(url: url, tokenProvider: tokenProvider)
        guard let detail = Self.decodeRoad(from: dto.features, fallbackId: id) else {
            throw BackroadStatusError.apiError(status: 200, body: "Response did not contain a road geometry")
        }
        return detail
    }

    /// Turns a per-road slice of GeoJSON features (one MultiLineString /
    /// LineString plus any Point road-features belonging to the same road)
    /// into a `RoadDetail`. Returns nil if there's no road geometry in the
    /// slice — callers decide whether that's an error (single-road
    /// endpoint) or something to skip (bulk endpoint).
    static func decodeRoad(from features: [RoadFeatureCollectionDTO.FeatureDTO], fallbackId: String) -> RoadDetail? {
        guard let roadFeature = features.first(where: { $0.geometry.type == "MultiLineString" || $0.geometry.type == "LineString" }) else {
            return nil
        }

        let coordinates: [[Double]]
        let conditions: [RoadConditionPoint]
        switch roadFeature.geometry.coordinates {
        case .line(let coords):
            coordinates = coords
            conditions = []
        case .multiLine(let segments):
            coordinates = Self.flatten(segments: segments)
            conditions = Self.conditions(from: segments, names: roadFeature.properties.conditions ?? [])
        case .point:
            return nil
        }

        let roadFeatures: [RoadFeatureDetail] = features.compactMap { feature in
            guard feature.geometry.type == "Point",
                  case let .point(coord) = feature.geometry.coordinates,
                  coord.count >= 2,
                  let fid = feature.properties.id else { return nil }
            return RoadFeatureDetail(
                id: fid,
                type: feature.properties.name ?? "UNKNOWN",
                displayName: feature.properties.displayName ?? feature.properties.name ?? "Feature",
                description: feature.properties.description?.isEmpty == true ? nil : feature.properties.description,
                position: [coord[0], coord[1]]
            )
        }

        return RoadDetail(
            id: roadFeature.properties.id ?? fallbackId,
            name: roadFeature.properties.name ?? "",
            coordinates: coordinates,
            features: roadFeatures,
            conditions: conditions
        )
    }

    /// Concatenates MultiLineString segments into a single coord array. Each
    /// pair of adjacent segments shares a seam point (same coord ends segment
    /// N and starts segment N+1), so we skip duplicates at seams.
    private static func flatten(segments: [[[Double]]]) -> [[Double]] {
        var flat: [[Double]] = []
        for segment in segments {
            for coord in segment where flat.last != coord {
                flat.append(coord)
            }
        }
        return flat
    }

    /// Pairs each segment's last point with the corresponding condition name
    /// to reconstruct the server's `pathConditions` list. See the server's
    /// `Path.getConditionSegments(...)` — the last coord of each segment is
    /// the condition anchor point for that segment's condition.
    private static func conditions(from segments: [[[Double]]], names: [String]) -> [RoadConditionPoint] {
        let count = min(segments.count, names.count)
        var result: [RoadConditionPoint] = []
        result.reserveCapacity(count)
        for i in 0..<count {
            guard let last = segments[i].last, last.count >= 2 else { continue }
            result.append(RoadConditionPoint(point: last, condition: names[i]))
        }
        return result
    }

    func uploadDraftPhoto(tokenProvider: TokenProvider, issuer: URL, data: Data, fileName: String) async throws -> DraftPhoto {
        let url = integrationsV1(issuer: issuer).appendingPathComponent("draft").appendingPathComponent("upload")
        let boundary = "BackroadStatus-\(UUID().uuidString)"
        let body = Self.multipartBody(boundary: boundary, fieldName: "file", fileName: fileName, mimeType: "image/jpeg", data: data)

        let (respData, http) = try await fetchWithAuthRetry(tokenProvider: tokenProvider) { token in
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            req.setValue("application/json", forHTTPHeaderField: "Accept")
            req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
            req.httpBody = body
            return req
        }

        guard (200..<300).contains(http.statusCode) else {
            let body = String(data: respData, encoding: .utf8) ?? "<non-UTF8 body>"
            print("[BackroadStatus] \(http.statusCode) \(url.absoluteString) — body: \(body.prefix(500))")
            throw BackroadStatusError.apiError(status: http.statusCode, body: body)
        }
        return try decoder.decode(DraftPhoto.self, from: respData)
    }

    private static func multipartBody(boundary: String, fieldName: String, fileName: String, mimeType: String, data: Data) -> Data {
        var body = Data()
        let newline = "\r\n"
        body.append("--\(boundary)\(newline)".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"\(fieldName)\"; filename=\"\(fileName)\"\(newline)".data(using: .utf8)!)
        body.append("Content-Type: \(mimeType)\(newline)\(newline)".data(using: .utf8)!)
        body.append(data)
        body.append("\(newline)--\(boundary)--\(newline)".data(using: .utf8)!)
        return body
    }

    private func integrationsV1(issuer: URL) -> URL {
        issuer
            .appendingPathComponent("api")
            .appendingPathComponent("integrations")
            .appendingPathComponent("v1")
    }

    private func get<T: Decodable>(url: URL, tokenProvider: TokenProvider) async throws -> T {
        try await send(tokenProvider: tokenProvider) { token in
            var req = URLRequest(url: url)
            req.httpMethod = "GET"
            self.applyCommonHeaders(&req, token: token)
            return req
        }
    }

    private func post<Body: Encodable, Response: Decodable>(url: URL, tokenProvider: TokenProvider, body: Body) async throws -> Response {
        let data = try encoder.encode(body)
        return try await send(tokenProvider: tokenProvider) { token in
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            self.applyCommonHeaders(&req, token: token)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = data
            return req
        }
    }

    private func applyCommonHeaders(_ req: inout URLRequest, token: String) {
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
    }

    // MARK: GeoJSON FeatureCollection decoding for /paths/{id}

    struct RoadFeatureCollectionDTO: Decodable {
        let features: [FeatureDTO]

        struct FeatureDTO: Decodable {
            let geometry: GeometryDTO
            let properties: PropertiesDTO
        }

        struct GeometryDTO: Decodable {
            let type: String
            let coordinates: CoordsDTO
        }

        struct PropertiesDTO: Decodable {
            let id: String?
            let name: String?
            let displayName: String?
            let description: String?
            /// For MultiLineString road geometries, the server emits one
            /// condition name per sub-line (same order as the segments).
            let conditions: [String]?
            /// On Point road-features in the bulk response, identifies the
            /// road the feature belongs to. Nil on road geometry features
            /// (their own `id` is the road id) and on the single-road
            /// endpoint (there's only one road in that response).
            let pathId: String?
        }

        enum CoordsDTO: Decodable {
            case point([Double])
            case line([[Double]])
            case multiLine([[[Double]]])

            init(from decoder: Decoder) throws {
                let container = try decoder.singleValueContainer()
                if let multi = try? container.decode([[[Double]]].self) {
                    self = .multiLine(multi)
                } else if let line = try? container.decode([[Double]].self) {
                    self = .line(line)
                } else if let point = try? container.decode([Double].self) {
                    self = .point(point)
                } else {
                    throw DecodingError.dataCorruptedError(
                        in: container,
                        debugDescription: "Unknown GeoJSON coordinates shape"
                    )
                }
            }
        }
    }

    private func send<T: Decodable>(
        tokenProvider: TokenProvider,
        makeRequest: @escaping (String) -> URLRequest
    ) async throws -> T {
        let (data, http) = try await fetchWithAuthRetry(tokenProvider: tokenProvider, makeRequest: makeRequest)
        guard (200..<300).contains(http.statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? "<non-UTF8 body>"
            print("[BackroadStatus] \(http.statusCode) \(http.url?.absoluteString ?? "?") — body: \(body.prefix(1000))")
            throw BackroadStatusError.apiError(status: http.statusCode, body: body)
        }
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            // Surface the first ~500 bytes of what the server actually returned
            // so decode mismatches are self-diagnosable.
            let preview = String(data: data.prefix(500), encoding: .utf8) ?? "<non-UTF8 body>"
            // Print to Xcode console too — banners and short toasts are hard to
            // read/copy and the preview is the thing you actually need.
            print("[BackroadStatus] Decode failed for \(http.url?.absoluteString ?? "?"): \(error)")
            print("[BackroadStatus] Body preview: \(preview)")
            throw BackroadStatusError.apiError(
                status: http.statusCode,
                body: "Decode failed: \(error). Body preview: \(preview)"
            )
        }
    }

    /// Builds a request with the current access token and runs it; if the
    /// server returns 401, forces a token refresh and retries exactly once.
    ///
    /// The retry covers two failure modes we can't tell apart from the
    /// client: (1) AppAuth handing back a stale access token because
    /// `performAction` short-circuited its freshness check, and (2) the
    /// server revoking the token mid-session before its expiry. In both
    /// cases a forced refresh is the correct response.
    ///
    /// Retries are attempted only once — if the refreshed token also gets a
    /// 401, we return that response and let the caller surface it (which
    /// typically means the refresh token itself is dead; AppAuth's
    /// `authState(_:didEncounterAuthorizationError:)` will have fired and
    /// cleared local state).
    private func fetchWithAuthRetry(
        tokenProvider: TokenProvider,
        makeRequest: (String) -> URLRequest
    ) async throws -> (Data, HTTPURLResponse) {
        if BackroadStatus.simulateOffline {
            throw BackroadStatusError.simulatedOffline
        }
        let firstToken = try await tokenProvider(false)
        let firstReq = makeRequest(firstToken)
        let (data, response) = try await session.data(for: firstReq)
        guard let http = response as? HTTPURLResponse else {
            throw BackroadStatusError.apiError(status: -1, body: nil)
        }
        guard http.statusCode == 401 else {
            return (data, http)
        }

        print("[BackroadStatus] 401 \(firstReq.url?.absoluteString ?? "?") — forcing token refresh and retrying once")
        let refreshedToken = try await tokenProvider(true)
        let retryReq = makeRequest(refreshedToken)
        let (retryData, retryResponse) = try await session.data(for: retryReq)
        guard let retryHttp = retryResponse as? HTTPURLResponse else {
            throw BackroadStatusError.apiError(status: -1, body: nil)
        }
        return (retryData, retryHttp)
    }
}
