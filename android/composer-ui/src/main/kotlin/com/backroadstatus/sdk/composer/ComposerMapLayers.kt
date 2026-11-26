package com.backroadstatus.sdk.composer

import androidx.compose.ui.graphics.Color
import com.backroadstatus.sdk.core.Condition
import com.backroadstatus.sdk.core.RoadConditionPoint
import com.backroadstatus.sdk.mapkit.BackroadStatusMapLayers
import com.backroadstatus.sdk.mapkit.MapProvider
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * MapLibre-Style-Spec JSON builders + source updates for the composer's
 * inline map. Entirely vendor-neutral — every mutation flows through
 * [MapProvider], so swapping MapLibre for Mapbox is a question of which
 * adapter the host supplies, not of touching this code.
 */
internal object ComposerMapLayers {

    object LayerId {
        const val geometrySource = "mini-backroadstatus-roads-geometry-src"
        const val focusedPathLayer = "mini-backroadstatus-focused-path"
        const val focusedPathBorderLayer = "mini-backroadstatus-focused-path-border"
        const val focusedPathDashLayer = "mini-backroadstatus-focused-path-dash"
        const val focusedPathHighlight = "mini-backroadstatus-focused-path-highlight"
        const val distanceMarkerSource = "mini-backroadstatus-distance-markers-src"
        const val distanceMarkerLayer = "mini-backroadstatus-distance-markers"
    }

    /**
     * Fontstack name MapLibre adapters inject against. Mapbox hosts can
     * opt into their own font by overriding the distance-marker layer's
     * `text-font` after it's installed.
     */
    const val distanceMarkerFontstack = "Noto Sans Regular"

    /**
     * Width threshold (meters) at which the focused road rendering steps
     * up to a slightly thicker line — matches `pathLength > 15000` in
     * the web composer.
     */
    private const val widthStepUpThreshold: Double = 15000.0

    /** Default interval between distance markers in meters. */
    const val distanceInterval: Double = 500.0

    // Host style manipulation

    /**
     * ANDs `id != focusedPathId` into each condition tile layer's filter
     * so the tile-rendered version of the focused road is hidden — the
     * composer's overlay renders it instead.
     */
    fun excludeFocusedPath(provider: MapProvider, pathId: String) {
        for (layerId in BackroadStatusMapLayers.conditionLayerIds) {
            if (!provider.layerExists(layerId)) continue
            try {
                provider.addFilterCondition(
                    layer = layerId,
                    filter = listOf("!=", listOf("get", "id"), pathId),
                )
            } catch (_: Throwable) {}
        }
    }

    // Focused-path overlay

