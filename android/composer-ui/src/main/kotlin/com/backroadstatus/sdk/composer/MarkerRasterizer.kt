package com.backroadstatus.sdk.composer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.caverock.androidsvg.SVG
import kotlin.math.roundToInt

/**
 * Rasterizes the composer's marker visuals to [Bitmap] so the
 * [com.backroadstatus.sdk.mapkit.MapProvider.addAnnotation] path can carry
 * them cross-SDK. Doing the render here (instead of per-adapter) means the
 * two map backends host pixel-identical markers.
 *
 * Caches by variant key so rapid slider drags reuse the same bitmap.
 */
object MarkerRasterizer {

    private val bitmapCache: MutableMap<String, Bitmap> = HashMap()
    private val svgCache: MutableMap<String, SVG> = HashMap()

    /**
     * Small colored dot with white border + soft drop shadow — mirrors the
     * web composer's `maplibregl.Marker({ color, scale: 0.6 })`.
     */
    fun sliderDot(density: Float, color: Color, diameterDp: Float = 16f): Bitmap {
        val diameter = diameterDp * density
        val key = "slider-${color.toArgb().toUInt().toString(16)}-${diameter.roundToInt()}"
        bitmapCache[key]?.let { return it }

        val padding = 4f * density
        val total = (diameter + padding * 2).roundToInt()
        val bitmap = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(padding, padding, padding + diameter, padding + diameter)

        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = (0x4D000000).toInt()
            maskFilter = BlurMaskFilter(2f * density, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.save()
        canvas.translate(0f, 1f * density)
        canvas.drawOval(rect, shadow)
        canvas.restore()

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() }
        canvas.drawOval(rect, fill)

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            this.color = android.graphics.Color.WHITE
        }
        val inset = 1f * density
        canvas.drawOval(
            RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset),
            stroke,
        )

        bitmapCache[key] = bitmap
        return bitmap
    }

    /**
     * Pre-renders the bundled feature-type SVG into a bitmap at the
     * composer's standard marker size.
     */
    fun featureIcon(
        context: Context,
        typeName: String,
        sizeDp: Float = 32f,
    ): Bitmap? {
        val density = context.resources.displayMetrics.density
        val sizePx = (sizeDp * density).roundToInt()
        val key = "feature-$typeName-$sizePx"
        bitmapCache[key]?.let { return it }

        val svg = loadSvg(context, typeName) ?: return null

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Preserve the SVG's native aspect ratio; AndroidSVG scales to fit.
        svg.documentWidth = sizePx.toFloat()
        svg.documentHeight = sizePx.toFloat()
        svg.renderToCanvas(canvas)

        bitmapCache[key] = bitmap
        return bitmap
    }

    /**
     * Returns the parsed [SVG] for a given bundled feature type, caching
     * for reuse. Used by both bitmap rasterization and the inline
     * `FeatureSvgImage` composable.
     */
    fun svg(context: Context, typeName: String): SVG? = loadSvg(context, typeName)

    private fun loadSvg(context: Context, typeName: String): SVG? {
        svgCache[typeName]?.let { return it }
        val path = FeatureIcon.assetPath(typeName) ?: return null
        return try {
            context.applicationContext.assets.open(path).use { stream ->
                val parsed = SVG.getFromInputStream(stream)
                svgCache[typeName] = parsed
                parsed
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Clears cached bitmaps/SVGs — call on memory pressure. */
    fun clearCache() {
        bitmapCache.clear()
        svgCache.clear()
    }
}
