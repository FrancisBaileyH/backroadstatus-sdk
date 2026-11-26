package com.backroadstatus.sdk.composer

import com.backroadstatus.sdk.mapkit.MapProvider

/**
 * Everything the adapter view needs to stand up its native map view and
 * hand the composer a drivable [MapProvider]. Passed by
 * `BackroadStatusReportScreen` into the host-supplied `mapBuilder`.
 *
 * The adapter's job is minimal: create the native map view, wrap it in a
 * [MapProvider], and call [onReady] as soon as the view is instantiated.
 * The composer takes it from there — it registers its own `onStyleLoaded`
 * handler on the provider, drives overlay data, etc.
 */
data class ComposerMapBinding(
    /**
     * Style URL the adapter should load. Any glyph-preparation /
     * per-SDK massaging is the adapter's responsibility.
     */
    val styleUrl: String,
    /**
     * Called exactly once by the adapter after it instantiates its native
     * map view and wraps it in a [MapProvider]. Fires before the style
     * loads so the composer can subscribe to `onStyleLoaded` in time.
     */
    val onReady: (MapProvider) -> Unit,
)