    fun installGeometryOverlay(provider: MapProvider) {
        if (provider.sourceExists(LayerId.geometrySource)) return

        try {
            provider.addSource(LayerId.geometrySource, mapOf("type" to "geojson", "data" to emptyFeatureCollection))
        } catch (_: Throwable) {}

        val widthExpr = focusedPathWidthExpression
        val mainColorExpr = conditionColorExpression(darkPalette = false)
        val darkColorExpr = conditionColorExpression(darkPalette = true)

        val belowKey: String? = when {
            provider.layerExists(BackroadStatusMapLayers.featureCircleLayerId) ->
                BackroadStatusMapLayers.featureCircleLayerId
            provider.layerExists(BackroadStatusMapLayers.featureIconLayerId) ->
                BackroadStatusMapLayers.featureIconLayerId
            else -> null
        }

        // Bottom of the group: blurred condition-tinted glow.
        tryAddLayer(
            provider,
            mapOf(
                "id" to LayerId.focusedPathHighlight,
                "type" to "line",
                "source" to LayerId.geometrySource,
                "paint" to mapOf(
                    "line-color" to mainColorExpr,
                    "line-opacity" to listOf(
                        "case",
                        listOf("==", listOf("get", "condition"), "HEAVILY_DEGRADED"), 0.4,
                        listOf("==", listOf("get", "condition"), "DEGRADED"), 0.5,
                        0.4,
                    ),
                    "line-blur" to 2,
                    "line-width" to listOf(
                        "interpolate", listOf("linear"), listOf("zoom"),
                        8, 8,
                        11, 14,
                    ),
                ),
                "layout" to mapOf(
                    "line-cap" to "round",
                    "line-join" to "round",
                ),
            ),
            below = belowKey,
        )

        // Dark border using line-gap-width. NOMINAL/DEGRADED/HEAVILY_DEGRADED only.
        tryAddLayer(
            provider,
            mapOf(
                "id" to LayerId.focusedPathBorderLayer,
                "type" to "line",
                "source" to LayerId.geometrySource,
                "filter" to listOf(
                    "in",
                    listOf("get", "condition"),
                    listOf("literal", listOf("NOMINAL", "DEGRADED", "HEAVILY_DEGRADED")),
                ),
                "minzoom" to 11,
                "paint" to mapOf(
                    "line-color" to darkColorExpr,
                    "line-gap-width" to widthExpr,
                    "line-width" to 0.5,
                ),
                "layout" to mapOf(
                    "line-cap" to "round",
                    "line-join" to "round",
                ),
            ),
            below = belowKey,
        )

        // Solid colored line for navigable conditions.
        tryAddLayer(
            provider,
            mapOf(
                "id" to LayerId.focusedPathLayer,
                "type" to "line",
                "source" to LayerId.geometrySource,
                "filter" to listOf(
                    "in",
                    listOf("get", "condition"),
                    listOf("literal", listOf("NOMINAL", "DEGRADED", "HEAVILY_DEGRADED")),
                ),
                "paint" to mapOf(
                    "line-color" to mainColorExpr,
                    "line-width" to widthExpr,
                ),
                "layout" to mapOf(
                    "line-cap" to "round",
                    "line-join" to "round",
                ),
            ),
            below = belowKey,
        )

        // Dashed line for IMPASSABLE / UNKNOWN stretches.
        tryAddLayer(
            provider,
            mapOf(
                "id" to LayerId.focusedPathDashLayer,
                "type" to "line",
                "source" to LayerId.geometrySource,
                "filter" to listOf(
                    "in",
                    listOf("get", "condition"),
                    listOf("literal", listOf("IMPASSABLE", "UNKNOWN")),
                ),
                "paint" to mapOf(
                    "line-color" to mainColorExpr,
                    "line-width" to widthExpr,
                    "line-dasharray" to listOf(2, 1),
                ),
                "layout" to mapOf(
                    "line-cap" to "butt",
                    "line-join" to "round",
                ),
            ),
            below = belowKey,
        )
    }

    fun updateGeometryOverlay(
        provider: MapProvider,
        coords: List<List<Double>>,
        conditions: List<RoadConditionPoint>,
    ) {
        if (coords.size < 2) {
            try { provider.setSourceData(LayerId.geometrySource, emptyFeatureCollection) } catch (_: Throwable) {}
            return
        }

        val totalLen = totalLength(coords)
        val segments = conditionSegments(coords, conditions)

        val features: List<Map<String, Any?>> = segments
            .filter { it.coords.size >= 2 }
            .map { segment ->
                mapOf(
                    "type" to "Feature",
                    "properties" to mapOf(
                        "condition" to segment.condition,
                        "pathLength" to totalLen,
                    ),
                    "geometry" to mapOf(
                        "type" to "LineString",
                        "coordinates" to segment.coords,
                    ),
                )
            }

        try {
            provider.setSourceData(
                LayerId.geometrySource,
                mapOf("type" to "FeatureCollection", "features" to features),
            )
        } catch (_: Throwable) {}
    }

    // Distance markers

    fun installDistanceMarkerLayer(provider: MapProvider) {
        if (provider.sourceExists(LayerId.distanceMarkerSource)) return
        try {
            provider.addSource(
                LayerId.distanceMarkerSource,
                mapOf("type" to "geojson", "data" to emptyFeatureCollection),
            )
        } catch (_: Throwable) {}

        tryAddLayer(
            provider,
            mapOf(
                "id" to LayerId.distanceMarkerLayer,
                "type" to "symbol",
                "source" to LayerId.distanceMarkerSource,
                "layout" to mapOf(
                    "symbol-placement" to "point",
                    "text-field" to listOf("get", "label"),
                    "text-font" to listOf(distanceMarkerFontstack),
                    "text-size" to 12,
                    "text-anchor" to "right",
                    "text-offset" to listOf(0, 1.5),
                    "text-justify" to "center",
                    "icon-allow-overlap" to true,
                    "icon-ignore-placement" to true,
                ),
                "paint" to mapOf(
                    "text-color" to Color(0xFF6F6F6F),
                    "text-halo-color" to Color(0xC5FBFBFB),
                    "text-halo-width" to 1.5,
                ),
            ),
            below = null,
        )
    }

