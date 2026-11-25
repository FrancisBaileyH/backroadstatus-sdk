import Foundation
import UIKit
import MapboxMaps
import BackroadStatusMapKit

/// Concrete `MapProvider` over Mapbox Maps SDK v11.
///
/// Sources, layers, and property updates all flow through Mapbox's
/// JSON-based style APIs (`addSource(withId:properties:)`,
/// `addLayer(with:layerPosition:)`, `setLayerProperty(...)`) rather than
/// the typed `LineLayer` / `SymbolLayer` structs. Mapbox natively consumes
/// the MapLibre Style Spec JSON the composer speaks, so the adapter stays
/// thin — just coerce `UIColor` values embedded in expression arrays into
/// CSS strings (Mapbox's JSON serializer, like MapLibre's, can't handle
/// `UIColor` inside an expression).
///
/// Thread-safety: `@MainActor` via the protocol.
public final class MapboxMapProvider: NSObject, MapProvider {

    private weak var mapView: MapView?
    private var mapboxMap: MapboxMap? { mapView?.mapboxMap }
    private var styleLoaded = false
    private var styleLoadToken: AnyCancelable?
    private var tapRecognizer: UITapGestureRecognizer?

    /// Annotation manager is lazily created on first `addAnnotation` so
    /// styles that don't use annotations don't incur the manager cost.
    private var pointManager: PointAnnotationManager?
    private var annotations: [String: PointAnnotation] = [:]

    public var onStyleLoaded: (() -> Void)?
    public var onTap: ((Coordinate) -> Void)?

    public init(mapView: MapView) {
        self.mapView = mapView
        super.init()
        subscribeToStyleLoaded(mapView)
        installTapRecognizer(on: mapView)
    }

    // MARK: - Style lifecycle

    private func subscribeToStyleLoaded(_ mapView: MapView) {
        styleLoadToken = mapView.mapboxMap.onStyleLoaded.observe { [weak self] _ in
            guard let self else { return }
            self.styleLoaded = true
            self.onStyleLoaded?()
        }
    }

