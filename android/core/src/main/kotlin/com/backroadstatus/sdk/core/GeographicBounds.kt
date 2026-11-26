package com.backroadstatus.sdk.core

import kotlinx.serialization.Serializable

/**
 * South-west + north-east corners, in lon/lat degrees. Mirrors
 * `BackroadStatusMapKit.BoundingBox` but lives in Core so the bulk
 * geometry downloader doesn't drag the MapKit module along.
 *
 * Hosts bridging from a map SDK (e.g. MapLibre's `LatLngBounds`) build this
 * at the call site — the SDK itself never sees the native type.
 */
@Serializable
data class GeographicBounds(
    val southLatitude: Double,
    val westLongitude: Double,
    val northLatitude: Double,
    val eastLongitude: Double,
)
