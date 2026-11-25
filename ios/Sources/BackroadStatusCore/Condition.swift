import SwiftUI
import UIKit

/// Mirrors the server's PathCondition enum (NOMINAL/DEGRADED/HEAVILY_DEGRADED/
/// IMPASSABLE/UNKNOWN) with display metadata. UNKNOWN is intentionally
/// not user-pickable — it's auto-prepended at submit time when the user
/// started past 0 m (the "unverified segment before where I began driving").
public enum Condition: String, CaseIterable, Identifiable, Hashable, Sendable, Codable {
    case nominal = "NOMINAL"
    case degraded = "DEGRADED"
    case heavilyDegraded = "HEAVILY_DEGRADED"
    case impassable = "IMPASSABLE"
    case unknown = "UNKNOWN"

    public var id: String { rawValue }

    /// Conditions the user can pick from the composer dropdown.
    public static let userPickable: [Condition] = [.nominal, .degraded, .heavilyDegraded, .impassable, .unknown]

    public var displayName: String {
        switch self {
        case .nominal: return "Good Condition"
        case .degraded: return "Somewhat Degraded"
        case .heavilyDegraded: return "Heavily Degraded"
        case .impassable: return "Impassable"
        case .unknown: return "Unknown"
        }
    }

    /// Mirrors the server-side PathCondition description. Used by the
    /// composer's condition-rating info sheet.
    public var description: String {
        switch self {
        case .nominal:
            return "Road is 2WD driveable in most cases or easily driven in a 4WD."
        case .degraded:
            return "Road has rough spots, water bars or moderate washouts and may require high-clearance 4WD."
        case .heavilyDegraded:
            return "Road requires high clearance 4WD. There may be challenging washouts, dangerous sections or exceptionally deep water bars."
        case .impassable:
            return "Road is impassable or requires highly specialized vehicles to drive."
        case .unknown:
            return "Road condition is unknown."
        }
    }

    /// Palette mirrors PathConditionColorScheme in MapScheme.js so iOS matches
    /// the web across slider tracks, chips, map markers, and the composer's
    /// focused-road overlay.
    public var uiColor: UIColor {
        switch self {
        case .nominal:         return UIColor(red: 0x43/255, green: 0x8a/255, blue: 0x62/255, alpha: 1) // #438a62
        case .degraded:        return UIColor(red: 0xed/255, green: 0xc4/255, blue: 0x38/255, alpha: 1) // #edc438
        case .heavilyDegraded: return UIColor(red: 0xd5/255, green: 0x62/255, blue: 0x65/255, alpha: 1) // #d56265
        case .impassable:      return UIColor(red: 0x25/255, green: 0x25/255, blue: 0x25/255, alpha: 1) // #252525
        case .unknown:         return UIColor(red: 0x82/255, green: 0x7b/255, blue: 0x70/255, alpha: 1) // #827b70
        }
    }

    /// Darkened palette used as the stroke color for the composer mini-map's
    /// focused-road border layer. Mirrors `*_DARK` entries in MapScheme.js.
    public var darkUIColor: UIColor {
        switch self {
        case .nominal:         return UIColor(red: 0x2e/255, green: 0x5f/255, blue: 0x44/255, alpha: 1)
        case .degraded:        return UIColor(red: 0xb8/255, green: 0x97/255, blue: 0x22/255, alpha: 1)
        case .heavilyDegraded: return UIColor(red: 0xa5/255, green: 0x3f/255, blue: 0x42/255, alpha: 1)
        case .impassable:      return UIColor(red: 0x10/255, green: 0x10/255, blue: 0x10/255, alpha: 1)
        case .unknown:         return UIColor(red: 0x5d/255, green: 0x57/255, blue: 0x4e/255, alpha: 1)
        }
    }

    public var color: Color { Color(uiColor) }

    /// Contrasting text color for chip readability.
    public var chipForeground: Color {
        switch self {
        case .degraded: return .black
        default: return .white
        }
    }
}
