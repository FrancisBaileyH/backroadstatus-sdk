import Foundation
import SwiftUI

/// Maps a server `PathFeatureType` enum name to the bundled icon asset.
/// Mirrors the web `/icons/{type}.svg` lookup so discovered features (from
/// the API) and user-created features (from the composer) render with the
/// exact same visuals users see on the map's symbol layers and on
/// backroadstatus.com.
public enum FeatureIcon {
    /// Types we ship an SVG asset for. The imageset name matches the type
    /// name exactly — look them up in `FeatureIcons.xcassets`.
    private static let bundled: Set<String> = [
        "WARNING",
        "OPEN_GATE", "LOCKED_GATE", "POTENTIAL_GATE",
        "MANAGED_OPEN_GATE", "MANAGED_LOCKED_GATE",
        "BRIDGE_IN", "BRIDGE_OUT", "BRIDGE_UNKNOWN", "FOOT_BRIDGE",
        "IMPASSE", "BYPASS", "HYDROPOWER_STATION"
    ]

    /// SwiftUI image for a feature type. Returns the bundled SVG asset when
    /// one exists; otherwise falls back to a neutral SF Symbol so unknown /
    /// future feature types still render something recognizable.
    public static func image(for typeName: String) -> Image {
        if bundled.contains(typeName) {
            return Image(typeName, bundle: .module)
        }
        return Image(systemName: "mappin.and.ellipse")
    }
}

/// User-facing state for a discovered road feature in the composer's
/// verification checklist. The "removed" concept lives as a toggle inside
/// the Changed flow ("No longer present") rather than its own top-level
/// option — mirrors the web composer.
///
/// Submission mapping:
///   - `.unknown`   → no FeatureUpdateInput emitted
///   - `.noChange`  → verified; `removed=false`, `finalType == originalType`
///   - `.changed`   → user tweaks + optional removed via companion toggle
public enum FeatureStatus: String, CaseIterable, Identifiable, Sendable, Codable {
    case unknown
    case noChange
    case changed

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .unknown:  return "Unknown"
        case .noChange: return "No Change"
        case .changed:  return "Changed"
        }
    }
}

/// Which `PathFeatureType` values a user can pick when transitioning the given
/// feature type with the "Changed" status. Mirrors `optionsFor(type)` in
/// feature-updates.js. Returns nil for types that can only receive comment
/// updates (impasses, warnings, bypasses, hydropower stations, etc.).
public enum FeatureTransition {
    public static func options(for type: String) -> [TypeOption]? {
        switch type {
        case "OPEN_GATE", "LOCKED_GATE", "POTENTIAL_GATE":
            return [
                TypeOption(rawValue: "OPEN_GATE", displayName: "Open"),
                TypeOption(rawValue: "LOCKED_GATE", displayName: "Locked")
            ]
        case "BRIDGE_IN", "BRIDGE_OUT", "FOOT_BRIDGE", "BRIDGE_UNKNOWN":
            return [
                TypeOption(rawValue: "BRIDGE_IN", displayName: "Bridge In"),
                TypeOption(rawValue: "BRIDGE_OUT", displayName: "Bridge Out"),
                TypeOption(rawValue: "FOOT_BRIDGE", displayName: "Foot Bridge")
            ]
        default:
            return nil
        }
    }

    public struct TypeOption: Identifiable, Hashable, Sendable {
        public let rawValue: String
        public let displayName: String
        public var id: String { rawValue }
    }
}
