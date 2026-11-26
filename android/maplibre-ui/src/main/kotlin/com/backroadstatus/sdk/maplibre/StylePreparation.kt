package com.backroadstatus.sdk.maplibre

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Ensures a MapLibre style has a `glyphs` URL set so text-based symbol
 * layers (the distance-marker overlay the composer installs) render
 * regardless of what the consumer's style ships.
 *
 * On [prepare]:
 *   - If the style already has a `glyphs` field → return the original URL
 *     unchanged.
 *   - Otherwise → fetch the style JSON, inject a glyphs URL pointing at
 *     the SDK's bundled Noto Sans Regular PBFs (served out of the
 *     composer-ui module's assets), write the modified JSON to a temp
 *     file, and return a `file://` URL for it.
 *
 * Failures at any step fall back to the original URL so the map still
 * loads — only distance-marker text is sacrificed.
 */
object StylePreparation {
    private const val TAG = "BackroadStatus"
    const val bundledFontstack = "Noto Sans Regular"

    private val http: OkHttpClient by lazy { OkHttpClient.Builder().build() }

    fun prepare(context: Context, originalStyleUrl: String): String {
        return try {
            val fontsRootAsset = extractBundledFonts(context) ?: return originalStyleUrl

            val body = readStyleBody(context, originalStyleUrl)
            if (body.isNullOrEmpty()) return originalStyleUrl

            val style = JSONObject(body)
            if (style.opt("glyphs") is String) return originalStyleUrl

            style.put("glyphs", "file://$fontsRootAsset/{fontstack}/{range}.pbf")
            val tempFile = File(context.cacheDir, "backroadstatus-style-${UUID.randomUUID()}.json")
            FileOutputStream(tempFile).use { it.write(style.toString().toByteArray(Charsets.UTF_8)) }
            "file://${tempFile.absolutePath}"
        } catch (t: Throwable) {
            Log.w(TAG, "Style glyphs injection failed (${t.message}); using original style URL")
            originalStyleUrl
        }
    }

    private fun readStyleBody(context: Context, url: String): String? = when {
        url.startsWith("asset://") ->
            context.assets.open(url.removePrefix("asset://")).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        url.startsWith("file://") ->
            File(url.removePrefix("file://")).readText(Charsets.UTF_8)
        else ->
            http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() }
    }

    /**
     * Copies the bundled font assets out of the composer-ui module's `Fonts/`
     * asset tree into app cache so MapLibre can read them via `file://`.
     * Returns the absolute directory path, or null if the copy failed.
     *
     * This is a workaround for MapLibre Android not supporting `asset://`
     * URIs in the `glyphs` field; it DOES support `file://`.
     */
    private fun extractBundledFonts(context: Context): String? {
        val assets = context.assets
        val rootName = "Fonts"
        val entries = try {
            assets.list(rootName).orEmpty()
        } catch (_: Throwable) {
            return null
        }
        if (entries.isEmpty()) return null

        val outDir = File(context.cacheDir, "backroadstatus/$rootName")
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
                } catch (_: Throwable) {
                    // Silently skip missing ranges — composer only ever requests one.
                }
            }
        }
        return outDir.absolutePath
    }
}
