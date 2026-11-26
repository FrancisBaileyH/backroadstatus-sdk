package com.backroadstatus.sdk.composer

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ordered coordinate array for a road plus precomputed cumulative haversine
 * distances (meters). Used by the composer slider for step-index →
 * distance label conversion.
 */
data class RoadGeometry(
    val coordinates: List<List<Double>>,
    val cumulativeDistances: List<Double>,
) {
    val totalDistance: Double get() = cumulativeDistances.lastOrNull() ?: 0.0

    fun coordAtMeters(meters: Double): List<Double>? {
        if (coordinates.isEmpty()) return null
        val clamped = meters.coerceIn(0.0, totalDistance)
        var best = 0
        var bestDelta = kotlin.math.abs(cumulativeDistances[0] - clamped)
        for (i in 1 until cumulativeDistances.size) {
            val d = kotlin.math.abs(cumulativeDistances[i] - clamped)
            if (d < bestDelta) {
                best = i
                bestDelta = d
            }
        }
        return coordinates[best]
    }

    companion object {
        fun from(coordinates: List<List<Double>>): RoadGeometry {
            if (coordinates.size < 2) {
                return RoadGeometry(
                    coordinates = coordinates,
                    cumulativeDistances = if (coordinates.isEmpty()) emptyList() else listOf(0.0),
                )
            }
            val distances = ArrayList<Double>(coordinates.size)
            distances.add(0.0)
            var total = 0.0
            for (i in 1 until coordinates.size) {
                total += haversine(coordinates[i - 1], coordinates[i])
                distances.add(total)
            }
            return RoadGeometry(coordinates, distances)
        }

        private fun haversine(a: List<Double>, b: List<Double>): Double {
            val r = 6_371_000.0
            val lon1 = a[0] * PI / 180.0
            val lat1 = a[1] * PI / 180.0
            val lon2 = b[0] * PI / 180.0
            val lat2 = b[1] * PI / 180.0
            val dLat = lat2 - lat1
            val dLon = lon2 - lon1
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * asin(min(1.0, sqrt(h)))
        }
    }
}
