import Foundation
import UIKit
import MapLibre
import BackroadStatusMapKit

/// Concrete `MapProvider` over MapLibre's `MLNMapView` + `MLNStyle`.
///
/// Translates the vendor-neutral MapLibre Style Spec JSON the composer
/// speaks into MapLibre's typed `MLNStyleLayer` / `MLNShapeSource` API.
/// Expression values flow through `NSExpression(mglJSONObject:)`; filters
/// through `NSPredicate(mglJSONObject:)`; paint/layout keys are mapped to
/// MLN Swift property names in `apply(property:value:to:)`.
///
/// Only the paint/layout properties the composer actively uses are wired
/// here — line/symbol in practice. Extending to a new property means one
/// entry in the switch, not rebuilding the abstraction.
public final class MapLibreMapProvider: NSObject, MapProvider, MLNMapViewDelegate {

    private weak var mapView: MLNMapView?
    private var styleLoaded = false
    private var annotations: [String: ImageAnnotation] = [:]
    private var tapRecognizer: UITapGestureRecognizer?

    public var onStyleLoaded: (() -> Void)?
    public var onTap: ((Coordinate) -> Void)?

    public init(mapView: MLNMapView) {
        self.mapView = mapView
        super.init()
        mapView.delegate = self
        installTapRecognizer(on: mapView)
    }

    // MARK: - Style lifecycle

    public func loadStyle(url: URL) async throws {
        guard let mapView else { return }
        styleLoaded = false
        await MainActor.run { mapView.styleURL = url }
        // didFinishLoading(style:) fires `onStyleLoaded`; we don't await it
        // here — the composer attaches its `onStyleLoaded` handler directly.
    }

    public var isStyleReady: Bool { styleLoaded && mapView?.style != nil }

