package com.backroadstatus.sdk.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Slippy-map tile math (XYZ / Web Mercator). Shared between the bulk
 * geometry downloader (enumerating tiles inside an offline region's bbox)
 * and the per-road cache lookup (figuring out which tile a tapped road's
 * bbox falls in).
 */
object TileMath {

    data class TileCoord(val z: Int, val x: Int, val y: Int)

    private const val MERCATOR_LAT_LIMIT = 85.05112878

    /**
     * Returns every tile at [zoom] whose geographic extent intersects
     * [bounds]. Clamps coords inside Web Mercator's ±85.0511° latitude
     * range so the polar singularity doesn't produce NaN tile indices.
     */
    fun tiles(bounds: GeographicBounds, zoom: Int): List<TileCoord> {
        val n = 1 shl zoom
        val minX = tileX(bounds.westLongitude, zoom)
        val maxX = tileX(bounds.eastLongitude, zoom)
        // Latitude axis inverts under the slippy-map Y convention: north → smaller Y.
        val minY = tileY(bounds.northLatitude, zoom)
        val maxY = tileY(bounds.southLatitude, zoom)

        val result = ArrayList<TileCoord>()
        for (x in max(0, minX)..min(n - 1, maxX)) {
            for (y in max(0, minY)..min(n - 1, maxY)) {
                result.add(TileCoord(zoom, x, y))
            }
        }
        return result
    }

    fun tileX(longitude: Double, zoom: Int): Int {
        val n = (1 shl zoom).toDouble()
        return floor((longitude + 180.0) / 360.0 * n).toInt()
    }

    fun tileY(latitude: Double, zoom: Int): Int {
        val clamped = max(-MERCATOR_LAT_LIMIT, min(MERCATOR_LAT_LIMIT, latitude))
        val rad = clamped * PI / 180.0
        val n = (1 shl zoom).toDouble()
        return floor((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n).toInt()
    }
}
