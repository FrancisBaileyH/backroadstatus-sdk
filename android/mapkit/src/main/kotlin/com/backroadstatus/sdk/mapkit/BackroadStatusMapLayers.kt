package com.backroadstatus.sdk.mapkit

import com.backroadstatus.sdk.core.Condition

/**
 * Style identifiers the SDK expects to find in your map style, plus a
 * vendor-neutral helper to override the condition tile layers with the
 * SDK's canonical palette.
 *
 * The composer's inline map and your main map should both render roads
 * with the same colors; this helper is how the SDK stays authoritative on
 * that palette regardless of what colors your style ships with.
 *
 * The expected layer / source IDs match what the BackroadStatus tile server
 * emits, so the simplest integration is to copy the server's reference
 * style and layer it under your own base map.
 */
object BackroadStatusMapLayers {
    data class ConditionLayer(val layerId: String, val condition: Condition)

    /**
     * Line-layer IDs → PathCondition they render. Single source of truth
     * for every condition-color helper. `roads-fallback` covers roads
     * without a known condition.
     */
    val conditionLayerMapping: List<ConditionLayer> = listOf(
        ConditionLayer("roads-NOMINAL", Condition.NOMINAL),
        ConditionLayer("roads-DEGRADED", Condition.DEGRADED),
        ConditionLayer("roads-HEAVILY_DEGRADED", Condition.HEAVILY_DEGRADED),
        ConditionLayer("roads-IMPASSABLE", Condition.IMPASSABLE),
        ConditionLayer("roads-fallback", Condition.UNKNOWN),
    )

    /** Just the layer IDs from [conditionLayerMapping]. */
    val conditionLayerIds: List<String> = conditionLayerMapping.map { it.layerId }

    /** Source ID that serves the `roads` vector-tile layer. */
    const val roadsSourceId: String = "backroadstatus-roads"

    /** Source-layer name inside [roadsSourceId] containing road geometry. */
    const val roadsSourceLayer: String = "roads"

    /**
     * Layer IDs backed by the `road-features` source-layer (gates, bridges,
     * washouts, warnings, …). The composer queries both to discover
     * features on the focused road.
     */
    const val featureCircleLayerId: String = "road-features-circles"
    const val featureIconLayerId: String = "road-features-icons"

    /**
     * Overrides each condition tile layer's `line-color` with the SDK's
     * canonical per-condition palette. Call from your map's style-did-
     * finish-loading hook so the host map and the composer render roads
     * with the same colors — otherwise whatever palette your style ships
     * with wins on the host map while the composer tints to the SDK
     * palette, and the two look out of sync.
     *
     * Safe to call unconditionally: layers missing from the style are
     * skipped.
     */
    fun applyCanonicalConditionColors(provider: MapProvider) {
        for ((layerId, condition) in conditionLayerMapping) {
            if (!provider.layerExists(layerId)) continue
            try {
                provider.setLayerProperty(layerId, "line-color", condition.color)
            } catch (_: Throwable) {
                // Style shape drift — adapter rejected the property; keep going.
            }
        }
    }
}
