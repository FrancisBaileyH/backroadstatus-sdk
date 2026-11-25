import MapLibre
import BackroadStatusCore
import BackroadStatusMapKit

public extension BackroadStatusMapLayers {
    /// MapLibre-flavored convenience that wires the SDK's canonical
    /// condition palette onto a live `MLNStyle` directly. Intended for
    /// the host app's main map — call from your
    /// `mapView(_:didFinishLoading:)` delegate method so your map and
    /// the composer's focused-path overlay render roads with the same
    /// palette.
    ///
    /// Layers missing from the style are skipped silently; safe to call
    /// unconditionally.
    static func applyCanonicalConditionColors(style: MLNStyle) {
        for (layerId, condition) in BackroadStatusMapLayers.conditionLayerMapping {
            guard let layer = style.layer(withIdentifier: layerId) as? MLNLineStyleLayer else { continue }
            layer.lineColor = NSExpression(forConstantValue: condition.uiColor)
        }
    }
}
