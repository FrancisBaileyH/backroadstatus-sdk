import Foundation
import UIKit

/// `[lon, lat]`-in-spirit coordinate. Pure Swift so Core/MapKit types don't
/// depend on any map SDK; adapters convert to their native coord type at the
/// boundary (e.g. `MapLibreMapProvider` wraps this into `CLLocationCoordinate2D`
/// before calling MapLibre).
public struct Coordinate: Equatable, Sendable, Codable {
    public var longitude: Double
    public var latitude: Double

    public init(longitude: Double, latitude: Double) {
        self.longitude = longitude
        self.latitude = latitude
    }

    /// Convenience to mirror the `[lon, lat]` arrays used throughout the SDK's
    /// server-facing types.
    public init(lonLat: [Double]) {
        precondition(lonLat.count >= 2, "lonLat requires two components")
        self.longitude = lonLat[0]
        self.latitude = lonLat[1]
    }

    public var lonLat: [Double] { [longitude, latitude] }
}

/// Axis-aligned geographic bounding box. South-west + north-east corners.
public struct BoundingBox: Equatable, Sendable {
    public var sw: Coordinate
    public var ne: Coordinate

    public init(sw: Coordinate, ne: Coordinate) {
        self.sw = sw
        self.ne = ne
    }

    /// Smallest bounding box that contains every provided coordinate, or
    /// `nil` if the input is empty.
    public static func fitting(_ coordinates: [Coordinate]) -> BoundingBox? {
        guard let first = coordinates.first else { return nil }
        var minLat = first.latitude, maxLat = first.latitude
        var minLon = first.longitude, maxLon = first.longitude
        for c in coordinates.dropFirst() {
            if c.latitude < minLat { minLat = c.latitude }
            if c.latitude > maxLat { maxLat = c.latitude }
            if c.longitude < minLon { minLon = c.longitude }
            if c.longitude > maxLon { maxLon = c.longitude }
        }
        return BoundingBox(
            sw: Coordinate(longitude: minLon, latitude: minLat),
            ne: Coordinate(longitude: maxLon, latitude: maxLat)
        )
    }
}

/// Padding around the map viewport. Mirrors `UIEdgeInsets` layout but lives
/// in MapKit so the protocol has no UIKit dependency at the signature level.
public struct MapEdgeInsets: Equatable, Sendable {
    public var top: Double
    public var left: Double
    public var bottom: Double
    public var right: Double

    public init(top: Double, left: Double, bottom: Double, right: Double) {
        self.top = top
        self.left = left
        self.bottom = bottom
        self.right = right
    }

    public static let zero = MapEdgeInsets(top: 0, left: 0, bottom: 0, right: 0)
    public static func all(_ v: Double) -> MapEdgeInsets {
        MapEdgeInsets(top: v, left: v, bottom: v, right: v)
    }
}

/// A pre-rendered marker to place on the map. Deliberately image-based so
/// one annotation representation works across SDKs; MapLibre adapters host
/// these via a hidden symbol layer rather than per-annotation UIViews.
public struct Annotation: Equatable {
    public let id: String
    public var coordinate: Coordinate
    /// Pre-rasterized marker image. Composer code renders SwiftUI / UIKit
    /// marker views to `UIImage` once per visual variant and reuses the
    /// result across position changes.
    public let image: UIImage
    /// Anchor within the image: (0,0) = top-left, (0.5,1) = bottom-center.
    /// Used by adapters to offset the image so the right pixel sits on
    /// `coordinate`.
    public let anchor: CGPoint
    /// Higher values draw on top of lower values. Adapters may map this to
    /// symbol-sort-key or layer stacking depending on their SDK.
    public let zPriority: Int

    public init(
        id: String,
        coordinate: Coordinate,
        image: UIImage,
        anchor: CGPoint = CGPoint(x: 0.5, y: 0.5),
        zPriority: Int = 0
    ) {
        self.id = id
        self.coordinate = coordinate
        self.image = image
        self.anchor = anchor
        self.zPriority = zPriority
    }
}
