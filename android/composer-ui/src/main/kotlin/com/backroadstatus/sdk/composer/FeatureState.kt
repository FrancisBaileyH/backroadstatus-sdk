package com.backroadstatus.sdk.composer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * User-facing state for a discovered road feature in the composer's
 * verification checklist. The "removed" concept lives as a toggle inside
 * the Changed flow ("No longer present") rather than its own top-level
 * option — mirrors the web composer.
 *
 * Submission mapping:
 *   - [unknown]  → no FeatureUpdateInput emitted
 *   - [noChange] → verified; `removed=false`, `finalType == originalType`
 *   - [changed]  → user tweaks + optional removed via companion toggle
 */
@Serializable
enum class FeatureStatus {
    @SerialName("unknown") unknown,
    @SerialName("noChange") noChange,
    @SerialName("changed") changed;

    val displayName: String
        get() = when (this) {
            unknown -> "Unknown"
            noChange -> "No Change"
            changed -> "Changed"
        }
}

/**
 * Which `PathFeatureType` values a user can pick when transitioning the
 * given feature type with the "Changed" status. Mirrors `optionsFor(type)`
 * in feature-updates.js. Returns null for types that can only receive
 * comment updates (impasses, warnings, bypasses, hydropower stations, etc.).
 */
object FeatureTransition {
    data class TypeOption(val rawValue: String, val displayName: String)

    fun options(type: String): List<TypeOption>? = when (type) {
        "OPEN_GATE", "LOCKED_GATE", "POTENTIAL_GATE" -> listOf(
            TypeOption("OPEN_GATE", "Open"),
            TypeOption("LOCKED_GATE", "Locked"),
        )
        "BRIDGE_IN", "BRIDGE_OUT", "FOOT_BRIDGE", "BRIDGE_UNKNOWN" -> listOf(
            TypeOption("BRIDGE_IN", "Bridge In"),
            TypeOption("BRIDGE_OUT", "Bridge Out"),
            TypeOption("FOOT_BRIDGE", "Foot Bridge"),
        )
        else -> null
    }
}
