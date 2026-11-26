package com.backroadstatus.sdk.mapbox

import android.graphics.PointF
import androidx.compose.ui.graphics.Color
import com.backroadstatus.sdk.mapkit.Annotation
import com.backroadstatus.sdk.mapkit.BoundingBox
import com.backroadstatus.sdk.mapkit.Coordinate
import com.backroadstatus.sdk.mapkit.MapEdgeInsets
import com.backroadstatus.sdk.mapkit.MapProvider
import com.mapbox.bindgen.Value
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.CoordinateBounds
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotation
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.gestures.gestures
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject

/**
 * Concrete [MapProvider] over Mapbox Maps SDK v11 for Android.
 *
 * Sources, layers, and property updates all flow through Mapbox's
 * JSON-based style APIs rather than the typed layer DSL. Mapbox natively
 * consumes the MapLibre Style Spec JSON the composer speaks, so the
 * adapter stays thin — just coerce Compose [Color] values embedded in
 * expression arrays into CSS strings (Mapbox's JSON serializer, like
 * MapLibre's, can't handle a Color object inside an expression).
 */
class MapboxMapProvider(
    private val mapView: MapView,
    private val mapboxMap: MapboxMap,
) : MapProvider {

    private var style: Style? = null
    private var styleLoaded = false
    private var pointManager: PointAnnotationManager? = null
    private val annotations: MutableMap<String, PointAnnotation> = linkedMapOf()

    override var onStyleLoaded: (() -> Unit)? = null
    override var onTap: ((Coordinate) -> Unit)? = null

    init {
        mapView.gestures.addOnMapClickListener { point ->
            onTap?.invoke(Coordinate(longitude = point.longitude(), latitude = point.latitude()))
            true
        }
    }

    // --- Style lifecycle ---

    override suspend fun loadStyle(url: String) {
        val deferred = CompletableDeferred<Unit>()
        styleLoaded = false
        mapboxMap.loadStyle(url) { s ->
            style = s
            styleLoaded = true
            onStyleLoaded?.invoke()
            deferred.complete(Unit)
        }
        deferred.await()
    }

    override val isStyleReady: Boolean get() = styleLoaded && style != null

    // --- Sources ---

    override fun addSource(id: String, spec: Map<String, Any?>) {
        val style = this.style ?: throw ProviderError("style not ready")
        if (style.styleSourceExists(id)) return
        val value = Value.fromJson(JSONObject(sanitize(spec) as Map<String, Any?>).toString())
            .value ?: throw ProviderError("invalid source spec JSON")
        style.addStyleSource(id, value).error?.let { throw ProviderError("addSource($id): $it") }
    }

    override fun setSourceData(id: String, geoJson: Map<String, Any?>) {
        val style = this.style ?: throw ProviderError("style not ready")
        if (!style.styleSourceExists(id)) throw ProviderError("source $id missing")
        val json = JSONObject(sanitize(geoJson) as Map<String, Any?>).toString()
        val value = Value.fromJson(json).value ?: throw ProviderError("invalid geoJSON")
        style.setStyleSourceProperty(id, "data", value).error?.let { throw ProviderError("setSourceData($id): $it") }
    }

    override fun sourceExists(id: String): Boolean = style?.styleSourceExists(id) == true

    // --- Layers ---

    override fun addLayer(spec: Map<String, Any?>, below: String?) {
        val style = this.style ?: throw ProviderError("style not ready")
        val id = spec["id"] as? String ?: throw ProviderError("layer spec missing id")
        if (style.styleLayerExists(id)) return
        val sanitized = sanitize(spec) as Map<String, Any?>
        val value = Value.fromJson(JSONObject(sanitized).toString()).value
            ?: throw ProviderError("invalid layer spec JSON")
        val position = if (below != null && style.styleLayerExists(below))
            com.mapbox.maps.LayerPosition(/* above = */ null, /* below = */ below, /* at = */ null)
        else com.mapbox.maps.LayerPosition(null, null, null)
        style.addStyleLayer(value, position).error?.let { throw ProviderError("addLayer($id): $it") }
    }

    override fun removeLayer(id: String) {
        val style = this.style ?: return
        if (!style.styleLayerExists(id)) return
        style.removeStyleLayer(id).error?.let { throw ProviderError("removeLayer($id): $it") }
    }

    override fun layerExists(id: String): Boolean = style?.styleLayerExists(id) == true

    override fun setLayerProperty(layer: String, property: String, value: Any) {
        val style = this.style ?: throw ProviderError("style not ready")
        if (!style.styleLayerExists(layer)) throw ProviderError("layer $layer missing")
        val mapboxValue = toValue(value) ?: throw ProviderError("cannot serialize $property")
        style.setStyleLayerProperty(layer, property, mapboxValue).error?.let {
            throw ProviderError("setLayerProperty($layer, $property): $it")
        }
    }

    override fun setLayerFilter(layer: String, filter: List<Any?>) {
        val style = this.style ?: throw ProviderError("style not ready")
        if (!style.styleLayerExists(layer)) throw ProviderError("layer $layer missing")
        val mapboxValue = toValue(filter) ?: throw ProviderError("cannot serialize filter")
        style.setStyleLayerProperty(layer, "filter", mapboxValue).error?.let {
            throw ProviderError("setLayerFilter($layer): $it")
        }
    }

    override fun addFilterCondition(layer: String, filter: List<Any?>) {
        val style = this.style ?: throw ProviderError("style not ready")
        if (!style.styleLayerExists(layer)) throw ProviderError("layer $layer missing")
        val sanitized = sanitize(filter) as List<Any?>
        val existingValue = style.getStyleLayerProperty(layer, "filter").value
        val existingJson = runCatching { existingValue?.toJson() }.getOrNull()
        val combined: List<Any?> = if (!existingJson.isNullOrEmpty() && existingJson != "[]") {
            val arr = JSONArray(existingJson)
            val existingList = (0 until arr.length()).map { arr.get(it) }
            listOf("all", existingList, sanitized)
        } else sanitized
        val mapboxValue = toValue(combined) ?: throw ProviderError("cannot serialize combined filter")
        style.setStyleLayerProperty(layer, "filter", mapboxValue).error?.let {
            throw ProviderError("addFilterCondition($layer): $it")
        }
    }

    // --- Viewport ---

    override val zoom: Double get() = mapboxMap.cameraState.zoom
    override fun setZoom(zoom: Double, animated: Boolean) {
        val cam = CameraOptions.Builder().zoom(zoom).build()
        if (animated) mapView.camera.easeTo(cam, MapAnimationOptions.mapAnimationOptions { duration(300) })
        else mapboxMap.setCamera(cam)
    }

    override fun fit(bounds: BoundingBox, padding: MapEdgeInsets, animated: Boolean) {
        val mbxBounds = CoordinateBounds(
            com.mapbox.geojson.Point.fromLngLat(bounds.sw.longitude, bounds.sw.latitude),
            com.mapbox.geojson.Point.fromLngLat(bounds.ne.longitude, bounds.ne.latitude),
        )
        val insets = EdgeInsets(padding.top, padding.left, padding.bottom, padding.right)
        val cam = mapboxMap.cameraForCoordinateBounds(mbxBounds, insets)
        if (animated) mapView.camera.easeTo(cam, MapAnimationOptions.mapAnimationOptions { duration(300) })
        else mapboxMap.setCamera(cam)
    }

    // --- Annotations ---

    override fun addAnnotation(annotation: Annotation) {
        val manager = pointManager ?: run {
            val m = mapView.annotations.createPointAnnotationManager()
            pointManager = m
            m
        }
        val style = this.style
        val imageId = "backroadstatus.${annotation.id}"
        style?.addImage(imageId, annotation.image)

        val existing = annotations[annotation.id]
        if (existing != null) manager.delete(existing)

        val options = PointAnnotationOptions()
            .withPoint(com.mapbox.geojson.Point.fromLngLat(annotation.coordinate.longitude, annotation.coordinate.latitude))
            .withIconImage(imageId)
            .withSymbolSortKey(annotation.zPriority.toDouble())
            .withIconAnchor(iconAnchor(annotation.anchor))
        val created = manager.create(options)
        annotations[annotation.id] = created
    }

    override fun moveAnnotation(id: String, to: Coordinate) {
        val manager = pointManager ?: return
        val existing = annotations[id] ?: return
        existing.point = com.mapbox.geojson.Point.fromLngLat(to.longitude, to.latitude)
        manager.update(existing)
    }

    override fun removeAnnotation(id: String) {
        val manager = pointManager ?: return
        annotations.remove(id)?.let { manager.delete(it) }
    }

    private fun iconAnchor(anchor: PointF): com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor {
        val a = com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
        return when {
            anchor.x == 0.5f && anchor.y == 0.5f -> a.CENTER
            anchor.x == 0.5f && anchor.y == 0.0f -> a.TOP
            anchor.x == 0.5f && anchor.y == 1.0f -> a.BOTTOM
            anchor.x == 0.0f && anchor.y == 0.5f -> a.LEFT
            anchor.x == 1.0f && anchor.y == 0.5f -> a.RIGHT
            anchor.x == 0.0f && anchor.y == 0.0f -> a.TOP_LEFT
            anchor.x == 1.0f && anchor.y == 0.0f -> a.TOP_RIGHT
            anchor.x == 0.0f && anchor.y == 1.0f -> a.BOTTOM_LEFT
            anchor.x == 1.0f && anchor.y == 1.0f -> a.BOTTOM_RIGHT
            else -> a.CENTER
        }
    }

    // --- Sanitization / conversion ---

    private fun sanitize(value: Any?): Any? = when (value) {
        is Color -> cssString(value)
        is List<*> -> value.map { sanitize(it) }
        is Map<*, *> -> value.mapValues { (_, v) -> sanitize(v) }
        else -> value
    }

    private fun cssString(color: Color): String {
        val r = (color.red * 255).toInt()
        val g = (color.green * 255).toInt()
        val b = (color.blue * 255).toInt()
        val a = color.alpha
        return "rgba($r, $g, $b, $a)"
    }

    private fun toValue(raw: Any): Value? {
        val sanitized = sanitize(raw)
        val json: String = when (sanitized) {
            is String -> JSONObject().put("_", sanitized).let { "\"${sanitized.replace("\"", "\\\"")}\"" }
            is Number -> sanitized.toString()
            is Boolean -> sanitized.toString()
            is List<*> -> JSONArray(sanitized).toString()
            is Map<*, *> -> JSONObject(sanitized as Map<String, Any?>).toString()
            else -> return null
        }
        return Value.fromJson(json).value
    }

    class ProviderError(message: String) : Exception(message)
}
