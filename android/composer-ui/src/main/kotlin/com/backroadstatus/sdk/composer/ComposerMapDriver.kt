package com.backroadstatus.sdk.composer

import android.content.Context
import android.graphics.PointF
import com.backroadstatus.sdk.core.RoadConditionPoint
import com.backroadstatus.sdk.mapkit.Annotation
import com.backroadstatus.sdk.mapkit.BoundingBox
import com.backroadstatus.sdk.mapkit.Coordinate
import com.backroadstatus.sdk.mapkit.MapEdgeInsets
import com.backroadstatus.sdk.mapkit.MapProvider
import com.backroadstatus.sdk.mapkit.BackroadStatusMapLayers as MapKitLayers

/**
 * Owns the composer's map-driving logic: installs overlay layers,
 * diff-pushes slider / feature markers, and fits the viewport to the
 * focused road. Entirely vendor-neutral — everything flows through the
 * active [MapProvider].
 *
 * `ReportComposerScreen` holds a [ComposerMapDriver] as a `remember`-ed
 * instance, hands [attach] to the adapter via [ComposerMapBinding.onReady],
 * and calls the `update*` methods when its state changes.
 */
class ComposerMapDriver(private val context: Context) {

    private var provider: MapProvider? = null
    private var pathId: String = ""

    private var lastKnownCoords: List<List<Double>> = emptyList()
    private var lastKnownConditions: List<RoadConditionPoint> = emptyList()
    private var pendingSliderMarkers: List<SliderMarker> = emptyList()
    private var pendingFeatureMarkers: List<PlacedFeatureMarker> = emptyList()

    private val installedAnnotationIds: MutableSet<String> = mutableSetOf()
    private val annotationSignatures: MutableMap<String, Int> = mutableMapOf()

    private var lastFittedCoordCount: Int = 0
    private var styleAttached = false

    /**
     * Called by the adapter (via [ComposerMapBinding.onReady]) once the
     * [MapProvider] is drivable. Registers the style-load handler,
     * installs overlay layers if the style is already ready, and replays
     * any state that was pushed in before attach.
     */
    fun attach(provider: MapProvider, pathId: String) {
        this.provider = provider
        this.pathId = pathId

        provider.onStyleLoaded = { installLayersAndReplay() }

        if (provider.isStyleReady) {
            installLayersAndReplay()
        }
    }

    private fun installLayersAndReplay() {
        val p = provider ?: return
        MapKitLayers.applyCanonicalConditionColors(p)
        ComposerMapLayers.excludeFocusedPath(p, pathId)
        ComposerMapLayers.installGeometryOverlay(p)
        ComposerMapLayers.installDistanceMarkerLayer(p)
        styleAttached = true

        // Replay whatever state arrived before the style was ready.
        pushOverlay()
        syncSliderMarkers(pendingSliderMarkers)
        syncCreatedFeatureMarkers(pendingFeatureMarkers)

        if (lastKnownCoords.size >= 2) {
            fitToCoordinates(lastKnownCoords)
            lastFittedCoordCount = lastKnownCoords.size
        }
    }

    // Input updates

    fun updateCoordinates(coords: List<List<Double>>, conditions: List<RoadConditionPoint>) {
        lastKnownCoords = coords
        lastKnownConditions = conditions
        pushOverlay()
        if (coords.size <= lastFittedCoordCount || coords.size < 2) return
        lastFittedCoordCount = coords.size
        fitToCoordinates(coords)
    }

    fun updateSliderMarkers(markers: List<SliderMarker>) {
        pendingSliderMarkers = markers
        syncSliderMarkers(markers)
    }

    fun updateCreatedFeatureMarkers(markers: List<PlacedFeatureMarker>) {
        pendingFeatureMarkers = markers
        syncCreatedFeatureMarkers(markers)
    }

    // Overlay pushing

    private fun pushOverlay() {
        val p = provider ?: return
        if (!styleAttached) return
        ComposerMapLayers.updateGeometryOverlay(p, lastKnownCoords, lastKnownConditions)
        ComposerMapLayers.updateDistanceMarkers(p, lastKnownCoords)
    }

    private fun fitToCoordinates(coords: List<List<Double>>) {
        val p = provider ?: return
        val converted = coords.map { Coordinate.fromLonLat(it) }
        val bounds = BoundingBox.fitting(converted) ?: return
        p.fit(bounds, padding = MapEdgeInsets.all(30.0), animated = true)
    }

    // Slider markers

    private fun sliderSignature(marker: SliderMarker): Int {
        return "slider".hashCode() * 31 + marker.color.hashCode()
    }

    private fun syncSliderMarkers(markers: List<SliderMarker>) {
        val p = provider ?: return
        if (!styleAttached) return

        val incomingIds = markers.map { sliderPrefix + it.id }.toSet()
        val toRemove = installedAnnotationIds
            .filter { it.startsWith(sliderPrefix) && it !in incomingIds }
            .toList()
        for (id in toRemove) {
            p.removeAnnotation(id)
            installedAnnotationIds.remove(id)
            annotationSignatures.remove(id)
        }

        val density = context.resources.displayMetrics.density
        for (marker in markers) {
            val id = sliderPrefix + marker.id
            val sig = sliderSignature(marker)
            if (id in installedAnnotationIds && annotationSignatures[id] == sig) {
                p.moveAnnotation(id, Coordinate.fromLonLat(marker.position))
                continue
            }
            val annotation = Annotation(
                id = id,
                coordinate = Coordinate.fromLonLat(marker.position),
                image = MarkerRasterizer.sliderDot(density, marker.color),
                anchor = PointF(0.5f, 0.5f),
                zPriority = 10,
            )
            p.addAnnotation(annotation)
            installedAnnotationIds.add(id)
            annotationSignatures[id] = sig
        }
    }

    // Created-feature markers

    private fun featureSignature(marker: PlacedFeatureMarker): Int =
        "feature".hashCode() * 31 + marker.typeName.hashCode()

    private fun syncCreatedFeatureMarkers(markers: List<PlacedFeatureMarker>) {
        val p = provider ?: return
        if (!styleAttached) return

        val incomingIds = markers.map { featurePrefix + it.id }.toSet()
        val toRemove = installedAnnotationIds
            .filter { it.startsWith(featurePrefix) && it !in incomingIds }
            .toList()
        for (id in toRemove) {
            p.removeAnnotation(id)
            installedAnnotationIds.remove(id)
            annotationSignatures.remove(id)
        }

        for (marker in markers) {
            val id = featurePrefix + marker.id
            val sig = featureSignature(marker)
            if (id in installedAnnotationIds && annotationSignatures[id] == sig) {
                p.moveAnnotation(id, Coordinate.fromLonLat(marker.position))
                continue
            }
            val image = MarkerRasterizer.featureIcon(context, marker.typeName) ?: continue
            val annotation = Annotation(
                id = id,
                coordinate = Coordinate.fromLonLat(marker.position),
                image = image,
                anchor = PointF(0.5f, 0.5f),
                zPriority = 20,
            )
            p.addAnnotation(annotation)
            installedAnnotationIds.add(id)
            annotationSignatures[id] = sig
        }
    }

    companion object {
        private const val sliderPrefix = "slider:"
        private const val featurePrefix = "feature:"
    }
}
