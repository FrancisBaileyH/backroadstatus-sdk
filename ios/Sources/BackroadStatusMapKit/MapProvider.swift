import Foundation
import UIKit

/// Vendor-neutral map interface the composer talks to. Adapters implement
/// this over a concrete map SDK (MapLibre, Mapbox, …) by translating
/// MapLibre Style Spec JSON — the composer's preferred currency — into
/// native SDK calls.
///
/// The composer never imports a map SDK. It hands this provider JSON specs
/// for sources + layers + property updates, and the adapter is free to
/// bridge those into whatever native typed API its SDK expects. Both
/// MapLibre and Mapbox natively consume the same style spec, so the
/// translation layer in each adapter stays thin.
///
/// Thread-safety: assume main-actor isolation unless an adapter documents
/// otherwise. All composer calls originate from SwiftUI onChange / Task
/// closures already dispatched to the main queue.
@MainActor
public protocol MapProvider: AnyObject {

    // MARK: - Style lifecycle

    /// Loads a style from `url`. May complete asynchronously; adapters fire
    /// `onStyleLoaded` once the style graph is editable. Multiple calls
    /// replace the previously-loaded style.
    func loadStyle(url: URL) async throws

    /// `true` once the style graph is editable (sources + layers can be
    /// added/queried). False during initial load or style swaps.
    var isStyleReady: Bool { get }

    /// Invoked each time a style finishes loading. Not called for failures.
    var onStyleLoaded: (() -> Void)? { get set }

    // MARK: - Sources (MapLibre Style Spec JSON)

    /// Adds a source from a spec object. For GeoJSON sources, pass
    /// `{"type": "geojson", "data": <geoJSON>}`. Setting `"data"` to an
    /// empty feature collection is the idiomatic initial state.
    func addSource(id: String, spec: [String: Any]) throws

    /// Replaces the `data` payload on a previously-added GeoJSON source.
    /// Pass a full GeoJSON object (Feature, FeatureCollection, or geometry).
    func setSourceData(id: String, geoJSON: [String: Any]) throws

    func sourceExists(id: String) -> Bool

    // MARK: - Layers (MapLibre Style Spec JSON)

    /// Adds a style layer from a spec object. The spec must include `id`,
    /// `type`, and `source` at minimum; `source-layer`, `filter`, `minzoom`,
    /// `maxzoom`, `paint`, `layout` are honored if present. `below` selects
    /// which existing layer to insert this new one beneath (nil → top).
    func addLayer(spec: [String: Any], below: String?) throws

    func removeLayer(id: String) throws

    func layerExists(id: String) -> Bool

    /// Updates a single `paint`/`layout` property on an existing layer.
    /// `value` is either a constant (e.g. `UIColor`, `Double`, `String`,
    /// `[Double]`) or a MapLibre JSON expression as `[Any]`.
    func setLayerProperty(layer: String, property: String, value: Any) throws

    /// Updates the layer's filter. Pass a MapLibre JSON filter expression
    /// as `[Any]` (e.g. `["==", ["get", "id"], "road-42"]`).
    func setLayerFilter(layer: String, filter: [Any]) throws

    /// ANDs `filter` into the layer's existing filter instead of replacing
    /// it. Lets the composer exclude the focused road from condition tile
    /// layers without clobbering the `condition == NOMINAL` filter the
    /// host style ships with. The adapter handles the AND composition in
    /// its native filter representation.
    func addFilterCondition(layer: String, filter: [Any]) throws

    // MARK: - Viewport

    var zoom: Double { get }
    func setZoom(_ zoom: Double, animated: Bool)
    func fit(bounds: BoundingBox, padding: MapEdgeInsets, animated: Bool)

    // MARK: - Interaction

    /// Fires on taps with the tapped geographic coordinate.
    var onTap: ((Coordinate) -> Void)? { get set }

    // MARK: - Annotations (image-based, cross-SDK)

    /// Adds or replaces an annotation by id. Adapters typically render these
    /// via a dedicated symbol layer so the same image representation works
    /// across map SDKs without custom-view plumbing.
    func addAnnotation(_ annotation: Annotation)

    /// Updates an existing annotation's coordinate without re-rasterizing.
    func moveAnnotation(id: String, to coord: Coordinate)

    func removeAnnotation(id: String)
}