    fun updateDistanceMarkers(
        provider: MapProvider,
        coords: List<List<Double>>,
        interval: Double = distanceInterval,
    ) {
        if (coords.size < 2) {
            try { provider.setSourceData(LayerId.distanceMarkerSource, emptyFeatureCollection) } catch (_: Throwable) {}
            return
        }

        val cumulative = DoubleArray(coords.size)
        for (i in 1 until coords.size) {
            cumulative[i] = cumulative[i - 1] + haversine(coords[i - 1], coords[i])
        }
        val total = cumulative.last()

        val features = ArrayList<Map<String, Any?>>()
        var target = interval
        var cursor = 0
        while (target < total) {
            while (cursor < cumulative.size - 1 && cumulative[cursor] < target) cursor++
            val coord = coords[cursor]
            val km = target / 1000.0
            val label = if (kotlin.math.round(km) == km) "${km.toInt()} km" else String.format(Locale.US, "%.1f km", km)
            features.add(
                mapOf(
                    "type" to "Feature",
                    "properties" to mapOf("label" to label),
                    "geometry" to mapOf("type" to "Point", "coordinates" to coord),
                ),
            )
            target += interval
        }

        try {
            provider.setSourceData(
                LayerId.distanceMarkerSource,
                mapOf("type" to "FeatureCollection", "features" to features),
            )
        } catch (_: Throwable) {}
    }

    // Shared expressions

    private val focusedPathWidthExpression: List<Any?>
        get() {
            val longCase = listOf(">", listOf("get", "pathLength"), widthStepUpThreshold)
            return listOf(
                "interpolate", listOf("linear"), listOf("zoom"),
                8, listOf("case", longCase, 1.75, 1.25),
                11, listOf("case", longCase, 3.25, 2.25),
            )
        }

    private fun conditionColorExpression(darkPalette: Boolean): List<Any?> {
        fun color(c: Condition): Color = if (darkPalette) c.darkColor else c.color
        return listOf(
            "match",
            listOf("get", "condition"),
            "NOMINAL", color(Condition.NOMINAL),
            "DEGRADED", color(Condition.DEGRADED),
            "HEAVILY_DEGRADED", color(Condition.HEAVILY_DEGRADED),
            "IMPASSABLE", color(Condition.IMPASSABLE),
            color(Condition.UNKNOWN),
        )
    }

    // Segment construction

    private data class ConditionSegment(val coords: List<List<Double>>, val condition: String)

    private fun conditionSegments(
        coords: List<List<Double>>,
        conditions: List<RoadConditionPoint>,
    ): List<ConditionSegment> {
        if (conditions.isEmpty()) {
            return listOf(ConditionSegment(coords = coords, condition = "UNKNOWN"))
        }
        val segments = ArrayList<ConditionSegment>()
        var startIdx = 0
        for (condition in conditions) {
            val endIdx = coords.indexOfFirst { it == condition.point }
            if (endIdx < 0 || endIdx < startIdx) continue
            val slice = coords.subList(startIdx, endIdx + 1)
            if (slice.size >= 2) segments.add(ConditionSegment(slice, condition.condition))
            startIdx = endIdx
        }
        if (startIdx < coords.size - 1 && conditions.isNotEmpty()) {
            val tail = coords.subList(startIdx, coords.size)
            if (tail.size >= 2) segments.add(ConditionSegment(tail, conditions.last().condition))
        }
        return segments
    }

    private fun totalLength(coords: List<List<Double>>): Double {
        if (coords.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until coords.size) {
            total += haversine(coords[i - 1], coords[i])
        }
        return total
    }

    internal fun haversine(a: List<Double>, b: List<Double>): Double {
        val r = 6_371_000.0
        val lon1 = a[0] * PI / 180
        val lat1 = a[1] * PI / 180
        val lon2 = b[0] * PI / 180
        val lat2 = b[1] * PI / 180
        val dLat = lat2 - lat1
        val dLon = lon2 - lon1
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }

    private val emptyFeatureCollection: Map<String, Any?> =
        mapOf("type" to "FeatureCollection", "features" to emptyList<Map<String, Any?>>())

    private fun tryAddLayer(provider: MapProvider, spec: Map<String, Any?>, below: String?) {
        try {
            provider.addLayer(spec, below = below)
        } catch (_: Throwable) {}
    }
}
