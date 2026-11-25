import UIKit
import MapboxMaps
import BackroadStatusCore
import BackroadStatusMapKit

public extension BackroadStatusMapLayers {
    /// Mapbox-flavored convenience that wires the SDK's canonical
    /// condition palette onto a live `MapboxMap` directly. Intended for
    /// the host app's main map — call once the style has loaded so your
    /// map and the composer's focused-path overlay render roads with the
    /// same palette.
    ///
    /// Layers missing from the style are skipped silently; safe to call
    /// unconditionally.
    static func applyCanonicalConditionColors(map: MapboxMap) {
        for (layerId, condition) in BackroadStatusMapLayers.conditionLayerMapping {
            guard map.layerExists(withId: layerId) else { continue }
            try? map.setLayerProperty(
                for: layerId,
                property: "line-color",
                value: cssString(for: condition.uiColor)
            )
        }
    }

    private static func cssString(for color: UIColor) -> String {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        return String(
            format: "rgba(%d, %d, %d, %g)",
            Int((r * 255).rounded()),
            Int((g * 255).rounded()),
            Int((b * 255).rounded()),
            Double(a)
        )
    }
}
