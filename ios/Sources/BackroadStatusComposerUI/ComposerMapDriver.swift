import Foundation
import UIKit
import BackroadStatusCore
import BackroadStatusMapKit

/// Owns the composer's map-driving logic: installs overlay layers,
/// diff-pushes slider / feature markers, and fits the viewport to the
/// focused road. Entirely vendor-neutral — everything flows through
/// the active `MapProvider`.
///
/// `ReportComposerView` holds a `ComposerMapDriver` as a `@StateObject`,
/// hands `driver.attach(provider:)` to the adapter via
/// `ComposerMapBinding.onReady`, and calls `driver.update*` when its
/// SwiftUI state changes.
///
/// Geometry no longer comes from tile-stitching — the composer pulls
/// canonical coords from `GeometryStore` (pre-downloaded with the offline
/// pack) or the live API. This driver's only job is rendering what the
/// composer hands it.
@MainActor
public final class ComposerMapDriver: ObservableObject {

    // MARK: - State mirrored from the composer

    private weak var provider: MapProvider?
    private var pathId: String = ""

    private var lastKnownCoords: [[Double]] = []
    private var lastKnownConditions: [RoadConditionPoint] = []
    private var pendingSliderMarkers: [SliderMarker] = []
    private var pendingFeatureMarkers: [PlacedFeatureMarker] = []

    private var installedAnnotationIds: Set<String> = []
    private var annotationSignatures: [String: Int] = [:]

    private var lastFittedCoordCount: Int = 0
    private var styleAttached = false

    public init() {}

    // MARK: - Lifecycle

    /// Called by the adapter (via `ComposerMapBinding.onReady`) once the
    /// `MapProvider` is drivable. Registers the style-load handler,
    /// installs overlay layers if the style is already ready, and
    /// replays any state that was pushed in before attach.
    public func attach(provider: MapProvider, pathId: String) {
        self.provider = provider
        self.pathId = pathId

        provider.onStyleLoaded = { [weak self] in
            self?.installLayersAndReplay()
        }

        if provider.isStyleReady {
            installLayersAndReplay()
        }
    }

    private func installLayersAndReplay() {
        guard let provider else { return }
        BackroadStatusMapLayers.applyCanonicalConditionColors(provider: provider)
        ComposerMapLayers.excludeFocusedPath(provider: provider, pathId: pathId)
        ComposerMapLayers.installGeometryOverlay(provider: provider)
        ComposerMapLayers.installDistanceMarkerLayer(provider: provider)
        styleAttached = true

        // Replay whatever state arrived before the style was ready.
        pushOverlay()
        syncSliderMarkers(pendingSliderMarkers)
        syncCreatedFeatureMarkers(pendingFeatureMarkers)

        // If geometry already arrived (cache hit before style loaded),
        // fit to it. Otherwise wait for the composer's onChange to drive
        // the fit once GeometryStore / the API returns coords.
        if lastKnownCoords.count >= 2 {
            fitToCoordinates(lastKnownCoords)
            lastFittedCoordCount = lastKnownCoords.count
        }
    }

    // MARK: - Input updates

    public func updateCoordinates(_ coords: [[Double]], conditions: [RoadConditionPoint]) {
        lastKnownCoords = coords
        lastKnownConditions = conditions
        pushOverlay()

        guard coords.count > lastFittedCoordCount, coords.count >= 2 else { return }
        lastFittedCoordCount = coords.count
        fitToCoordinates(coords)
    }

    public func updateSliderMarkers(_ markers: [SliderMarker]) {
        pendingSliderMarkers = markers
        syncSliderMarkers(markers)
    }

    public func updateCreatedFeatureMarkers(_ markers: [PlacedFeatureMarker]) {
        pendingFeatureMarkers = markers
        syncCreatedFeatureMarkers(markers)
    }

    // MARK: - Overlay pushing

    private func pushOverlay() {
        guard let provider, styleAttached else { return }
        ComposerMapLayers.updateGeometryOverlay(provider: provider, coords: lastKnownCoords, conditions: lastKnownConditions)
        ComposerMapLayers.updateDistanceMarkers(provider: provider, coords: lastKnownCoords)
    }

    private func fitToCoordinates(_ coords: [[Double]]) {
        guard let provider else { return }
        let converted = coords.map { Coordinate(lonLat: $0) }
        guard let bounds = BoundingBox.fitting(converted) else { return }
        provider.fit(bounds: bounds, padding: .all(30), animated: true)
    }

    // MARK: - Slider markers (image-rasterized annotations)

    /// Hashes the visual signature of a slider marker so position-only
    /// changes can `moveAnnotation(id:to:)` instead of re-rasterizing.
    private func sliderSignature(_ marker: SliderMarker) -> Int {
        var hasher = Hasher()
        hasher.combine("slider")
        hasher.combine(marker.color.cgColor.components ?? [])
        return hasher.finalize()
    }

    private func syncSliderMarkers(_ markers: [SliderMarker]) {
        guard let provider, styleAttached else { return }

        let incoming = Set(markers.map(\.id))
        for id in installedAnnotationIds.subtracting(incoming) where annotationSignatures[id] != nil {
            if id.hasPrefix(Self.sliderPrefix) {
                provider.removeAnnotation(id: id)
                installedAnnotationIds.remove(id)
                annotationSignatures.removeValue(forKey: id)
            }
        }

        for marker in markers {
            let id = Self.sliderPrefix + marker.id
            let sig = sliderSignature(marker)
            if installedAnnotationIds.contains(id), annotationSignatures[id] == sig {
                provider.moveAnnotation(id: id, to: Coordinate(lonLat: marker.position))
                continue
            }
            let annotation = Annotation(
                id: id,
                coordinate: Coordinate(lonLat: marker.position),
                image: MarkerRasterizer.sliderDot(color: marker.color),
                anchor: CGPoint(x: 0.5, y: 0.5),
                zPriority: 10
            )
            provider.addAnnotation(annotation)
            installedAnnotationIds.insert(id)
            annotationSignatures[id] = sig
        }
    }

    // MARK: - Created-feature markers

    private func featureSignature(_ marker: PlacedFeatureMarker) -> Int {
        var hasher = Hasher()
        hasher.combine("feature")
        hasher.combine(marker.typeName)
        return hasher.finalize()
    }

    private func syncCreatedFeatureMarkers(_ markers: [PlacedFeatureMarker]) {
        guard let provider, styleAttached else { return }

        let incoming = Set(markers.map(\.id))
        for id in installedAnnotationIds.subtracting(incoming.map { Self.featurePrefix + $0 }) {
            if id.hasPrefix(Self.featurePrefix) {
                provider.removeAnnotation(id: id)
                installedAnnotationIds.remove(id)
                annotationSignatures.removeValue(forKey: id)
            }
        }

        for marker in markers {
            let id = Self.featurePrefix + marker.id
            let sig = featureSignature(marker)
            if installedAnnotationIds.contains(id), annotationSignatures[id] == sig {
                provider.moveAnnotation(id: id, to: Coordinate(lonLat: marker.position))
                continue
            }
            let image = MarkerRasterizer.featureIcon(typeName: marker.typeName)
            let annotation = Annotation(
                id: id,
                coordinate: Coordinate(lonLat: marker.position),
                image: image,
                anchor: CGPoint(x: 0.5, y: 0.5),
                zPriority: 20
            )
            provider.addAnnotation(annotation)
            installedAnnotationIds.insert(id)
            annotationSignatures[id] = sig
        }
    }

    // MARK: - Shared constants

    private static let sliderPrefix = "slider:"
    private static let featurePrefix = "feature:"
}
