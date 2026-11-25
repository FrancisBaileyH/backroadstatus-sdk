import Foundation
import UIKit

/// Entry point for the BackroadStatus SDK.
///
/// Call ``configure(clientId:redirectUri:issuer:)`` once at app startup,
/// then use ``signIn(presenting:scopes:)`` to kick off OAuth and
/// ``me()`` / other API methods to read data.
public final class BackroadStatus {
    public static let shared = BackroadStatus()

    public struct Config {
        public let clientId: String
        public let redirectUri: URL
        public let issuer: URL

        public init(clientId: String, redirectUri: URL, issuer: URL) {
            self.clientId = clientId
            self.redirectUri = redirectUri
            self.issuer = issuer
        }
    }

    public static let defaultScopes = ["bulletins:read", "bulletins:write", "profile:read"]

    /// Testing-only knob: when true, every API call throws immediately as if the
    /// network were unreachable. Useful for validating offline fallback paths
    /// without actually airplane-moding the device.
    public static var simulateOffline: Bool = false

    private(set) var config: Config?
    let auth = Auth()
    let api = APIClient()

    private init() {}

    public static func configure(clientId: String, redirectUri: URL, issuer: URL) {
        shared.config = Config(clientId: clientId, redirectUri: redirectUri, issuer: issuer)
        shared.auth.restore()
    }

    public var isAuthenticated: Bool { auth.hasValidToken }

    public func signIn(
        presenting viewController: UIViewController,
        scopes: [String] = defaultScopes
    ) async throws {
        let config = try requireConfig()
        try await auth.signIn(presenting: viewController, config: config, scopes: scopes)
    }

    /// Convenience gate for host-app "sign in to continue" buttons. Returns
    /// immediately if the user is already signed in; otherwise finds the
    /// top-most view controller and presents the OAuth flow, awaiting its
    /// result. Rethrows anything `signIn(presenting:)` would throw — including
    /// the user-cancelled-the-OAuth-sheet case — so callers can distinguish
    /// "user bailed, don't proceed" from a happy path.
    ///
    /// Use this at action-trigger points (e.g. a "Create Report" tap) rather
    /// than at view-open time: it lets unauthenticated users browse the
    /// surrounding UI and only prompts them when they try to do something
    /// that actually needs an identity.
    @MainActor
    public func ensureAuthenticated(scopes: [String] = defaultScopes) async throws {
        if isAuthenticated { return }
        guard let presenter = Self.topmostPresentingViewController() else {
            throw BackroadStatusError.notAuthenticated
        }
        try await signIn(presenting: presenter, scopes: scopes)
    }