    public func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
        styleLoaded = true
        onStyleLoaded?()
    }

    // MARK: - Sources

    public func addSource(id: String, spec: [String: Any]) throws {
        guard let style = mapView?.style else { throw ProviderError.styleNotReady }
        if style.source(withIdentifier: id) != nil { return }

        let type = (spec["type"] as? String) ?? "geojson"
        switch type {
        case "geojson":
            let source = MLNShapeSource(identifier: id, shape: Self.shape(from: spec["data"]), options: nil)
            style.addSource(source)
        default:
            throw ProviderError.unsupportedSourceType(type)
        }
    }

    public func setSourceData(id: String, geoJSON: [String: Any]) throws {
        guard let source = mapView?.style?.source(withIdentifier: id) as? MLNShapeSource else {
            throw ProviderError.sourceMissing(id)
        }
        source.shape = Self.shape(from: geoJSON)
    }

    public func sourceExists(id: String) -> Bool {
        mapView?.style?.source(withIdentifier: id) != nil
    }

    private static func shape(from data: Any?) -> MLNShape? {
        guard let data else { return nil }
        guard let json = try? JSONSerialization.data(withJSONObject: data) else { return nil }
        return try? MLNShape(data: json, encoding: String.Encoding.utf8.rawValue)
    }

    // MARK: - Layers

    public func addLayer(spec: [String: Any], below: String?) throws {
        guard let style = mapView?.style else { throw ProviderError.styleNotReady }
        guard let id = spec["id"] as? String,
              let type = spec["type"] as? String,
              let sourceId = spec["source"] as? String else {
            throw ProviderError.invalidLayerSpec
        }
        if style.layer(withIdentifier: id) != nil { return }
        guard let source = style.source(withIdentifier: sourceId) else {
            throw ProviderError.sourceMissing(sourceId)
        }

        let layer: MLNVectorStyleLayer
        switch type {
        case "line":   layer = MLNLineStyleLayer(identifier: id, source: source)
        case "symbol": layer = MLNSymbolStyleLayer(identifier: id, source: source)
        default:
            throw ProviderError.unsupportedLayerType(type)
        }

        if let sourceLayer = spec["source-layer"] as? String {
            layer.sourceLayerIdentifier = sourceLayer
        }
        if let minZoom = spec["minzoom"] as? Double {
            layer.minimumZoomLevel = Float(minZoom)
        }
        if let maxZoom = spec["maxzoom"] as? Double {
            layer.maximumZoomLevel = Float(maxZoom)
        }
        if let filter = spec["filter"] as? [Any] {
            layer.predicate = NSPredicate(mglJSONObject: filter)
        }

        if let paint = spec["paint"] as? [String: Any] {
            for (key, value) in paint {
                try apply(property: key, value: value, to: layer)
            }
        }
        if let layout = spec["layout"] as? [String: Any] {
            for (key, value) in layout {
                try apply(property: key, value: value, to: layer)
            }
        }

        if let below, let belowLayer = style.layer(withIdentifier: below) {
            style.insertLayer(layer, below: belowLayer)
        } else {
            style.addLayer(layer)
        }
    }

    public func removeLayer(id: String) throws {
        guard let style = mapView?.style else { return }
        if let layer = style.layer(withIdentifier: id) {
            style.removeLayer(layer)
        }
    }

    public func layerExists(id: String) -> Bool {
        mapView?.style?.layer(withIdentifier: id) != nil
    }

    public func setLayerProperty(layer: String, property: String, value: Any) throws {
        guard let style = mapView?.style else { throw ProviderError.styleNotReady }
        guard let mlnLayer = style.layer(withIdentifier: layer) as? MLNVectorStyleLayer else {
            throw ProviderError.layerMissing(layer)
        }
        try apply(property: property, value: value, to: mlnLayer)
    }

    public func setLayerFilter(layer: String, filter: [Any]) throws {
        guard let style = mapView?.style else { throw ProviderError.styleNotReady }
        guard let mlnLayer = style.layer(withIdentifier: layer) as? MLNVectorStyleLayer else {
            throw ProviderError.layerMissing(layer)
        }
        mlnLayer.predicate = NSPredicate(mglJSONObject: filter)
    }

    public func addFilterCondition(layer: String, filter: [Any]) throws {
        guard let style = mapView?.style else { throw ProviderError.styleNotReady }
        guard let mlnLayer = style.layer(withIdentifier: layer) as? MLNVectorStyleLayer else {
            throw ProviderError.layerMissing(layer)
        }
        let additional = NSPredicate(mglJSONObject: filter)
        if let existing = mlnLayer.predicate {
            mlnLayer.predicate = NSCompoundPredicate(andPredicateWithSubpredicates: [existing, additional])
        } else {
            mlnLayer.predicate = additional
        }
    }

    /// Converts a style-spec property value into an `NSExpression`. JSON
    /// arrays are treated as MapLibre expressions; everything else becomes
    /// a constant.
    ///
    /// Composer code uses `UIColor` directly inside expression arrays
    /// (match cases, case branches, …); MapLibre's JSON parser doesn't
    /// recognize `UIColor` embedded in an array (it serializes via
    /// `description`, which then explodes at match-time with
    /// `NSNumber isEqualToString:`). We coerce colors to `#rrggbb` /
    /// `rgba(...)` strings before handing the array off.
    private static func expression(from value: Any) -> NSExpression {
        if let json = value as? [Any] {
            return NSExpression(mglJSONObject: sanitize(json))
        }
        return NSExpression(forConstantValue: value)
    }

    /// Recursively converts any `UIColor` values inside a JSON expression
    /// array to `rgba(r, g, b, a)` strings. Everything else passes through
    /// untouched.
    private static func sanitize(_ json: [Any]) -> [Any] {
        json.map { element -> Any in
            if let color = element as? UIColor {
                return cssString(for: color)
            }
            if let nested = element as? [Any] {
                return sanitize(nested)
            }
            if let dict = element as? [String: Any] {
                return dict.mapValues { value -> Any in
                    if let c = value as? UIColor { return cssString(for: c) }
                    if let nested = value as? [Any] { return sanitize(nested) }
                    return value
                }
            }
            return element
        }
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

    /// Only the paint/layout properties the composer actually uses are
    /// wired up. Add entries here as the composer's style grows — the
    /// adapter is the single place that knows how spec names map to MLN
    /// Swift names (`line-dasharray` → `lineDashPattern`, etc.) and
    /// which spec values are literal arrays vs expression arrays.
    private func apply(property: String, value: Any, to layer: MLNVectorStyleLayer) throws {
        if let line = layer as? MLNLineStyleLayer {
            switch property {
            case "line-color":      line.lineColor = Self.expression(from: value)
            case "line-width":      line.lineWidth = Self.expression(from: value)
            case "line-opacity":    line.lineOpacity = Self.expression(from: value)
            case "line-gap-width":  line.lineGapWidth = Self.expression(from: value)
            case "line-blur":       line.lineBlur = Self.expression(from: value)
            case "line-cap":        line.lineCap = Self.expression(from: value)
            case "line-join":       line.lineJoin = Self.expression(from: value)
            case "line-dasharray":
                // Literal number array, not an expression — routing
                // `[2, 1]` through `NSExpression(mglJSONObject:)` would
                // make MapLibre try to dispatch `2` as an opcode string
                // and crash on `NSNumber isEqualToString:`.
                line.lineDashPattern = NSExpression(forConstantValue: value)
            default: throw ProviderError.unsupportedProperty(property)
            }
            return
        }
        if let symbol = layer as? MLNSymbolStyleLayer {
            switch property {
            case "text-field":           symbol.text = Self.expression(from: value)
            case "text-font":
                // Literal string array — see line-dasharray comment.
                symbol.textFontNames = NSExpression(forConstantValue: value)
            case "text-size":            symbol.textFontSize = Self.expression(from: value)
            case "text-anchor":          symbol.textAnchor = Self.expression(from: value)
            case "text-offset":
                // Literal 2-vector. MapLibre's typed API wants an
                // `NSValue(cgVector:)`; the JSON form is a `[Double]` pair.
                if let pair = value as? [Any], pair.count == 2,
                   let dx = (pair[0] as? NSNumber)?.doubleValue,
                   let dy = (pair[1] as? NSNumber)?.doubleValue {
                    symbol.textOffset = NSExpression(forConstantValue: NSValue(cgVector: CGVector(dx: dx, dy: dy)))
                } else {
                    symbol.textOffset = NSExpression(forConstantValue: value)
                }
            case "text-justify":         symbol.textJustification = Self.expression(from: value)
            case "text-color":           symbol.textColor = Self.expression(from: value)
            case "text-halo-color":      symbol.textHaloColor = Self.expression(from: value)
            case "text-halo-width":      symbol.textHaloWidth = Self.expression(from: value)
            case "symbol-placement":     symbol.symbolPlacement = Self.expression(from: value)
            case "icon-allow-overlap":   symbol.iconAllowsOverlap = Self.expression(from: value)
            case "icon-ignore-placement": symbol.iconIgnoresPlacement = Self.expression(from: value)
            default: throw ProviderError.unsupportedProperty(property)
            }
            return
        }
        throw ProviderError.unsupportedLayerForProperty(property)
    }

    // MARK: - Viewport

    public var zoom: Double { mapView?.zoomLevel ?? 0 }

    public func setZoom(_ zoom: Double, animated: Bool) {
        mapView?.setZoomLevel(zoom, animated: animated)
    }

    public func fit(bounds: BoundingBox, padding: MapEdgeInsets, animated: Bool) {
        guard let mapView else { return }
        let mlnBounds = MLNCoordinateBounds(
            sw: CLLocationCoordinate2D(latitude: bounds.sw.latitude, longitude: bounds.sw.longitude),
            ne: CLLocationCoordinate2D(latitude: bounds.ne.latitude, longitude: bounds.ne.longitude)
        )
        mapView.setVisibleCoordinateBounds(
            mlnBounds,
            edgePadding: UIEdgeInsets(top: padding.top, left: padding.left, bottom: padding.bottom, right: padding.right),
            animated: animated
        )
    }

    // MARK: - Interaction

    private func installTapRecognizer(on mapView: MLNMapView) {
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        tap.cancelsTouchesInView = false
        mapView.addGestureRecognizer(tap)
        tapRecognizer = tap
    }

    @objc private func handleTap(_ recognizer: UITapGestureRecognizer) {
        guard let mapView, recognizer.state == .ended, let onTap else { return }
        let point = recognizer.location(in: mapView)
        let coord = mapView.convert(point, toCoordinateFrom: mapView)
        onTap(Coordinate(longitude: coord.longitude, latitude: coord.latitude))
    }

    // MARK: - Annotations (image-based via MLNAnnotationImage)

    public func addAnnotation(_ annotation: Annotation) {
        guard let mapView else { return }
        if let existing = annotations[annotation.id] {
            // Replacing visual — remove then re-add so `imageFor:` picks up
            // the new image under the same reuse identifier.
            mapView.removeAnnotation(existing)
        }
        let a = ImageAnnotation(annotation: annotation)
        a.coordinate = CLLocationCoordinate2D(latitude: annotation.coordinate.latitude, longitude: annotation.coordinate.longitude)
        annotations[annotation.id] = a
        mapView.addAnnotation(a)
    }

    public func moveAnnotation(id: String, to coord: Coordinate) {
        guard let a = annotations[id] else { return }
        a.coordinate = CLLocationCoordinate2D(latitude: coord.latitude, longitude: coord.longitude)
    }

    public func removeAnnotation(id: String) {
        guard let mapView, let a = annotations[id] else { return }
        mapView.removeAnnotation(a)
        annotations.removeValue(forKey: id)
    }

    public func mapView(_ mapView: MLNMapView, imageFor annotation: MLNAnnotation) -> MLNAnnotationImage? {
        guard let ours = annotation as? ImageAnnotation else { return nil }
        // Reuse identifier keyed by image identity so shared visuals share
        // the GL-texture slot while unique ones get their own.
        let reuseId = "backroadstatus.\(ObjectIdentifier(ours.image).hashValue)"
        if let cached = mapView.dequeueReusableAnnotationImage(withIdentifier: reuseId) {
            return cached
        }
        return MLNAnnotationImage(image: ours.image, reuseIdentifier: reuseId)
    }

    // MARK: - Errors

    public enum ProviderError: Error, CustomStringConvertible {
        case styleNotReady
        case sourceMissing(String)
        case layerMissing(String)
        case unsupportedSourceType(String)
        case unsupportedLayerType(String)
        case unsupportedProperty(String)
        case unsupportedLayerForProperty(String)
        case invalidLayerSpec

        public var description: String {
            switch self {
            case .styleNotReady: return "MapProvider: style not ready"
            case .sourceMissing(let id): return "MapProvider: source \(id) missing"
            case .layerMissing(let id): return "MapProvider: layer \(id) missing"
            case .unsupportedSourceType(let t): return "MapProvider: unsupported source type \(t)"
            case .unsupportedLayerType(let t): return "MapProvider: unsupported layer type \(t)"
            case .unsupportedProperty(let p): return "MapProvider: unsupported property \(p)"
            case .unsupportedLayerForProperty(let p): return "MapProvider: property \(p) on unsupported layer type"
            case .invalidLayerSpec: return "MapProvider: layer spec missing required id/type/source"
            }
        }
    }
}

/// Internal MLNPointAnnotation subclass carrying the pre-rendered image so
/// the delegate's `imageFor:` can return it.
private final class ImageAnnotation: MLNPointAnnotation {
    let image: UIImage
    init(annotation: Annotation) {
        self.image = annotation.image
        super.init()
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) not supported") }
}
