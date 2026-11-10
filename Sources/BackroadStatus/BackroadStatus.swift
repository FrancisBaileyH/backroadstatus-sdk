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

    public func signOut() {
        auth.signOut()
    }

    public func me() async throws -> MeResponse {
        let config = try requireConfig()
        let token = try await auth.accessToken(config: config)
        return try await api.me(token: token, issuer: config.issuer)
    }

    public func createBulletin(_ input: CreateBulletinInput) async throws -> Bulletin {
        let config = try requireConfig()
        let token = try await auth.accessToken(config: config)
        return try await api.createBulletin(token: token, issuer: config.issuer, input: input)
    }

    func requireConfig() throws -> Config {
        guard let config else { throw BackroadStatusError.notConfigured }
        return config
    }
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

public struct CreateBulletinInput: Encodable, Sendable {
    public let pathId: String
    public let reportDate: String
    public let description: String
    public let photos: [PhotoInput]
    public let conditionRatings: [ConditionRatingInput]
    public let vehicles: [VehicleInput]
    public let featureUpdates: [FeatureUpdateInput]

    public init(
        pathId: String,
        reportDate: Date = Date(),
        description: String,
        photos: [PhotoInput] = [],
        conditionRatings: [ConditionRatingInput] = [],
        vehicles: [VehicleInput] = [],
        featureUpdates: [FeatureUpdateInput] = []
    ) {
        self.pathId = pathId
        self.reportDate = Self.isoDateFormatter.string(from: reportDate)
        self.description = description
        self.photos = photos
        self.conditionRatings = conditionRatings
        self.vehicles = vehicles
        self.featureUpdates = featureUpdates
    }

    private static let isoDateFormatter: DateFormatter = {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd"
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.timeZone = TimeZone(secondsFromGMT: 0)
        return fmt
    }()
}

public struct PhotoInput: Encodable, Sendable {
    public let reference: String
    public let caption: String
    public init(reference: String, caption: String) {
        self.reference = reference
        self.caption = caption
    }
}

public struct ConditionRatingInput: Encodable, Sendable {
    public let point: [Double]
    public let condition: String
    public init(point: [Double], condition: String) {
        self.point = point
        self.condition = condition
    }
}

public struct VehicleInput: Encodable, Sendable {
    public let category: String
    public let name: String?
    public let description: String?
    public init(category: String, name: String? = nil, description: String? = nil) {
        self.category = category
        self.name = name
        self.description = description
    }
}

public struct FeatureUpdateInput: Encodable, Sendable {
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

    public var errorDescription: String? {
        switch self {
        case .notConfigured: return "BackroadStatus.configure(...) was not called."
        case .notAuthenticated: return "Not signed in."
        case .oauthFailed(let err): return "OAuth flow failed: \(err?.localizedDescription ?? "unknown")"
        case .apiError(let status, let body): return "API error \(status): \(body ?? "")"
        }
    }
}
