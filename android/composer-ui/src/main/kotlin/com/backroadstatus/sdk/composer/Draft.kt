package com.backroadstatus.sdk.composer

import android.content.Context
import android.util.Log
import com.backroadstatus.sdk.core.RoadDetail
import com.backroadstatus.sdk.core.VehicleCategory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-photo metadata persisted with a draft. The bytes live in a sidecar
 * file at `DraftPhotos/<id>.jpg` so the draft JSON stays small and cheap
 * to rewrite on every keystroke. [reference] is populated when the photo
 * has made it up to the server; null means it still needs uploading.
 */
@Serializable
data class DraftPhotoMeta(
    val id: String,
    val caption: String,
    val reference: String? = null,
)

/**
 * Snapshot of composer state persisted so the user can close the app in
 * the middle of writing a report (offline-while-driving, interruption,
 * plain forgetfulness) and pick up right where they left off.
 */
@Serializable
data class Draft(
    /**
     * SDK-internal draft id. Separate from [clientSubmissionId] which is
     * what the server dedupes on; keeping them distinct lets us iterate
     * the draft without rotating the idempotency key.
     */
    val id: String,
    /**
     * Stable across the draft's lifetime so retries after Submit always
     * dedupe on the server, even across app relaunches.
     */
    val clientSubmissionId: String,
    val roadId: String,
    val roadName: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** Epoch milliseconds for the report date. */
    val reportDate: Long,
    val description: String,
    val startStep: Float,
    val conditions: List<ConditionEntry>,
    val verifyMode: Boolean,
    val verifyValues: List<Float>,
    val featureStatuses: Map<String, FeatureStatus>,
    val featureChangeTypes: Map<String, String>,
    val featureChangeComments: Map<String, String>,
    val featureRemoved: Map<String, Boolean>,
    val createdFeatures: List<CreatedFeature>,
    /**
     * Vehicle categories the user ticked under "Modes of Travel". Optional
     * on the API side; composer caps selection at 3.
     */
    val vehicles: List<VehicleCategory> = emptyList(),
    /**
     * Photos attached to the draft. Bytes live at `DraftPhotos/<id>.jpg`;
     * the meta here carries id + caption + the upload reference once the
     * server has it.
     */
    val photos: List<DraftPhotoMeta> = emptyList(),
    /**
     * Snapshot of the road detail at the time the draft was last saved.
     * Lets the composer render the map and populate the condition slider
     * without hitting `/paths/{id}` — critical for offline resume where
     * the user zoomed over, tapped a road, and saved a draft before
     * losing signal. A live fetch still runs in the background once
     * online and upgrades this cache if the geometry gets richer.
     */
    val cachedRoad: RoadDetail? = null,
)

/**
 * Single-draft store backed by a single JSON file in app internal storage.
 *
 * The SDK enforces "one draft at a time" so the composer doesn't have to
 * juggle a list — a draft either exists or it doesn't. If the user tries
 * to start a report on a road that doesn't match the existing draft, the
 * composer prompts them (handled in `ReportComposerScreen`, not here).
 */
class DraftStore internal constructor(
    private val storageFile: File,
    private val photosDirectory: File,
) {
    @Volatile
    private var _currentDraft: Draft? = null
    val currentDraft: Draft? get() = _currentDraft

    init {
        storageFile.parentFile?.mkdirs()
        _currentDraft = loadFromDisk(storageFile)
    }

    fun save(draft: Draft) {
        val updated = draft.copy(updatedAt = System.currentTimeMillis())
        try {
            val encoded = json.encodeToString(Draft.serializer(), updated)
            storageFile.writeText(encoded, Charsets.UTF_8)
            _currentDraft = updated
        } catch (t: Throwable) {
            Log.w("BackroadStatus", "Failed to persist draft: ${t.message}")
        }
    }

    fun discard() {
        storageFile.delete()
        photosDirectory.deleteRecursively()
        _currentDraft = null
    }

    // Photo bytes

    fun writePhoto(id: String, data: ByteArray) {
        photosDirectory.mkdirs()
        try {
            File(photosDirectory, "$id.jpg").writeBytes(data)
        } catch (t: Throwable) {
            Log.w("BackroadStatus", "Failed to persist draft photo $id: ${t.message}")
        }
    }

    fun readPhoto(id: String): ByteArray? {
        val f = File(photosDirectory, "$id.jpg")
        return if (f.exists()) f.readBytes() else null
    }

    fun removePhoto(id: String) {
        File(photosDirectory, "$id.jpg").delete()
    }

    /**
     * Prunes photo files that are no longer referenced by the given set of
     * ids. Called after each draft save so photos the user removed don't
     * linger on disk.
     */
    fun pruneOrphanPhotos(keeping: Set<String>) {
        val contents = photosDirectory.listFiles() ?: return
        for (f in contents) {
            val stem = f.nameWithoutExtension
            if (stem !in keeping) f.delete()
        }
    }

    internal fun photosDir(): File = photosDirectory

    companion object {
        internal val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        private var instance: DraftStore? = null
        private val initMutex = Mutex()

        suspend fun shared(context: Context): DraftStore {
            instance?.let { return it }
            return initMutex.withLock {
                instance?.let { return@withLock it }
                val base = File(context.applicationContext.filesDir, "BackroadStatus")
                val store = DraftStore(
                    storageFile = File(base, "draft.json"),
                    photosDirectory = File(base, "DraftPhotos"),
                )
                instance = store
                store
            }
        }

        fun sharedOrNull(): DraftStore? = instance

        /**
         * Non-blocking synchronous peek at the on-disk draft (if any). Lets
         * the composer decide on its initial render whether to surface the
         * resume alert — the file read is just a few hundred bytes of JSON.
         */
        fun peekDraftOnDisk(context: Context): Draft? {
            val f = File(context.applicationContext.filesDir, "BackroadStatus/draft.json")
            if (!f.exists()) return null
            return loadFromDisk(f)
        }

        private fun loadFromDisk(file: File): Draft? {
            if (!file.exists()) return null
            return try {
                json.decodeFromString(Draft.serializer(), file.readText(Charsets.UTF_8))
            } catch (_: Throwable) {
                null
            }
        }
    }
}
