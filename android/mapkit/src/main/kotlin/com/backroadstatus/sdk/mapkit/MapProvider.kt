package com.backroadstatus.sdk.mapkit

/**
 * Vendor-neutral map interface the composer talks to. Adapters implement
 * this over a concrete map SDK (MapLibre, Mapbox, …) by translating
 * MapLibre Style Spec JSON — the composer's preferred currency — into
 * native SDK calls.
 *
 * The composer never imports a map SDK. It hands this provider JSON specs
 * for sources + layers + property updates, and the adapter is free to
 * bridge those into whatever native typed API its SDK expects. Both
 * MapLibre and Mapbox natively consume the same style spec, so the
 * translation layer in each adapter stays thin.
 *
 * Thread-safety: assume main-thread isolation unless an adapter documents
 * otherwise. All composer calls originate from Compose `LaunchedEffect` /
 * `remember` closures dispatched to the main thread.
 */
interface MapProvider {

    // Style lifecycle

    /**
     * Loads a style from [url]. May complete asynchronously; adapters fire
     * [onStyleLoaded] once the style graph is editable. Multiple calls
     * replace the previously-loaded style.
     */
    suspend fun loadStyle(url: String)

    /**
     * `true` once the style graph is editable (sources + layers can be
     * added/queried). False during initial load or style swaps.
     */
    val isStyleReady: Boolean

    /** Invoked each time a style finishes loading. Not called for failures. */
    var onStyleLoaded: (() -> Unit)?

    // Sources (MapLibre Style Spec JSON)

    /**
     * Adds a source from a spec object. For GeoJSON sources, pass
     * `{"type": "geojson", "data": <geoJSON>}`. Setting `"data"` to an
     * empty feature collection is the idiomatic initial state.
     */
    fun addSource(id: String, spec: Map<String, Any?>)

    /**
     * Replaces the `data` payload on a previously-added GeoJSON source.
     * Pass a full GeoJSON object (Feature, FeatureCollection, or geometry).
     */
    fun setSourceData(id: String, geoJson: Map<String, Any?>)

    fun sourceExists(id: String): Boolean

    // Layers (MapLibre Style Spec JSON)

    /**
     * Adds a style layer from a spec object. The spec must include `id`,
     * `type`, and `source` at minimum; `source-layer`, `filter`, `minzoom`,
     * `maxzoom`, `paint`, `layout` are honored if present. [below] selects
     * which existing layer to insert this new one beneath (null → top).
     */
    fun addLayer(spec: Map<String, Any?>, below: String? = null)

    fun removeLayer(id: String)

    fun layerExists(id: String): Boolean

    /**
     * Updates a single `paint` / `layout` property on an existing layer.
     * [value] is either a constant (e.g. Compose `Color`, `Double`,
     * `String`, `List<Double>`) or a MapLibre JSON expression as `List<Any?>`.
     */
    fun setLayerProperty(layer: String, property: String, value: Any)

    /**
     * Updates the layer's filter. Pass a MapLibre JSON filter expression
     * (e.g. `listOf("==", listOf("get", "id"), "road-42")`).
     */
    fun setLayerFilter(layer: String, filter: List<Any?>)

    /**
     * ANDs [filter] into the layer's existing filter instead of replacing
     * it. Lets the composer exclude the focused road from condition tile
     * layers without clobbering the `condition == NOMINAL` filter the
     * host style ships with. The adapter handles the AND composition in
     * its native filter representation.
     */
    fun addFilterCondition(layer: String, filter: List<Any?>)

    // Viewport

    val zoom: Double
    fun setZoom(zoom: Double, animated: Boolean)
    fun fit(bounds: BoundingBox, padding: MapEdgeInsets, animated: Boolean)

    // Interaction

    /** Fires on taps with the tapped geographic coordinate. */
    var onTap: ((Coordinate) -> Unit)?

    // Annotations (image-based, cross-SDK)

    /**
     * Adds or replaces an annotation by id. Adapters typically render these
     * via a dedicated symbol layer so the same image representation works
     * across map SDKs without custom-view plumbing.
     */
    fun addAnnotation(annotation: Annotation)

    /** Updates an existing annotation's coordinate without re-rasterizing. */
    fun moveAnnotation(id: String, to: Coordinate)

    fun removeAnnotation(id: String)
}
