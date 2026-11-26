package com.backroadstatus.sdk.mapkit

import android.graphics.Bitmap
import android.graphics.PointF
import kotlinx.serialization.Serializable

/**
 * `[lon, lat]`-in-spirit coordinate. Pure Kotlin so Core/MapKit types don't
 * depend on any map SDK; adapters convert to their native coord type at the
 * boundary (e.g. `MapLibreMapProvider` wraps this into MapLibre's `LatLng`
 * before calling MapLibre).
 */
@Serializable
data class Coordinate(
    val longitude: Double,
    val latitude: Double,
) {
    val lonLat: List<Double> get() = listOf(longitude, latitude)

    companion object {
        /** Mirrors `[lon, lat]` arrays used throughout the SDK's server-facing types. */
        fun fromLonLat(lonLat: List<Double>): Coordinate {
            require(lonLat.size >= 2) { "lonLat requires two components" }
            return Coordinate(longitude = lonLat[0], latitude = lonLat[1])
        }
    }
}

/** Axis-aligned geographic bounding box. South-west + north-east corners. */
data class BoundingBox(
    val sw: Coordinate,
    val ne: Coordinate,
) {
    companion object {
        /**
         * Smallest bounding box that contains every provided coordinate, or
         * `null` if the input is empty.
         */
        fun fitting(coordinates: List<Coordinate>): BoundingBox? {
            val first = coordinates.firstOrNull() ?: return null
            var minLat = first.latitude
            var maxLat = first.latitude
            var minLon = first.longitude
            var maxLon = first.longitude
            for (c in coordinates.drop(1)) {
                if (c.latitude < minLat) minLat = c.latitude
                if (c.latitude > maxLat) maxLat = c.latitude
                if (c.longitude < minLon) minLon = c.longitude
                if (c.longitude > maxLon) maxLon = c.longitude
            }
            return BoundingBox(
                sw = Coordinate(longitude = minLon, latitude = minLat),
                ne = Coordinate(longitude = maxLon, latitude = maxLat),
            )
        }
    }
}

/**
 * Padding around the map viewport. Lives in MapKit so the [MapProvider]
 * interface has no Android-view dependency at the signature level.
 */
data class MapEdgeInsets(
    val top: Double,
    val left: Double,
    val bottom: Double,
    val right: Double,
) {
    companion object {
        val Zero = MapEdgeInsets(0.0, 0.0, 0.0, 0.0)
        fun all(v: Double) = MapEdgeInsets(v, v, v, v)
    }
}

/**
 * A pre-rendered marker to place on the map. Image-based so one annotation
 * representation works across SDKs; adapters host these via a hidden symbol
 * layer rather than per-annotation Views.
 */
data class Annotation(
    val id: String,
    val coordinate: Coordinate,
    /**
     * Pre-rasterized marker bitmap. Composer code renders Compose / native
     * marker views to a Bitmap once per visual variant and reuses the
     * result across position changes.
     */
    val image: Bitmap,
    /**
     * Anchor within the image: (0,0) = top-left, (0.5,1) = bottom-center.
     * Used by adapters to offset the image so the right pixel sits on
     * [coordinate].
     */
    val anchor: PointF = PointF(0.5f, 0.5f),
    /**
     * Higher values draw on top of lower values. Adapters may map this to
     * symbol-sort-key or layer stacking depending on their SDK.
     */
    val zPriority: Int = 0,
)
