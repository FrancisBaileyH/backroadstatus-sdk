package com.backroadstatus.sdk.maplibre

import androidx.compose.ui.graphics.toArgb
import com.backroadstatus.sdk.mapkit.BackroadStatusMapLayers
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory

/**
 * MapLibre-flavored convenience that wires the SDK's canonical condition
 * palette onto a live [org.maplibre.android.maps.Style] directly. Intended
 * for the host app's main map — call from your style-loaded callback so
 * your map and the composer's focused-path overlay render roads with the
 * same palette.
 *
 * Layers missing from the style are skipped silently; safe to call
 * unconditionally.
 */
fun BackroadStatusMapLayers.applyCanonicalConditionColors(style: org.maplibre.android.maps.Style) {
    for ((layerId, condition) in BackroadStatusMapLayers.conditionLayerMapping.map { it.layerId to it.condition }) {
        val layer = style.getLayer(layerId) as? LineLayer ?: continue
        val argb = condition.color.toArgb()
        val r = ((argb ushr 16) and 0xFF)
        val g = ((argb ushr 8) and 0xFF)
        val b = (argb and 0xFF)
        val a = ((argb ushr 24) and 0xFF) / 255f
        layer.setProperties(PropertyFactory.lineColor(Expression.rgba(r, g, b, a)))
    }
}
