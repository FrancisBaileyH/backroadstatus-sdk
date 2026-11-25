import Foundation
import BackroadStatusCore

/// Style identifiers the SDK expects to find in your map style, plus a
/// vendor-neutral helper to override the condition tile layers with the
/// SDK's canonical palette.
///
/// The composer's inline map and your main map should both render roads
/// with the same colors; this helper is how the SDK stays authoritative on
/// that palette regardless of what colors your style ships with.
///
/// The expected layer/source IDs match what the BackroadStatus tile server
/// emits, so the simplest integration is to copy the server's reference
/// style and layer it under your own base map.
public enum BackroadStatusMapLayers {
    /// Line-layer IDs → PathCondition they render. Single source of truth
    /// for every condition-color helper (vendor-neutral and SDK-specific
    /// overloads in the adapters both iterate this). `roads-fallback`
    /// covers roads without a known condition.
    public static let conditionLayerMapping: [(layerId: String, condition: Condition)] = [
        ("roads-NOMINAL",          .nominal),
        ("roads-DEGRADED",         .degraded),
        ("roads-HEAVILY_DEGRADED", .heavilyDegraded),
        ("roads-IMPASSABLE",       .impassable),
        ("roads-fallback",         .unknown)
    ]

    /// Just the layer IDs from `conditionLayerMapping`, for call-sites that
    /// only need to iterate layers (not pair them with a condition).
    public static let conditionLayerIds: [String] = conditionLayerMapping.map(\.layerId)

    /// Source ID that serves the `roads` vector-tile layer.
    public static let roadsSourceId = "backroadstatus-roads"

    /// Source-layer name inside `roadsSourceId` containing road geometry.
    public static let roadsSourceLayer = "roads"

    /// Layer IDs backed by the `road-features` source-layer (gates, bridges,
    /// washouts, warnings, …). The composer queries both to discover
    /// features on the focused road.
    public static let featureCircleLayerId = "road-features-circles"
    public static let featureIconLayerId = "road-features-icons"

    /// Overrides each condition tile layer's `line-color` with the SDK's
    /// canonical per-condition palette. Call from your map's style-did-
    /// finish-loading hook so the host map and the composer render roads
    /// with the same colors — otherwise whatever palette your style ships
    /// with wins on the host map while the composer tints to the SDK
    /// palette, and the two look out of sync.
    ///
    /// Safe to call unconditionally: layers missing from the style are
    /// skipped.
    @MainActor
    public static func applyCanonicalConditionColors(provider: MapProvider) {
        for (layerId, condition) in conditionLayerMapping where provider.layerExists(id: layerId) {
            try? provider.setLayerProperty(
                layer: layerId,
                property: "line-color",
                value: condition.uiColor
            )
        }
    }
}
