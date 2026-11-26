package com.backroadstatus.sdk.mapbox

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Ensures a Mapbox style has a `glyphs` URL set so text-based symbol
 * layers (the distance-marker overlay the composer installs) render
 * regardless of what the consumer's style ships.
 *
 * On [prepare]:
 *   - If the style already has a `glyphs` field → return the original URL
 *     unchanged.
 *   - Otherwise → fetch the style JSON, inject a `glyphs` URL pointing at
 *     the SDK's bundled Noto Sans Regular PBFs (same approach as the
 *     MapLibre adapter), write the modified JSON to a temp file, and
 *     return a `file://` URL for it.
 *
 * Using the bundled PBFs instead of `mapbox://fonts/mapbox/...` sidesteps
 * any uncertainty about whether `Noto Sans Regular` is in the Mapbox
 * account's default fontstack library.
 */
object MapboxStylePreparation {
    private const val TAG = "BackroadStatus.Mapbox"
    const val bundledFontstack = "Noto Sans Regular"

    private val http: OkHttpClient by lazy { OkHttpClient.Builder().build() }

    fun prepare(context: Context, originalStyleUrl: String): String {
        return try {
            if (originalStyleUrl.startsWith("mapbox://")) {
                // Mapbox-hosted styles can't be rewritten client-side; let them render as-is.
                return originalStyleUrl
            }
            val fontsRoot = extractBundledFonts(context) ?: return originalStyleUrl
            val body = http.newCall(Request.Builder().url(originalStyleUrl).build()).execute().use {
                it.body?.string().orEmpty()
            }
            if (body.isEmpty()) return originalStyleUrl
            val style = JSONObject(body)
            if (style.opt("glyphs") is String) return originalStyleUrl
            style.put("glyphs", "file://$fontsRoot/{fontstack}/{range}.pbf")
            val tempFile = File(context.cacheDir, "backroadstatus-mapbox-style-${UUID.randomUUID()}.json")
            FileOutputStream(tempFile).use { it.write(style.toString().toByteArray(Charsets.UTF_8)) }
            "file://${tempFile.absolutePath}"
        } catch (t: Throwable) {
            Log.w(TAG, "Style glyphs injection failed (${t.message}); using original style URL")
            originalStyleUrl
        }
    }

    private fun extractBundledFonts(context: Context): String? {
        val assets = context.assets
        val rootName = "Fonts"
        val entries = try { assets.list(rootName).orEmpty() } catch (_: Throwable) { return null }
        if (entries.isEmpty()) return null
        val outDir = File(context.cacheDir, "backroadstatus-mapbox/$rootName")
        outDir.mkdirs()
        for (fontstack in entries) {
            val fontDir = File(outDir, fontstack).apply { mkdirs() }
            val ranges = try { assets.list("$rootName/$fontstack").orEmpty() } catch (_: Throwable) { continue }
            for (range in ranges) {
                val outFile = File(fontDir, range)
                if (outFile.exists()) continue
                try {
                    assets.open("$rootName/$fontstack/$range").use { input ->
                        FileOutputStream(outFile).use { output -> input.copyTo(output) }
                    }
                } catch (_: Throwable) {}
            }
        }
        return outDir.absolutePath
    }
}
