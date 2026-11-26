package com.backroadstatus.sdk.maplibre

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.backroadstatus.sdk.mapkit.Annotation
import com.backroadstatus.sdk.mapkit.BoundingBox
import com.backroadstatus.sdk.mapkit.Coordinate
import com.backroadstatus.sdk.mapkit.MapEdgeInsets
import com.backroadstatus.sdk.mapkit.MapProvider
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Concrete [MapProvider] over MapLibre's Android [MapView] + [MapLibreMap].
 *
 * Translates the vendor-neutral MapLibre Style Spec JSON the composer
 * speaks into MapLibre Android's typed layer / source API. Expressions
 * flow through [Expression.Converter.convert]; filters through the same
 * expression pipeline; paint / layout properties are mapped to
 * [PropertyFactory] calls in [applyProperty].
 *
 * Only the paint/layout properties the composer actively uses are wired
 * here — line/symbol in practice. Extending to a new property means one
 * entry in the `when`, not rebuilding the abstraction.
 */
class MapLibreMapProvider(
    private val mapView: MapView,
    private val mapLibreMap: MapLibreMap,
) : MapProvider {

    private var styleRef: Style? = null
    private var styleLoadedInternal = false
    private val annotationFeatures: MutableMap<String, AnnotationState> = linkedMapOf()
    private val pendingImages: MutableMap<String, Bitmap> = mutableMapOf()

    override var onStyleLoaded: (() -> Unit)? = null
    override var onTap: ((Coordinate) -> Unit)? = null

    init {
        mapLibreMap.addOnMapClickListener { point ->
            onTap?.invoke(Coordinate(longitude = point.longitude, latitude = point.latitude))
            true
        }
    }

    // --- Style lifecycle ---

    override suspend fun loadStyle(url: String) {
        val deferred = CompletableDeferred<Unit>()
        styleLoadedInternal = false
        mapLibreMap.setStyle(Style.Builder().fromUri(url)) { style ->
            styleRef = style
            styleLoadedInternal = true
            // Install annotation layer scaffolding (idempotent).
            ensureAnnotationScaffolding(style)
            onStyleLoaded?.invoke()
            deferred.complete(Unit)
        }
        deferred.await()
    }

    override val isStyleReady: Boolean
        get() = styleLoadedInternal && styleRef?.isFullyLoaded == true

    // --- Sources ---

    override fun addSource(id: String, spec: Map<String, Any?>) {
        val style = styleRef ?: throw ProviderError("style not ready")
        if (style.getSource(id) != null) return
        val type = spec["type"] as? String ?: "geojson"
        when (type) {
            "geojson" -> {
                val data = spec["data"]
                val source = GeoJsonSource(id, jsonString(data))
                style.addSource(source)
            }
            else -> throw ProviderError("unsupported source type $type")
        }
    }

    override fun setSourceData(id: String, geoJson: Map<String, Any?>) {
        val style = styleRef ?: throw ProviderError("style not ready")
        val source = style.getSource(id) as? GeoJsonSource ?: throw ProviderError("source $id missing")
        source.setGeoJson(jsonString(geoJson))
    }

    override fun sourceExists(id: String): Boolean = styleRef?.getSource(id) != null

    // --- Layers ---

    override fun addLayer(spec: Map<String, Any?>, below: String?) {
        val style = styleRef ?: throw ProviderError("style not ready")
        val id = spec["id"] as? String ?: throw ProviderError("layer spec missing id")
        val type = spec["type"] as? String ?: throw ProviderError("layer spec missing type")
        val sourceId = spec["source"] as? String ?: throw ProviderError("layer spec missing source")
        if (style.getLayer(id) != null) return
        if (style.getSource(sourceId) == null) throw ProviderError("source $sourceId missing")

        val sourceLayerSpec = spec["source-layer"] as? String
        val filterSpec = spec["filter"] as? List<*>
        val layer = when (type) {
            "line" -> LineLayer(id, sourceId).apply {
                sourceLayerSpec?.let { sourceLayer = it }
                filterSpec?.let { setFilter(expression(it)) }
            }
            "symbol" -> SymbolLayer(id, sourceId).apply {
                sourceLayerSpec?.let { sourceLayer = it }
                filterSpec?.let { setFilter(expression(it)) }
            }
            else -> throw ProviderError("unsupported layer type $type")
        }

        (spec["minzoom"] as? Number)?.let { layer.minZoom = it.toFloat() }
        (spec["maxzoom"] as? Number)?.let { layer.maxZoom = it.toFloat() }

        (spec["paint"] as? Map<*, *>)?.forEach { (k, v) ->
            applyProperty(layer, k as String, v)
        }
        (spec["layout"] as? Map<*, *>)?.forEach { (k, v) ->
            applyProperty(layer, k as String, v)
        }

        if (below != null && style.getLayer(below) != null) {
            style.addLayerBelow(layer, below)
        } else {
            style.addLayer(layer)
        }
    }

    override fun removeLayer(id: String) {
        val style = styleRef ?: return
        style.getLayer(id)?.let { style.removeLayer(it) }
    }

    override fun layerExists(id: String): Boolean = styleRef?.getLayer(id) != null

    override fun setLayerProperty(layer: String, property: String, value: Any) {
        val style = styleRef ?: throw ProviderError("style not ready")
        val styleLayer = style.getLayer(layer) ?: throw ProviderError("layer $layer missing")
        applyProperty(styleLayer, property, value)
    }

    override fun setLayerFilter(layer: String, filter: List<Any?>) {
        val style = styleRef ?: throw ProviderError("style not ready")
        when (val l = style.getLayer(layer)) {
            is LineLayer -> l.setFilter(expression(filter))
            is SymbolLayer -> l.setFilter(expression(filter))
            null -> throw ProviderError("layer $layer missing")
            else -> throw ProviderError("filter not supported on ${l.javaClass.simpleName}")
        }
    }

    override fun addFilterCondition(layer: String, filter: List<Any?>) {
        val style = styleRef ?: throw ProviderError("style not ready")
        val existing = when (val l = style.getLayer(layer)) {
            is LineLayer -> l.filter
            is SymbolLayer -> l.filter
            else -> null
        }
        val additional = expression(filter)
        val combined = if (existing != null) Expression.all(existing, additional) else additional
        when (val l = style.getLayer(layer)) {
            is LineLayer -> l.setFilter(combined)
            is SymbolLayer -> l.setFilter(combined)
            null -> throw ProviderError("layer $layer missing")
            else -> throw ProviderError("filter not supported on ${l.javaClass.simpleName}")
        }
    }

    // --- Viewport ---

    override val zoom: Double get() = mapLibreMap.cameraPosition.zoom
    override fun setZoom(zoom: Double, animated: Boolean) {
        val update = CameraUpdateFactory.zoomTo(zoom)
        if (animated) mapLibreMap.animateCamera(update) else mapLibreMap.moveCamera(update)
    }

    override fun fit(bounds: BoundingBox, padding: MapEdgeInsets, animated: Boolean) {
        val lngBounds = LatLngBounds.from(
            bounds.ne.latitude, bounds.ne.longitude,
            bounds.sw.latitude, bounds.sw.longitude,
        )
        val update = CameraUpdateFactory.newLatLngBounds(
            lngBounds,
            padding.left.toInt(), padding.top.toInt(), padding.right.toInt(), padding.bottom.toInt(),
        )
        if (animated) mapLibreMap.animateCamera(update) else mapLibreMap.moveCamera(update)
    }

    // --- Annotations ---

    override fun addAnnotation(annotation: Annotation) {
        val style = styleRef
        val imageId = annotation.id
        if (style != null) {
            style.addImage(imageId, annotation.image)
        } else {
            pendingImages[imageId] = annotation.image
        }
        annotationFeatures[annotation.id] = AnnotationState(
            id = annotation.id,
            imageId = imageId,
            coordinate = annotation.coordinate,
            anchor = annotation.anchor,
            zPriority = annotation.zPriority,
        )
        refreshAnnotations()
    }

    override fun moveAnnotation(id: String, to: Coordinate) {
        val state = annotationFeatures[id] ?: return
        annotationFeatures[id] = state.copy(coordinate = to)
        refreshAnnotations()
    }

    override fun removeAnnotation(id: String) {
        annotationFeatures.remove(id)
        refreshAnnotations()
    }

    // --- Annotation scaffolding ---

    private fun ensureAnnotationScaffolding(style: Style) {
        // Push any annotation images that were queued before the style existed.
        for ((id, bmp) in pendingImages) style.addImage(id, bmp)
        pendingImages.clear()
        if (style.getSource(ANNOTATION_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(ANNOTATION_SOURCE_ID, emptyFeatureCollectionJson()))
        }
        if (style.getLayer(ANNOTATION_LAYER_ID) == null) {
            val symbol = SymbolLayer(ANNOTATION_LAYER_ID, ANNOTATION_SOURCE_ID).apply {
                setProperties(
                    PropertyFactory.iconImage(Expression.get("icon-image")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.symbolSortKey(Expression.get("z-priority")),
                )
            }
            style.addLayer(symbol)
        }
        refreshAnnotations()
    }

    private fun refreshAnnotations() {
        val style = styleRef ?: return
        val source = style.getSource(ANNOTATION_SOURCE_ID) as? GeoJsonSource ?: return
        val features = JSONArray()
        for (state in annotationFeatures.values) {
            val feature = JSONObject().apply {
                put("type", "Feature")
                put(
                    "properties",
                    JSONObject().apply {
                        put("icon-image", state.imageId)
                        put("z-priority", state.zPriority)
                    },
                )
                put(
                    "geometry",
                    JSONObject().apply {
                        put("type", "Point")
                        put("coordinates", JSONArray().apply {
                            put(state.coordinate.longitude)
                            put(state.coordinate.latitude)
                        })
                    },
                )
            }
            features.put(feature)
        }
        val fc = JSONObject().apply {
            put("type", "FeatureCollection")
            put("features", features)
        }
        source.setGeoJson(fc.toString())
    }

    // --- Property application ---

    private fun applyProperty(layer: org.maplibre.android.style.layers.Layer, property: String, value: Any?) {
        val converted: PropertyValue<*> = when (property) {
            "line-color" -> PropertyFactory.lineColor(toArgbIfColor(value) ?: expressionOrConst(value))
            "line-width" -> PropertyFactory.lineWidth(expressionOrConst(value))
            "line-opacity" -> PropertyFactory.lineOpacity(expressionOrConst(value))
            "line-gap-width" -> PropertyFactory.lineGapWidth(expressionOrConst(value))
            "line-blur" -> PropertyFactory.lineBlur(expressionOrConst(value))
            "line-cap" -> PropertyFactory.lineCap(value as? String ?: throw ProviderError("line-cap expects String"))
            "line-join" -> PropertyFactory.lineJoin(value as? String ?: throw ProviderError("line-join expects String"))
            "line-dasharray" -> {
                val pattern = (value as? List<*>)?.map { (it as Number).toFloat() }?.toTypedArray()
                    ?: throw ProviderError("line-dasharray expects [Number]")
                PropertyFactory.lineDasharray(pattern)
            }
            "text-field" -> PropertyFactory.textField(expressionOrConst(value))
            "text-font" -> PropertyFactory.textFont(
                (value as? List<*>)?.map { it.toString() }?.toTypedArray()
                    ?: throw ProviderError("text-font expects [String]"),
            )
            "text-size" -> PropertyFactory.textSize(expressionOrConst(value))
            "text-anchor" -> PropertyFactory.textAnchor(value as? String ?: throw ProviderError("text-anchor expects String"))
            "text-offset" -> PropertyFactory.textOffset(
                (value as? List<*>)?.map { (it as Number).toFloat() }?.toTypedArray()
                    ?: throw ProviderError("text-offset expects [Number]"),
            )
            "text-justify" -> PropertyFactory.textJustify(value as? String ?: throw ProviderError("text-justify expects String"))
            "text-color" -> PropertyFactory.textColor(toArgbIfColor(value) ?: expressionOrConst(value))
            "text-halo-color" -> PropertyFactory.textHaloColor(toArgbIfColor(value) ?: expressionOrConst(value))
            "text-halo-width" -> PropertyFactory.textHaloWidth(expressionOrConst(value))
            "symbol-placement" -> PropertyFactory.symbolPlacement(value as? String ?: throw ProviderError("symbol-placement expects String"))
            "icon-allow-overlap" -> PropertyFactory.iconAllowOverlap(value as? Boolean ?: throw ProviderError("icon-allow-overlap expects Boolean"))
            "icon-ignore-placement" -> PropertyFactory.iconIgnorePlacement(value as? Boolean ?: throw ProviderError("icon-ignore-placement expects Boolean"))
            else -> throw ProviderError("unsupported property $property")
        }
        layer.setProperties(converted)
    }

    // --- Expression + JSON helpers ---

    private fun expression(json: List<Any?>): Expression {
        val sanitized = sanitize(json)
        return Expression.Converter.convert(JSONArray(sanitized).toString())
    }

    private fun expressionOrConst(value: Any?): Expression {
        return when (value) {
            is List<*> -> expression(value)
            is Color -> Expression.rgba(
                (value.red * 255).toInt(),
                (value.green * 255).toInt(),
                (value.blue * 255).toInt(),
                value.alpha,
            )
            is Number -> Expression.literal(value.toFloat())
            is Boolean -> Expression.literal(value)
            is String -> Expression.literal(value)
            else -> throw ProviderError("Cannot convert to expression: $value")
        }
    }

    private fun toArgbIfColor(value: Any?): Expression? = (value as? Color)?.let {
        Expression.rgba(
            (it.red * 255).toInt(),
            (it.green * 255).toInt(),
            (it.blue * 255).toInt(),
            it.alpha,
        )
    }

    /**
     * Recursively converts Compose [Color] values inside a JSON expression
     * list to `rgba(r,g,b,a)` strings — MapLibre Android's JSON expression
     * parser expects CSS-style color literals, not an opaque Color object.
     */
    private fun sanitize(list: List<Any?>): List<Any?> {
        return list.map { element ->
            when (element) {
                is Color -> cssString(element)
                is List<*> -> sanitize(element.filterIsInstance<Any?>())
                is Map<*, *> -> element.mapValues { (_, v) ->
                    when (v) {
                        is Color -> cssString(v)
                        is List<*> -> sanitize(v.filterIsInstance<Any?>())
                        else -> v
                    }
                }
                else -> element
            }
        }
    }

    private fun cssString(color: Color): String {
        val r = (color.red * 255).toInt()
        val g = (color.green * 255).toInt()
        val b = (color.blue * 255).toInt()
        val a = color.alpha
        return "rgba($r, $g, $b, $a)"
    }

    /**
     * Serializes a Kotlin `Map<String, Any?>` / `List<Any?>` structure (with
     * Compose Colors already sanitized) into a JSON string MapLibre's
     * GeoJsonSource / Expression parser can consume.
     */
    private fun jsonString(value: Any?): String {
        val sanitized = when (value) {
            is List<*> -> sanitize(value.filterIsInstance<Any?>())
            is Map<*, *> -> sanitize(listOf(value)).first()
            else -> value
        }
        return when (sanitized) {
            null -> emptyFeatureCollectionJson()
            is String -> sanitized
            is Map<*, *> -> JSONObject(sanitized as Map<String, Any?>).toString()
            is List<*> -> JSONArray(sanitized).toString()
            else -> sanitized.toString()
        }
    }

    private fun emptyFeatureCollectionJson(): String =
        """{"type":"FeatureCollection","features":[]}"""

    private data class AnnotationState(
        val id: String,
        val imageId: String,
        val coordinate: Coordinate,
        val anchor: PointF,
        val zPriority: Int,
    )

    class ProviderError(message: String) : Exception(message)

    companion object {
        private const val ANNOTATION_SOURCE_ID = "backroadstatus-annotations-src"
        private const val ANNOTATION_LAYER_ID = "backroadstatus-annotations"
    }
}
