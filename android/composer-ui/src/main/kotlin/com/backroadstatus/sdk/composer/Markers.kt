package com.backroadstatus.sdk.composer

import androidx.compose.ui.graphics.Color

/**
 * One slider-handle position + color. The composer builds these each time
 * the slider changes; the driver rasterizes them to [com.backroadstatus.sdk.mapkit.Annotation]s
 * with pre-rendered dot bitmaps and hands them to the active `MapProvider`.
 */
data class SliderMarker(
    /** Stable identity across diffs so the driver can update position/color
     *  in place instead of churning annotations. */
    val id: String,
    /** `[lon, lat]`. */
    val position: List<Double>,
    val color: Color,
)

/**
 * Value-type description of a user-created road feature pin (gate / bridge
 * / warning / …). Rendered on both the composer's mini-map and the Add
 * Feature picker map.
 */
data class PlacedFeatureMarker(
    /** Stable identity across Compose diffs. */
    val id: String,
    /** Server `PathFeatureType` enum name ("OPEN_GATE", "WARNING", …). */
    val typeName: String,
    /** `[lon, lat]` matching a coord in the road's geometry. */
    val position: List<Double>,
)