    /// Top-most view controller the SDK uses as a presenting anchor (OAuth
    /// sign-in, the auth-gated report composer, etc.). Exposed so other
    /// SDK modules (e.g. `BackroadStatusComposerUI`) can reuse the same
    /// traversal, and host apps can grab a presenter from a SwiftUI
    /// context that doesn't easily expose one.
    @MainActor
    public static func topmostPresentingViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .first { $0.activationState == .foregroundActive } as? UIWindowScene
        let window = scene?.windows.first { $0.isKeyWindow }
        var vc = window?.rootViewController
        while let presented = vc?.presentedViewController {
            vc = presented
        }
        return vc
    }

    public func signOut() {
        auth.signOut()
    }

    public func me() async throws -> MeResponse {
        let config = try requireConfig()
        return try await api.me(tokenProvider: tokenProvider(config: config), issuer: config.issuer)
    }

    public func createBulletin(_ input: CreateBulletinInput) async throws -> Bulletin {
        let config = try requireConfig()
        return try await api.createBulletin(tokenProvider: tokenProvider(config: config), issuer: config.issuer, input: input)
    }

    /// Fetches full road geometry for a path. The composer uses this as the
    /// primary source and falls back to tile-based stitching if this throws.
    public func road(id: String) async throws -> RoadDetail {
        let config = try requireConfig()
        return try await api.road(tokenProvider: tokenProvider(config: config), issuer: config.issuer, id: id)
    }

    /// Captures `auth` and `config` in a closure the API layer invokes to
    /// fetch a bearer token — once at the start of each request, and again
    /// (with `forceRefresh: true`) only when the first attempt comes back
    /// 401. See ``APIClient.fetchWithAuthRetry`` for the retry contract.
    func tokenProvider(config: Config) -> TokenProvider {
        { [auth] forceRefresh in
            try await auth.accessToken(config: config, forceRefresh: forceRefresh)
        }
    }

    /// Canonical geometry for `id` out of the offline cache, if the host
    /// app pre-downloaded the containing region via `downloadGeometry(bounds:)`.
    /// Non-blocking, synchronous — safe to call before falling back to `road(id:)`.
    public func cachedRoad(id: String) -> RoadDetail? {
        GeometryStore.shared.road(id: id)
    }

    /// Fetches canonical road geometry for every road inside `bounds` by
    /// walking the slippy-map tiles at `zoom` and hitting
    /// `/api/paths/bulk/{z}/{x}/{y}.json` for each. Roads are stored in the
    /// on-disk cache, keyed by road id, and are then served by
    /// ``cachedRoad(id:)`` — call this alongside your MLN offline pack
    /// download so the composer has full-resolution geometry when the user
    /// taps a road offline.
    ///
    /// The bulk endpoint returns unsimplified canonical geometry, so
    /// condition-rating points produced from cached coords match the
    /// server's record exactly — unlike anything derived from tile-rendered
    /// (DP-simplified) MVT.
    ///
    /// `onProgress` fires off the main thread; hop to main to update UI.
    public func downloadGeometry(
        bounds: GeographicBounds,
        zoom: Int = GeometryDownloader.defaultZoom,
        onProgress: @Sendable @escaping (GeometryDownloader.Progress) -> Void = { _ in }
    ) async throws {
        _ = try requireConfig()
        try await GeometryDownloader().download(bounds: bounds, zoom: zoom, onProgress: onProgress)
    }

    /// Uploads raw image bytes as a draft photo. The returned `reference` is
    /// what you include in `CreateBulletinInput.photos[].reference` on submit.
    public func uploadDraftPhoto(_ data: Data, fileName: String = "photo.jpg") async throws -> DraftPhoto {
        let config = try requireConfig()
        return try await api.uploadDraftPhoto(tokenProvider: tokenProvider(config: config), issuer: config.issuer, data: data, fileName: fileName)
    }

    func requireConfig() throws -> Config {
        guard let config else { throw BackroadStatusError.notConfigured }
        return config
    }
}

public struct RoadDetail: Sendable, Codable {
    public let id: String
    public let name: String
    /// `[lon, lat]` pairs along the road from start to end. For
    /// MultiLineString responses, condition segments are concatenated with
    /// seam-dedupe so this stays a single ordered path.
    public let coordinates: [[Double]]
    /// Road features (gates, bridges, washouts, etc.) attached to this road.
    public let features: [RoadFeatureDetail]
    /// Current condition points along the road in order. Each entry's `point`
    /// is a coord in `coordinates`; the condition applies to the segment
    /// leading into that point. Empty for responses without per-segment
    /// condition metadata.
    public let conditions: [RoadConditionPoint]

    public init(id: String, name: String, coordinates: [[Double]], features: [RoadFeatureDetail] = [], conditions: [RoadConditionPoint] = []) {
        self.id = id
        self.name = name
        self.coordinates = coordinates
        self.features = features
        self.conditions = conditions
    }
}

public struct RoadConditionPoint: Sendable, Equatable, Codable {
    /// `[lon, lat]`. Matches a coord in the owning `RoadDetail.coordinates`.
    public let point: [Double]
    /// Enum name of the server's `PathCondition` ("NOMINAL", "DEGRADED", …).
    /// Kept as `String` here so core doesn't depend on the UI's `Condition`
    /// enum; callers can map via `Condition(rawValue:)` when needed.
    public let condition: String

    public init(point: [Double], condition: String) {
        self.point = point
        self.condition = condition
    }
}

public struct RoadFeatureDetail: Sendable, Identifiable, Equatable, Codable {
    public let id: String
    /// Enum name of the PathFeatureType ("GATE", "BRIDGE", "WASHOUT", …).
    public let type: String
    /// Human-readable name ("Gate", "Bridge").
    public let displayName: String
    public let description: String?
    /// `[lon, lat]`.
    public let position: [Double]

    public init(id: String, type: String, displayName: String, description: String?, position: [Double]) {
        self.id = id
        self.type = type
        self.displayName = displayName
        self.description = description
        self.position = position
    }
}

public struct DraftPhoto: Decodable, Sendable {
    public let reference: String
    /// Optional `[lon, lat]` read from the image's EXIF data if present.
    public let location: [Double]?
}

