package com.backroadstatus.sdk.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Disk cache of canonical [RoadDetail]s keyed by road id. Populated by the
 * bulk geometry downloader during offline-region preparation and consulted
 * by the composer before it falls back to [BackroadStatus.road].
 *
 * Storage format is one JSON file per road under
 * `filesDir/BackroadStatus/geometry/<id>.json`. File-per-road keeps writes
 * independent (no lock contention between parallel tile downloads
 * unpacking the same file) and makes manual inspection easy while the API
 * shape is still settling. Android's `filesDir` is per-app internal storage
 * — not backed up unless the host app opts into Auto Backup for it.
 */
class GeometryStore private constructor(private val root: File) {

    private val rwLock = ReentrantReadWriteLock()

    fun road(id: String): RoadDetail? = rwLock.read {
        val f = fileFor(id)
        if (!f.exists()) return null
        return try {
            json.decodeFromString(RoadDetail.serializer(), f.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            Log.w("BackroadStatus", "GeometryStore: decode failed for $id: ${t.message}")
            null
        }
    }

    fun upsert(road: RoadDetail) = rwLock.write {
        val out = fileFor(road.id)
        try {
            val encoded = json.encodeToString(RoadDetail.serializer(), road)
            out.writeText(encoded, Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w("BackroadStatus", "GeometryStore: write failed for ${road.id}: ${t.message}")
        }
    }

    fun remove(id: String) = rwLock.write {
        fileFor(id).delete()
    }

    /**
     * Wipes the entire cache. Intended for "Clear offline data"-style user
     * actions; not called automatically on pack removal (packs can overlap,
     * and a road might still be in another pack).
     */
    fun clear() = rwLock.write {
        root.deleteRecursively()
        root.mkdirs()
    }

    fun cachedRoadCount(): Int = rwLock.read {
        root.listFiles()?.size ?: 0
    }

    private fun fileFor(id: String): File = File(root, "${idSafe(id)}.json")

    /** Road ids are server-controlled; sanitize defensively. */
    private fun idSafe(id: String): String = id
        .replace('/', '_')
        .replace(':', '_')

    companion object {
        private val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        private var instance: GeometryStore? = null
        private val initMutex = Mutex()

        /**
         * Returns the process-wide [GeometryStore], initialized on first use.
         * Call with `context` once before invoking read/write; subsequent
         * calls can pass the same context or any context (only used for
         * `filesDir`, cached after first init).
         */
        suspend fun shared(context: Context): GeometryStore {
            instance?.let { return it }
            return initMutex.withLock {
                instance?.let { return@withLock it }
                val base = File(context.applicationContext.filesDir, "BackroadStatus/geometry")
                base.mkdirs()
                val store = GeometryStore(base)
                instance = store
                store
            }
        }

        /** Fast-path for already-initialized consumers. Returns null if not set up yet. */
        fun sharedOrNull(): GeometryStore? = instance
    }
}
