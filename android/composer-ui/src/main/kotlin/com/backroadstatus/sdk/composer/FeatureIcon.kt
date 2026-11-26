package com.backroadstatus.sdk.composer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Maps a server `PathFeatureType` enum name to the bundled icon asset.
 * Mirrors the web `/icons/{type}.svg` lookup so discovered features (from
 * the API) and user-created features (from the composer) render with the
 * exact same visuals users see on the map's symbol layers and on
 * backroadstatus.com.
 *
 * The SVGs ship as raw assets under `assets/feature-icons/<TYPE>.svg` —
 * the composer's [MarkerRasterizer] renders them via AndroidSVG, and the
 * `FeatureSvgImage` Composable wraps them for inline use in Compose. This
 * keeps the icons byte-identical to what iOS ships under its xcasset
 * catalog.
 */
object FeatureIcon {
    /** Asset path for the given [typeName], or null for unknown types. */
    fun assetPath(typeName: String): String? =
        if (typeName in bundled) "feature-icons/$typeName.svg" else null

    /** Neutral fallback for unknown / future feature types. */
    val fallbackVector: ImageVector get() = Icons.Filled.Place

    internal val bundled: Set<String> = setOf(
        "WARNING",
        "OPEN_GATE", "LOCKED_GATE", "POTENTIAL_GATE",
        "MANAGED_OPEN_GATE", "MANAGED_LOCKED_GATE",
        "BRIDGE_IN", "BRIDGE_OUT", "BRIDGE_UNKNOWN", "FOOT_BRIDGE",
        "IMPASSE", "BYPASS", "HYDROPOWER_STATION",
    )
}
