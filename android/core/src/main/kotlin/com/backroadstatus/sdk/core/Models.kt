package com.backroadstatus.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Canonical road geometry + associated features + per-segment conditions. */
@Serializable
data class RoadDetail(
    val id: String,
    val name: String,
    /**
     * `[lon, lat]` pairs along the road from start to end. For
     * MultiLineString responses, condition segments are concatenated with
     * seam-dedupe so this stays a single ordered path.
     */
    val coordinates: List<List<Double>>,
    /** Road features (gates, bridges, washouts, etc.) attached to this road. */
    val features: List<RoadFeatureDetail> = emptyList(),
    /**
     * Current condition points along the road in order. Each entry's `point`
     * is a coord in [coordinates]; the condition applies to the segment
     * leading into that point.
     */
    val conditions: List<RoadConditionPoint> = emptyList(),
)

@Serializable
data class RoadConditionPoint(
    /** `[lon, lat]`. Matches a coord in the owning [RoadDetail.coordinates]. */
    val point: List<Double>,
    /**
     * Server's `PathCondition` enum name ("NOMINAL", "DEGRADED", …). Stored
     * as a plain `String` so Core doesn't drag the UI's [Condition] enum in;
     * callers map via [Condition.fromWire] when needed.
     */
    val condition: String,
)

@Serializable
data class RoadFeatureDetail(
    val id: String,
    /** Server's `PathFeatureType` enum name ("GATE", "BRIDGE", "WASHOUT", …). */
    val type: String,
    /** Human-readable name ("Gate", "Bridge"). */
    val displayName: String,
    val description: String? = null,
    /** `[lon, lat]`. */
    val position: List<Double>,
)

@Serializable
data class DraftPhoto(
    val reference: String,
    /** Optional `[lon, lat]` read from the image's EXIF data if present. */
    val location: List<Double>? = null,
)

@Serializable
data class Bulletin(
    val id: String,
    val pathId: String,
    val pathName: String,
    val comment: String,
    val bulletinDate: Long,
    val lastModifiedDate: Long,
    val createdDate: Long,
    val ownerId: String,
    val ownerName: String,
    val type: String,
    val replyCount: Long,
    val distanceMeters: Double? = null,
    val conditionRating: String? = null,
    val photos: List<BulletinPhoto> = emptyList(),
    val vehicles: List<BulletinVehicle> = emptyList(),
)

@Serializable
data class BulletinPhoto(
    val reference: String,
    val thumbnailReference: String,
    val caption: String,
    val location: List<Double>? = null,
)

@Serializable
data class BulletinVehicle(
    val category: String,
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class PhotoInput(
    val reference: String,
    val caption: String,
)

@Serializable
data class ConditionRatingInput(
    val point: List<Double>,
    val condition: String,
)

@Serializable
data class VehicleInput(
    val category: String,
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class FeatureUpdateInput(
    val featureId: String? = null,
    val finalType: String,
    val comment: String? = null,
    val removed: Boolean = false,
    val created: Boolean = false,
    val position: List<Double>? = null,
)

/**
 * The request body for `POST /bulletins`. `photos` is `var`-ish — the
 * submission queue fills it in after uploading pending photo bytes, so a
 * submission that was offline on Submit reaches `createBulletin` with an
 * initially-empty photos list and gets it populated once references land.
 */
@Serializable
data class CreateBulletinInput(
    val pathId: String,
    /** `yyyy-MM-dd` in the device's local timezone. */
    val reportDate: String,
    val description: String,
    var photos: List<PhotoInput> = emptyList(),
    val conditionRatings: List<ConditionRatingInput> = emptyList(),
    val vehicles: List<VehicleInput> = emptyList(),
    val featureUpdates: List<FeatureUpdateInput> = emptyList(),
    /**
     * Idempotency key — the server dedupes `(ownerId, clientSubmissionId)`
     * so retries from an offline queue never create duplicate bulletins.
     */
    val clientSubmissionId: String = UUID.randomUUID().toString(),
) {
    companion object {
        fun formatReportDate(date: Date = Date()): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            // Use the device's local time zone so that a date picked as
            // "today" formats to the same calendar day the user sees.
            fmt.timeZone = TimeZone.getDefault()
            return fmt.format(date)
        }

        /** Convenience factory that formats `reportDate` from a [Date]. */
        operator fun invoke(
            pathId: String,
            reportDate: Date = Date(),
            description: String,
            photos: List<PhotoInput> = emptyList(),
            conditionRatings: List<ConditionRatingInput> = emptyList(),
            vehicles: List<VehicleInput> = emptyList(),
            featureUpdates: List<FeatureUpdateInput> = emptyList(),
            clientSubmissionId: String = UUID.randomUUID().toString(),
        ): CreateBulletinInput = CreateBulletinInput(
            pathId = pathId,
            reportDate = formatReportDate(reportDate),
            description = description,
            photos = photos,
            conditionRatings = conditionRatings,
            vehicles = vehicles,
            featureUpdates = featureUpdates,
            clientSubmissionId = clientSubmissionId,
        )
    }
}

@Serializable
data class MeResponse(
    val id: String,
    val email: String,
    val name: String,
)