public struct Bulletin: Decodable, Identifiable, Sendable {
    public let id: String
    public let pathId: String
    public let pathName: String
    public let comment: String
    public let bulletinDate: Int64
    public let lastModifiedDate: Int64
    public let createdDate: Int64
    public let ownerId: String
    public let ownerName: String
    public let type: String
    public let replyCount: Int64
    public let distanceMeters: Double?
    public let conditionRating: String?
    public let photos: [BulletinPhoto]
    public let vehicles: [BulletinVehicle]
}

public struct BulletinPhoto: Decodable, Sendable {
    public let reference: String
    public let thumbnailReference: String
    public let caption: String
    public let location: [Double]?
}

public struct BulletinVehicle: Decodable, Sendable {
    public let category: String
    public let name: String?
    public let description: String?
}

public struct CreateBulletinInput: Codable, Sendable {
    public let pathId: String
    public let reportDate: String
    public let description: String
    /// Photos attached to the bulletin. `var` so the submission queue can
    /// fill this in after uploading pending photo bytes — a submission
    /// that was offline on Submit will reach `createBulletin` with an
    /// initially-empty photos array and have it populated once the queue
    /// has gotten references for each one.
    public var photos: [PhotoInput]
    public let conditionRatings: [ConditionRatingInput]
    public let vehicles: [VehicleInput]
    public let featureUpdates: [FeatureUpdateInput]
    /// Idempotency key — the server dedupes `(ownerId, clientSubmissionId)`
    /// so retries from an offline queue never create duplicate bulletins.
    /// Defaults to a freshly-minted UUID per submission; override only when
    /// you're explicitly resuming a previously-queued submission.
    public let clientSubmissionId: String

    public init(
        pathId: String,
        reportDate: Date = Date(),
        description: String,
        photos: [PhotoInput] = [],
        conditionRatings: [ConditionRatingInput] = [],
        vehicles: [VehicleInput] = [],
        featureUpdates: [FeatureUpdateInput] = [],
        clientSubmissionId: String = UUID().uuidString
    ) {
        self.pathId = pathId
        self.reportDate = Self.isoDateFormatter.string(from: reportDate)
        self.description = description
        self.photos = photos
        self.conditionRatings = conditionRatings
        self.vehicles = vehicles
        self.featureUpdates = featureUpdates
        self.clientSubmissionId = clientSubmissionId
    }

    private static let isoDateFormatter: DateFormatter = {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd"
        fmt.locale = Locale(identifier: "en_US_POSIX")
        // Use the user's local time zone so that a Date picked as "today" in a
        // DatePicker (which represents local midnight) formats to the same
        // calendar day the user sees. Formatting in UTC would roll the day
        // forward for anyone west of UTC after local noon or so.
        fmt.timeZone = .current
        return fmt
    }()
}

public struct PhotoInput: Codable, Sendable {
    public let reference: String
    public let caption: String
    public init(reference: String, caption: String) {
        self.reference = reference
        self.caption = caption
    }
}

public struct ConditionRatingInput: Codable, Sendable {
    public let point: [Double]
    public let condition: String
    public init(point: [Double], condition: String) {
        self.point = point
        self.condition = condition
    }
}

public struct VehicleInput: Codable, Sendable {
    public let category: String
    public let name: String?
    public let description: String?
    public init(category: String, name: String? = nil, description: String? = nil) {
        self.category = category
        self.name = name
        self.description = description
    }
}

public struct FeatureUpdateInput: Codable, Sendable {
    public let featureId: String?
    public let finalType: String
    public let comment: String?
    public let removed: Bool
    public let created: Bool
    public let position: [Double]?
    public init(featureId: String?, finalType: String, comment: String? = nil, removed: Bool = false, created: Bool = false, position: [Double]? = nil) {
        self.featureId = featureId
        self.finalType = finalType
        self.comment = comment
        self.removed = removed
        self.created = created
        self.position = position
    }
}

public struct MeResponse: Decodable {
    public let id: String
    public let email: String
    public let name: String
}

public enum BackroadStatusError: Error, LocalizedError {
    case notConfigured
    case notAuthenticated
    case oauthFailed(underlying: Error?)
    case apiError(status: Int, body: String?)
    case simulatedOffline

    public var errorDescription: String? {
        switch self {
        case .notConfigured: return "BackroadStatus.configure(...) was not called."
        case .notAuthenticated: return "Not signed in."
        case .oauthFailed(let err): return "OAuth flow failed: \(err?.localizedDescription ?? "unknown")"
        case .apiError(let status, let body): return "API error \(status): \(body ?? "")"
        case .simulatedOffline: return "Offline (simulated via BackroadStatus.simulateOffline)."
        }
    }
}
