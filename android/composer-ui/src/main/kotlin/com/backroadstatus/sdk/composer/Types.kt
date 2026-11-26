package com.backroadstatus.sdk.composer

import com.backroadstatus.sdk.core.Condition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One condition the user picked on the composer slider. Step index is a
 * cursor into [RoadGeometry.coordinates]; mirrors `condition-slider.js`.
 */
@Serializable
data class ConditionEntry(
    val id: String = UUID.randomUUID().toString(),
    val condition: Condition,
    val step: Float,
)

/**
 * Feature types the user is allowed to create. Matches the set exposed by
 * feature-add-modal.html. The "managed" PathFeatureType variants are
 * deliberately omitted — those are owned by scheduled jobs and should not
 * be created by hand.
 */
@Serializable
enum class CreatableFeatureType(val wire: String) {
    @SerialName("OPEN_GATE") OPEN_GATE("OPEN_GATE"),
    @SerialName("LOCKED_GATE") LOCKED_GATE("LOCKED_GATE"),
    @SerialName("BRIDGE_IN") BRIDGE_IN("BRIDGE_IN"),
    @SerialName("BRIDGE_OUT") BRIDGE_OUT("BRIDGE_OUT"),
    @SerialName("IMPASSE") IMPASSE("IMPASSE"),
    @SerialName("BYPASS") BYPASS("BYPASS"),
    @SerialName("WARNING") WARNING("WARNING"),
    @SerialName("FOOT_BRIDGE") FOOT_BRIDGE("FOOT_BRIDGE");

    val displayName: String
        get() = when (this) {
            OPEN_GATE -> "Open Gate"
            LOCKED_GATE -> "Locked Gate"
            BRIDGE_IN -> "Bridge In"
            BRIDGE_OUT -> "Bridge Out"
            IMPASSE -> "Impasse"
            BYPASS -> "HC 4x4 Bypass"
            WARNING -> "Warning"
            FOOT_BRIDGE -> "Foot Bridge"
        }
}

/**
 * A feature the user placed on the road in this composer session. Not yet
 * submitted — survives until the composer submits (becomes a
 * FeatureUpdateInput) or the user removes it.
 */
@Serializable
data class CreatedFeature(
    val id: String = UUID.randomUUID().toString(),
    val type: CreatableFeatureType,
    /** `[lon, lat]`. Always snapped to a vertex in the road's coordinate array. */
    val position: List<Double>,
    val comment: String? = null,
)

/**
 * Transient composer-side state for a draft photo. Lives in the
 * `ReportComposerView` state; serialized out to [DraftPhotoMeta] for disk.
 */
data class DraftPhotoState(
    val id: String = UUID.randomUUID().toString(),
    val data: ByteArray,
    val caption: String,
    val reference: String? = null,
    val uploadError: String? = null,
    /**
     * True while an upload Job is live. Explicit rather than derived from
     * (reference == null && uploadError == null) because an offline-captured
     * photo sits in that "neither succeeded nor failed" state without an
     * upload actually being in flight — the submission queue picks it up
     * on Submit.
     */
    val isUploading: Boolean = false,
) {
    /**
     * Photo has no server reference yet AND we aren't actively uploading —
     * it's waiting for the submission queue (or a retry of a failed eager
     * upload) to carry it the rest of the way.
     */
    val isPendingUpload: Boolean
        get() = reference == null && uploadError == null && !isUploading
}

/**
 * Non-view companion holding the composer's tunable constants.
 */
object Composer {
    const val submitErrorAnchorId = "submit-error-card"
    const val maxConditions = 5
    const val snapStep: Float = 1f
    const val marginSteps: Float = 2f
    const val jumpFraction: Float = 0.10f
    const val maxPhotos = 8
    const val maxVehicles = 3
    val captionRange = 8..200
}
