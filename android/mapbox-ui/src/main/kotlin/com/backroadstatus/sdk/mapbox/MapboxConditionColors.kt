package com.backroadstatus.sdk.mapbox

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.backroadstatus.sdk.mapkit.BackroadStatusMapLayers
import com.mapbox.bindgen.Value
import com.mapbox.maps.MapboxStyleManager

/**
 * Mapbox-flavored convenience that wires the SDK's canonical condition
 * palette onto a live Mapbox [MapboxStyleManager] directly. Intended for
 * the host app's main map — call once the style has loaded so your map
 * and the composer's focused-path overlay render roads with the same
 * palette.
 *
 * Layers missing from the style are skipped silently; safe to call
 * unconditionally.
 */
fun BackroadStatusMapLayers.applyCanonicalConditionColors(style: MapboxStyleManager) {
    for ((layerId, condition) in BackroadStatusMapLayers.conditionLayerMapping.map { it.layerId to it.condition }) {
        if (!style.styleLayerExists(layerId)) continue
        val css = cssString(condition.color)
        style.setStyleLayerProperty(layerId, "line-color", Value.valueOf(css))
    }
}

private fun cssString(color: Color): String {
    val r = (color.red * 255).toInt()
    val g = (color.green * 255).toInt()
    val b = (color.blue * 255).toInt()
    val a = color.alpha
    return "rgba($r, $g, $b, $a)"
}
