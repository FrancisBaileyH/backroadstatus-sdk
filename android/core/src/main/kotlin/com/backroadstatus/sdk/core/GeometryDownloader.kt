package com.backroadstatus.sdk.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow

/**
 * Fetches canonical road geometry for every tile inside an offline region's
 * bbox and stuffs each road into [GeometryStore] keyed by road id.
 *
 * Download knobs:
 *   - [zoom] — slippy-map zoom for the bulk endpoint. z11 is the default:
 *     typical offline packs cover a single-digit number of tiles at z11 and
 *     most backroads live inside one tile, so redundant copies across tiles
 *     stay low.
 *   - [maxInFlight] — concurrent requests cap. The tile server rate-limits
 *     the bulk endpoint at 10 rps; staying a touch under lets natural
 *     request latency smooth out bursts without tripping the limit.
 *
 * Uses its own [OkHttpClient] so it doesn't compete with the host app's
 * map-SDK HTTP client for connections.
 */
class GeometryDownloader {
    data class Progress(
        val completedTiles: Int,
        val totalTiles: Int,
        /**
         * Tiles whose fetch raised an error (auth, 404, exhausted retries).
         * Non-zero at `completedTiles == totalTiles` means the cache is
         * incomplete; surface this to the user so they can retry.
         */
        val failedTiles: Int,
    ) {
        val fraction: Double get() = if (totalTiles > 0) completedTiles.toDouble() / totalTiles else 0.0
    }

    private val api: ApiClient = ApiClient(
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = defaultMaxInFlight })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build(),
    )

    /**
     * Fetches geometry for every tile in [bounds] at [zoom], writing each
     * returned road into [GeometryStore].
     *
     * [onProgress] fires on an unspecified dispatcher after each tile
     * completes; hop to Main before updating UI.
     */
    suspend fun download(
        context: Context,
        bounds: GeographicBounds,
        zoom: Int = defaultZoom,
        maxInFlight: Int = defaultMaxInFlight,
        onProgress: (Progress) -> Unit = {},
    ) {
        val config = BackroadStatus.shared.requireConfig()
        val tokenProvider = BackroadStatus.shared.tokenProvider(context)

        val tiles = TileMath.tiles(bounds, zoom)
        val total = tiles.size
        if (total == 0) {
            onProgress(Progress(0, 0, 0))
            return
        }

        val store = GeometryStore.shared(context)
        val completed = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val semaphore = Semaphore(maxInFlight)

        coroutineScope {
            tiles.map { tile ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        var tileFailed = false
                        try {
                            val roads = fetchTile(api, config.issuer, tokenProvider, tile)
                            for (road in roads) store.upsert(road)
                        } catch (t: Throwable) {
                            // Swallow per-tile errors so one bad tile doesn't
                            // abort the whole pack. Log, bump the fail counter
                            // so the caller can surface an aggregate.
                            Log.w(
                                "BackroadStatus",
                                "GeometryDownloader: tile z${tile.z}/${tile.x}/${tile.y} failed: ${t.message}",
                            )
                            tileFailed = true
                        }
                        val done = completed.incrementAndGet()
                        val fail = if (tileFailed) failed.incrementAndGet() else failed.get()
                        onProgress(Progress(done, total, fail))
                    }
                }
            }.awaitAll()
        }
    }

    companion object {
        const val defaultZoom: Int = 11
        const val defaultMaxInFlight: Int = 8

        /** Simple exponential backoff on 429/5xx. Max 3 attempts, 500ms base. */
        private suspend fun fetchTile(
            api: ApiClient,
            issuer: String,
            tokenProvider: TokenProvider,
            tile: TileMath.TileCoord,
        ): List<RoadDetail> {
            var attempt = 0
            while (true) {
                try {
                    return api.bulkPaths(issuer, tokenProvider, tile.z, tile.x, tile.y)
                } catch (e: BackroadStatusError.ApiError) {
                    val retryable = e.status == 429 || e.status in 500..599
                    if (!retryable || attempt >= 3) throw e
                    attempt += 1
                    val backoffMs = (500.0 * 2.0.pow(attempt - 1)).toLong()
                    delay(backoffMs)
                }
            }
        }
    }
}