    public func loadStyle(url: URL) async throws {
        guard let mapboxMap else { return }
        styleLoaded = false
        // v11's loadStyle(_:completion:) is callback-based; bridge to async.
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            mapboxMap.loadStyle(StyleURI(url: url) ?? .standard) { error in
                if let error { cont.resume(throwing: error) }
                else { cont.resume() }
            }
        }
    }

    public var isStyleReady: Bool { styleLoaded && mapboxMap?.isStyleLoaded == true }

    // MARK: - Sources

    public func addSource(id: String, spec: [String: Any]) throws {
        guard let mapboxMap else { throw ProviderError.styleNotReady }
        if mapboxMap.sourceExists(withId: id) { return }
        try mapboxMap.addSource(withId: id, properties: spec)
    }

    public func setSourceData(id: String, geoJSON: [String: Any]) throws {
        guard let mapboxMap else { throw ProviderError.styleNotReady }
        guard mapboxMap.sourceExists(withId: id) else { throw ProviderError.sourceMissing(id) }
        // Round-trip the composer's plain-dict JSON through `GeoJSONObject`
        // (a Codable enum of feature / featureCollection / geometry), which
        // is what Mapbox's public source-update API takes.
        let data = try JSONSerialization.data(withJSONObject: geoJSON)
        let geoJSONObject = try JSONDecoder().decode(GeoJSONObject.self, from: data)
        mapboxMap.updateGeoJSONSource(withId: id, geoJSON: geoJSONObject)
    }

    public func sourceExists(id: String) -> Bool {
        mapboxMap?.sourceExists(withId: id) ?? false
    }

    // MARK: - Layers

    public func addLayer(spec: [String: Any], below: String?) throws {
        guard let mapboxMap else { throw ProviderError.styleNotReady }
        guard let id = spec["id"] as? String else { throw ProviderError.invalidLayerSpec }
        if mapboxMap.layerExists(withId: id) { return }

        let sanitizedSpec = Self.sanitizeLayerSpec(spec)
        let position: LayerPosition? = below.map { .below($0) }
        try mapboxMap.addLayer(with: sanitizedSpec, layerPosition: position)
    }

    public func removeLayer(id: String) throws {
        guard let mapboxMap, mapboxMap.layerExists(withId: id) else { return }
        try mapboxMap.removeLayer(withId: id)
    }

    public func layerExists(id: String) -> Bool {
        mapboxMap?.layerExists(withId: id) ?? false
    }

    public func setLayerProperty(layer: String, property: String, value: Any) throws {
        guard let mapboxMap else { throw ProviderError.styleNotReady }
        guard mapboxMap.layerExists(withId: layer) else { throw ProviderError.layerMissing(layer) }
        try mapboxMap.setLayerProperty(for: layer, property: property, value: Self.sanitize(value))
    }

    public func setLayerFilter(layer: String, filter: [Any]) throws {
        guard let mapboxMap else { throw ProviderError.styleNotReady }
        guard mapboxMap.layerExists(withId: layer) else { throw ProviderError.layerMissing(layer) }
        try mapboxMap.setLayerProperty(for: layer, property: "filter", value: Self.sanitize(filter))
    }

    public func addFilterCondition(layer: String, filter: [Any]) throws {
        guard let mapboxMap else { throw ProviderError.styleNotReady }
        guard mapboxMap.layerExists(withId: layer) else { throw ProviderError.layerMissing(layer) }

        let sanitized = Self.sanitize(filter)
        let existing = mapboxMap.layerProperty(for: layer, property: "filter").value
        if let existingArray = existing as? [Any], !existingArray.isEmpty {
            let combined: [Any] = ["all", existingArray, sanitized]
            try mapboxMap.setLayerProperty(for: layer, property: "filter", value: combined)
        } else {
            try mapboxMap.setLayerProperty(for: layer, property: "filter", value: sanitized)
        }
    }

    // MARK: - Viewport

    public var zoom: Double { Double(mapboxMap?.cameraState.zoom ?? 0) }

    public func setZoom(_ zoom: Double, animated: Bool) {
        guard let mapView else { return }
        let cam = CameraOptions(zoom: zoom)
        if animated {
            mapView.camera.ease(to: cam, duration: 0.3)
        } else {
            mapView.mapboxMap.setCamera(to: cam)
        }
    }

    public func fit(bounds: BackroadStatusMapKit.BoundingBox, padding: MapEdgeInsets, animated: Bool) {
        guard let mapView else { return }
        // The `CoordinateBounds`-taking `camera(for:padding:bearing:pitch:)`
        // overload is deprecated in v11; feed the 4 corners into the modern
        // `camera(for coordinates:camera:coordinatesPadding:maxZoom:offset:)`.
        let corners: [CLLocationCoordinate2D] = [
            CLLocationCoordinate2D(latitude: bounds.sw.latitude, longitude: bounds.sw.longitude),
            CLLocationCoordinate2D(latitude: bounds.sw.latitude, longitude: bounds.ne.longitude),
            CLLocationCoordinate2D(latitude: bounds.ne.latitude, longitude: bounds.ne.longitude),
            CLLocationCoordinate2D(latitude: bounds.ne.latitude, longitude: bounds.sw.longitude),
        ]
        let insets = UIEdgeInsets(top: padding.top, left: padding.left, bottom: padding.bottom, right: padding.right)
        guard let camera = try? mapView.mapboxMap.camera(
            for: corners,
            camera: CameraOptions(bearing: 0, pitch: 0),
            coordinatesPadding: insets,
            maxZoom: nil,
            offset: nil
        ) else { return }
        if animated {
            mapView.camera.ease(to: camera, duration: 0.3)
        } else {
            mapView.mapboxMap.setCamera(to: camera)
        }
    }

    // MARK: - Interaction

    private func installTapRecognizer(on mapView: MapView) {
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        tap.cancelsTouchesInView = false
        mapView.addGestureRecognizer(tap)
        tapRecognizer = tap
    }

    @objc private func handleTap(_ recognizer: UITapGestureRecognizer) {
        guard let mapView, recognizer.state == .ended, let onTap else { return }
        let point = recognizer.location(in: mapView)
        let coord = mapView.mapboxMap.coordinate(for: point)
        onTap(Coordinate(longitude: coord.longitude, latitude: coord.latitude))
    }

    // MARK: - Annotations (image-based via PointAnnotationManager)

    public func addAnnotation(_ annotation: BackroadStatusMapKit.Annotation) {
        guard let mapView else { return }
        let manager = pointManager ?? mapView.annotations.makePointAnnotationManager(id: "backroadstatus")
        pointManager = manager

        // Register the image on the style and reference it by id — more
        // stable across Mapbox v11 point releases than `PointAnnotation.Image`.
        // Unique id per annotation so swapping visuals under the same id
        // registers a fresh texture instead of reusing a stale one.
        let imageId = "backroadstatus.\(annotation.id)"
        try? mapView.mapboxMap.addImage(annotation.image, id: imageId)

        var pa = PointAnnotation(
            id: annotation.id,
            coordinate: CLLocationCoordinate2D(
                latitude: annotation.coordinate.latitude,
                longitude: annotation.coordinate.longitude
            )
        )
        pa.iconImage = imageId
        pa.iconAnchor = Self.iconAnchor(for: annotation.anchor)
        pa.symbolSortKey = Double(annotation.zPriority)
        annotations[annotation.id] = pa
        manager.annotations = Array(annotations.values)
    }

    public func moveAnnotation(id: String, to coord: Coordinate) {
        guard var pa = annotations[id] else { return }
        pa.point = Point(CLLocationCoordinate2D(latitude: coord.latitude, longitude: coord.longitude))
        annotations[id] = pa
        pointManager?.annotations = Array(annotations.values)
    }

    public func removeAnnotation(id: String) {
        annotations.removeValue(forKey: id)
        pointManager?.annotations = Array(annotations.values)
    }

    /// Maps the composer's (0,0)=top-left anchor to Mapbox's named
    /// `IconAnchor` cases. Falls back to `.center` for anything outside the
    /// nine canonical positions.
    private static func iconAnchor(for anchor: CGPoint) -> IconAnchor {
        switch (anchor.x, anchor.y) {
        case (0.5, 0.5): return .center
        case (0.5, 0.0): return .top
        case (0.5, 1.0): return .bottom
        case (0.0, 0.5): return .left
        case (1.0, 0.5): return .right
        case (0.0, 0.0): return .topLeft
        case (1.0, 0.0): return .topRight
        case (0.0, 1.0): return .bottomLeft
        case (1.0, 1.0): return .bottomRight
        default:         return .center
        }
    }

    // MARK: - JSON sanitization

    /// Mapbox's JSON serializer, like MapLibre's, serializes `UIColor` via
    /// `description` — which then explodes when the style engine tries to
    /// interpret the string as an opcode. Coerce colors to `rgba(...)`
    /// before handing values off.
    private static func sanitize(_ value: Any) -> Any {
        if let color = value as? UIColor { return cssString(for: color) }
        if let array = value as? [Any] { return array.map(sanitize) }
        if let dict = value as? [String: Any] { return dict.mapValues(sanitize) }
        return value
    }

    /// Recursively sanitizes every value in a layer spec. The top-level
    /// `addLayer(with:layerPosition:)` call takes `[String: Any]` where
    /// `paint`/`layout`/`filter` sub-dicts may contain UIColors.
    private static func sanitizeLayerSpec(_ spec: [String: Any]) -> [String: Any] {
        spec.mapValues(sanitize)
    }

    private static func cssString(for color: UIColor) -> String {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        return String(
            format: "rgba(%d, %d, %d, %g)",
            Int((r * 255).rounded()),
            Int((g * 255).rounded()),
            Int((b * 255).rounded()),
            Double(a)
        )
    }

    // MARK: - Errors

    public enum ProviderError: Error, CustomStringConvertible {
        case styleNotReady
        case sourceMissing(String)
        case layerMissing(String)
        case invalidLayerSpec

        public var description: String {
            switch self {
            case .styleNotReady: return "MapProvider: style not ready"
            case .sourceMissing(let id): return "MapProvider: source \(id) missing"
            case .layerMissing(let id): return "MapProvider: layer \(id) missing"
            case .invalidLayerSpec: return "MapProvider: layer spec missing required id"
            }
        }
    }
}
