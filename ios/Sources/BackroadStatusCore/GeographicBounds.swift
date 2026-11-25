import Foundation

/// South-west + north-east corners, in lon/lat degrees. Mirrors
/// `BackroadStatusMapKit.BoundingBox` but lives in Core so the bulk
/// geometry downloader doesn't drag the MapKit module along.
///
/// Hosts bridging from a map SDK (e.g. `MLNCoordinateBounds`) build this
/// at the call site — the SDK itself never sees the native type.
public struct GeographicBounds: Equatable, Sendable, Codable {
    public let southLatitude: Double
    public let westLongitude: Double
    public let northLatitude: Double
    public let eastLongitude: Double

    public init(
        southLatitude: Double,
        westLongitude: Double,
        northLatitude: Double,
        eastLongitude: Double
    ) {
        self.southLatitude = southLatitude
        self.westLongitude = westLongitude
        self.northLatitude = northLatitude
        self.eastLongitude = eastLongitude
    }
}
