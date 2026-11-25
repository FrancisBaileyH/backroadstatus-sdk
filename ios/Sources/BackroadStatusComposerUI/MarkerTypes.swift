import UIKit

/// One slider-handle position + color. The composer builds these each time
/// the slider changes; the driver rasterizes them to `Annotation`s with
/// pre-rendered dot images and hands them to the active `MapProvider`.
public struct SliderMarker: Equatable {
    /// Stable identity across diffs so the driver can update position/color
    /// in place instead of churning annotations.
    public let id: String
    /// `[lon, lat]`.
    public let position: [Double]
    public let color: UIColor

    public init(id: String, position: [Double], color: UIColor) {
        self.id = id
        self.position = position
        self.color = color
    }
}

/// Value-type description of a user-created road feature pin (gate /
/// bridge / warning / …). Rendered on both the composer's mini-map and
/// the Add Feature picker map.
public struct PlacedFeatureMarker: Equatable, Identifiable {
    /// Stable identity across SwiftUI diffs — composer uses the created
    /// feature's UUID string, add-feature sheet uses "current" for its
    /// lone in-progress pin.
    public let id: String
    /// Server `PathFeatureType` enum name ("OPEN_GATE", "WARNING", …).
    public let typeName: String
    /// `[lon, lat]` matching a coord in the road's geometry.
    public let position: [Double]

    public init(id: String, typeName: String, position: [Double]) {
        self.id = id
        self.typeName = typeName
        self.position = position
    }
}
