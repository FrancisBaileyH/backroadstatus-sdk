package com.backroadstatus.sdk.core

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors the server's PathCondition enum (NOMINAL/DEGRADED/HEAVILY_DEGRADED/
 * IMPASSABLE/UNKNOWN) with display metadata. UNKNOWN is intentionally
 * not user-pickable — it's auto-prepended at submit time when the user
 * started past 0 m (the "unverified segment before where I began driving").
 */
@Serializable
enum class Condition(val wire: String) {
    @SerialName("NOMINAL") NOMINAL("NOMINAL"),
    @SerialName("DEGRADED") DEGRADED("DEGRADED"),
    @SerialName("HEAVILY_DEGRADED") HEAVILY_DEGRADED("HEAVILY_DEGRADED"),
    @SerialName("IMPASSABLE") IMPASSABLE("IMPASSABLE"),
    @SerialName("UNKNOWN") UNKNOWN("UNKNOWN");

    val displayName: String
        get() = when (this) {
            NOMINAL -> "Good Condition"
            DEGRADED -> "Somewhat Degraded"
            HEAVILY_DEGRADED -> "Heavily Degraded"
            IMPASSABLE -> "Impassable"
            UNKNOWN -> "Unknown"
        }

    /** Mirrors the server-side PathCondition description. */
    val description: String
        get() = when (this) {
            NOMINAL -> "Road is 2WD driveable in most cases or easily driven in a 4WD."
            DEGRADED -> "Road has rough spots, water bars or moderate washouts and may require high-clearance 4WD."
            HEAVILY_DEGRADED -> "Road requires high clearance 4WD. There may be challenging washouts, dangerous sections or exceptionally deep water bars."
            IMPASSABLE -> "Road is impassable or requires highly specialized vehicles to drive."
            UNKNOWN -> "Road condition is unknown."
        }

    /**
     * Palette mirrors PathConditionColorScheme in MapScheme.js so Android matches
     * the web across slider tracks, chips, map markers, and the composer's
     * focused-road overlay.
     */
    val color: Color
        get() = when (this) {
            NOMINAL -> Color(0xFF438A62)
            DEGRADED -> Color(0xFFEDC438)
            HEAVILY_DEGRADED -> Color(0xFFD56265)
            IMPASSABLE -> Color(0xFF252525)
            UNKNOWN -> Color(0xFF827B70)
        }

    /** Darkened palette for the composer mini-map's focused-road border. */
    val darkColor: Color
        get() = when (this) {
            NOMINAL -> Color(0xFF2E5F44)
            DEGRADED -> Color(0xFFB89722)
            HEAVILY_DEGRADED -> Color(0xFFA53F42)
            IMPASSABLE -> Color(0xFF101010)
            UNKNOWN -> Color(0xFF5D574E)
        }

    /** Contrasting text color for chip readability. */
    val chipForeground: Color
        get() = if (this == DEGRADED) Color.Black else Color.White

    companion object {
        /** Conditions the user can pick from the composer dropdown. */
        val userPickable: List<Condition> = listOf(NOMINAL, DEGRADED, HEAVILY_DEGRADED, IMPASSABLE, UNKNOWN)

        fun fromWire(raw: String?): Condition? = raw?.let { value -> entries.firstOrNull { it.wire == value } }
    }
}
