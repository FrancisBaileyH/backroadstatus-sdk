package com.backroadstatus.sdk.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Lifecycle of a persisted submission. [queued] is transient — the queue
 * retries it automatically on connectivity flips. [failed] is parked —
 * something about the submission itself (a 400, a malformed field) means
 * retrying unchanged will fail again, so the user has to either edit it,
 * retry manually, or discard it.
 */
@Serializable
enum class SubmissionState { queued, failed }

/**
 * Snapshot of a persisted bulletin submission that hasn't been successfully
 * delivered to the server yet. Everything a UI needs to render the entry
 * without reaching into the queue's internals.
 */
data class PendingSubmission(
    val id: String,
    val clientSubmissionId: String,
    val pathId: String,
    val roadName: String?,
    val createdAt: Long,
    val lastError: String?,
    /**
     * Photos still waiting on a reference. 0 means the queue is only
     * waiting on `createBulletin`; > 0 means uploads are outstanding too.
     */
    val pendingPhotoCount: Int,
    val state: SubmissionState,
    /**
     * True when the entry carries a composer-draft blob the caller can
     * decode and prefill a composer with. False means the entry predates
     * the draft-snapshot change.
     */
    val hasDraftSnapshot: Boolean,
)

/**
 * Public DTO returned by [SubmissionQueue.takeForEdit]. Everything a
 * caller needs to resume the composer in edit mode: the opaque draft blob
 * it previously stored, plus the `clientSubmissionId` so the next Submit
 * still dedupes server-side.
 */
data class SubmissionForEdit(
    val id: String,
    val clientSubmissionId: String,
    val pathId: String,
    val roadName: String?,
    val draftSnapshot: ByteArray?,
    /**
     * Opaque blob the caller can hand to [SubmissionQueue.restore] to put
     * the original queue entry back exactly as it was. Lets the composer
     * offer a "Revert" affordance for edits the user decides to back out of.
     */
    val restorationPayload: ByteArray,
)

/**
 * A photo the composer is handing off to the submission queue. If
 * [reference] is set, the photo is already uploaded and the queue just
 * needs to include it in the bulletin body. Otherwise the queue will
 * upload the bytes first (and persist them so retries after app kill
 * still work).
 */
data class PendingPhoto(
    val id: String = UUID.randomUUID().toString(),
    val data: ByteArray,
    val caption: String,
    val reference: String? = null,
)

/** Outcome of a [SubmissionQueue.submit] call. */
sealed class SubmissionOutcome {
    /** Request landed on the server. Nothing is on the queue for this submission. */
    data class Completed(val bulletin: Bulletin) : SubmissionOutcome()

    /**
     * Network trouble (or similar retryable error) — the submission and any
     * pending photo bytes were stashed on disk and will retry automatically
     * once connectivity returns. Non-retryable failures do NOT return this
     * case; they throw and the entry is parked on disk as [SubmissionState.failed].
     */
    data class Queued(val pending: PendingSubmission) : SubmissionOutcome()
}

/**
 * On-disk retry queue for bulletin submissions. Pair with the server's
 * `clientSubmissionId` idempotency key so retries after a network blip, an
 * app background/kill, or a bad-signal road trip never create duplicate
 * bulletins.
 */
class SubmissionQueue internal constructor(
    private val context: Context,
    private val storageDir: File,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var isRetryingAll = false

    private val _pending = MutableStateFlow<List<PendingSubmission>>(emptyList())
    val pending: StateFlow<List<PendingSubmission>> = _pending.asStateFlow()

    private val _isNetworkAvailable = MutableStateFlow(true)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback: ConnectivityManager.NetworkCallback

    init {
        storageDir.mkdirs()
        reloadPending()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val becameOnline = !_isNetworkAvailable.value
                _isNetworkAvailable.value = true
                if (becameOnline && _pending.value.isNotEmpty()) {
                    Log.i(
                        "BackroadStatus",
                        "Connectivity restored — retrying ${_pending.value.size} pending submission(s)",
                    )
                    scope.launch { retryAll() }
                }
            }

            override fun onLost(network: Network) {
                _isNetworkAvailable.value = false
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    // MARK: Public API

    suspend fun submit(
        input: CreateBulletinInput,
        photos: List<PendingPhoto> = emptyList(),
        roadName: String? = null,
        draftSnapshot: ByteArray? = null,
    ): SubmissionOutcome {
        val entry = StoredSubmission(
            id = UUID.randomUUID().toString(),
            input = input,
            queuedPhotos = photos.map { QueuedPhoto(it.id, it.caption, it.reference) },
            roadName = roadName,
            createdAt = System.currentTimeMillis(),
            lastError = null,
            state = SubmissionState.queued,
            draftSnapshot = draftSnapshot,
        )
        val memory: Map<String, ByteArray> = photos.associate { it.id to it.data }
        return attempt(entry, memoryPhotos = memory)
    }

    suspend fun retry(id: String): SubmissionOutcome {
        val entry = loadEntry(id) ?: throw BackroadStatusError.ApiError(-1, "No pending submission $id")
        return attempt(entry, memoryPhotos = emptyMap())
    }

    /**
     * Auto-retry sweep. Walks only queued entries — failed items are
     * parked until the user acts on them.
     */
    suspend fun retryAll() {
        mutex.withLock {
            if (isRetryingAll) return
            isRetryingAll = true
        }
        try {
            val snapshot = _pending.value.filter { it.state == SubmissionState.queued }
            for (item in snapshot) {
                val entry = loadEntry(item.id) ?: continue
                try {
                    attempt(entry, emptyMap())
                } catch (_: Throwable) { }
            }
        } finally {
            mutex.withLock { isRetryingAll = false }
        }
    }

    fun cancel(id: String) {
        removeFromDisk(id)
        reloadPending()
    }

    /**
     * Pops a persisted submission off the queue so the composer can resume
     * it in edit mode. Moves any photo bytes we were holding into
     * [destinationDirectory] (the composer's DraftPhotos dir), removes the
     * entry JSON and its photos dir, and returns the public DTO the caller
     * needs to decode the draft blob and present the composer.
     */
    fun takeForEdit(id: String, destinationDirectory: File): SubmissionForEdit? {
        val entry = loadEntry(id) ?: return null
        val restorationPayload: ByteArray = try {
            json.encodeToString(StoredSubmission.serializer(), entry).toByteArray(Charsets.UTF_8)
        } catch (_: Throwable) {
            ByteArray(0)
        }
        val photosSrc = photosDir(entry.id)
        if (photosSrc.exists()) {
            destinationDirectory.mkdirs()
            for (photo in entry.queuedPhotos) {
                val src = photoFile(entry.id, photo.id)
                if (!src.exists()) continue
                val dst = File(destinationDirectory, "${photo.id}.jpg")
                if (dst.exists()) dst.delete()
                src.renameTo(dst)
            }
        }
        removeFromDisk(entry.id)
        reloadPending()
        return SubmissionForEdit(
            id = entry.id,
            clientSubmissionId = entry.input.clientSubmissionId,
            pathId = entry.input.pathId,
            roadName = entry.roadName,
            draftSnapshot = entry.draftSnapshot,
            restorationPayload = restorationPayload,
        )
    }

    /**
     * Inverse of [takeForEdit]. Reinstates the on-disk queue entry using
     * [SubmissionForEdit.restorationPayload] and migrates the
     * originally-tracked photo files back from [photoDirectory] into the
     * queue's sidecar dir. Called from the composer's "Revert" affordance.
     */
    fun restore(edit: SubmissionForEdit, photoDirectory: File) {
        val entry = try {
            json.decodeFromString(
                StoredSubmission.serializer(),
                String(edit.restorationPayload, Charsets.UTF_8),
            )
        } catch (t: Throwable) {
            Log.w("BackroadStatus", "restore(): couldn't decode restoration payload for ${edit.id}; skipping")
            return
        }
        for (photo in entry.queuedPhotos) {
            val src = File(photoDirectory, "${photo.id}.jpg")
            if (!src.exists()) continue
            val dir = photosDir(entry.id).apply { mkdirs() }
            val dst = File(dir, "${photo.id}.jpg")
            if (dst.exists()) dst.delete()
            src.renameTo(dst)
        }
        saveToDisk(entry)
        reloadPending()
    }

    // MARK: Attempt + error classification

    private suspend fun attempt(
        entry: StoredSubmission,
        memoryPhotos: Map<String, ByteArray>,
    ): SubmissionOutcome {
        var updated = entry

        // Stage 1: upload every photo that doesn't have a reference yet.
        val photos = updated.queuedPhotos.toMutableList()
        for (i in photos.indices) {
            if (photos[i].reference != null) continue
            val photoId = photos[i].id
            val bytes = memoryPhotos[photoId] ?: loadPhotoBytes(entry.id, photoId)
            if (bytes == null) {
                Log.w(
                    "BackroadStatus",
                    "Submission ${entry.id} photo $photoId bytes missing — marking failed",
                )
                updated = updated.copy(
                    state = SubmissionState.failed,
                    lastError = "Draft photo bytes missing on disk.",
                    queuedPhotos = photos,
                )
                saveToDisk(updated)
                reloadPending()
                throw BackroadStatusError.ApiError(-1, "Draft photo $photoId bytes missing on disk")
            }
            try {
                val uploaded = BackroadStatus.shared.uploadDraftPhoto(context, bytes)
                photos[i] = photos[i].copy(reference = uploaded.reference)
            } catch (t: Throwable) {
                val retryable = isRetryable(t)
                Log.i(
                    "BackroadStatus",
                    "Submission ${entry.id} photo $photoId upload ${if (retryable) "retryable" else "non-retryable"}: ${t.message}",
                )
                updated = updated.copy(
                    state = if (retryable) SubmissionState.queued else SubmissionState.failed,
                    lastError = t.message,
                    queuedPhotos = photos,
                )
                persistUnreferencedPhotos(updated, memoryPhotos)
                saveToDisk(updated)
                reloadPending()
                return if (retryable) SubmissionOutcome.Queued(toPending(updated)) else throw t
            }
        }

        // Stage 2: all photos have references; build the final photos list
        // on the bulletin input and submit.
        val finalPhotos = photos.map { PhotoInput(reference = it.reference ?: "", caption = it.caption) }
        val finalInput = updated.input.copy().also { it.photos = finalPhotos }
        updated = updated.copy(input = finalInput, queuedPhotos = photos)

        return try {
            val bulletin = BackroadStatus.shared.createBulletin(context, updated.input)
            removeFromDisk(entry.id)
            reloadPending()
            SubmissionOutcome.Completed(bulletin)
        } catch (t: Throwable) {
            val retryable = isRetryable(t)
            Log.i(
                "BackroadStatus",
                "Submission ${entry.id} createBulletin ${if (retryable) "retryable" else "non-retryable"}: ${t.message}",
            )
            updated = updated.copy(
                state = if (retryable) SubmissionState.queued else SubmissionState.failed,
                lastError = t.message,
            )
            saveToDisk(updated)
            reloadPending()
            if (retryable) SubmissionOutcome.Queued(toPending(updated)) else throw t
        }
    }

    private fun persistUnreferencedPhotos(
        entry: StoredSubmission,
        memoryPhotos: Map<String, ByteArray>,
    ) {
        for (photo in entry.queuedPhotos) {
            if (photo.reference != null) continue
            val bytes = memoryPhotos[photo.id] ?: continue
            savePhotoBytes(entry.id, photo.id, bytes)
        }
    }

    /** Network-ish errors get retried; everything else bubbles up. */
    private fun isRetryable(t: Throwable): Boolean {
        if (t is IOException) return true
        if (t is BackroadStatusError.SimulatedOffline) return true
        if (t is BackroadStatusError.ApiError) {
            // >= 500: server hiccup. 429: rate-limited. < 0: our own
            // "couldn't even read a response" sentinel. All worth retrying.
            return t.status >= 500 || t.status == 429 || t.status < 0
        }
        return false
    }

    // MARK: Storage

    private fun fileFor(id: String): File = File(storageDir, "$id.json")
    private fun photosDir(entryId: String): File = File(storageDir, "$entryId-photos")
    private fun photoFile(entryId: String, photoId: String): File = File(photosDir(entryId), "$photoId.jpg")

    private fun savePhotoBytes(entryId: String, photoId: String, data: ByteArray) {
        val dir = photosDir(entryId).apply { mkdirs() }
        File(dir, "$photoId.jpg").writeBytes(data)
    }

    private fun loadPhotoBytes(entryId: String, photoId: String): ByteArray? {
        val f = photoFile(entryId, photoId)
        return if (f.exists()) f.readBytes() else null
    }

    private fun saveToDisk(entry: StoredSubmission) {
        try {
            val encoded = json.encodeToString(StoredSubmission.serializer(), entry)
            fileFor(entry.id).writeText(encoded, Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w("BackroadStatus", "Failed to persist submission ${entry.id}: ${t.message}")
        }
    }

    private fun loadEntry(id: String): StoredSubmission? {
        val f = fileFor(id)
        if (!f.exists()) return null
        return try {
            json.decodeFromString(StoredSubmission.serializer(), f.readText(Charsets.UTF_8))
        } catch (_: Throwable) {
            null
        }
    }

    private fun removeFromDisk(id: String) {
        fileFor(id).delete()
        photosDir(id).deleteRecursively()
    }

    private fun reloadPending() {
        val entries = storageDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                try {
                    json.decodeFromString(StoredSubmission.serializer(), f.readText(Charsets.UTF_8))
                } catch (_: Throwable) {
                    null
                }
            }
            ?.sortedBy { it.createdAt }
            ?: emptyList()
        _pending.value = entries.map(::toPending)
    }

    private fun toPending(entry: StoredSubmission): PendingSubmission = PendingSubmission(
        id = entry.id,
        clientSubmissionId = entry.input.clientSubmissionId,
        pathId = entry.input.pathId,
        roadName = entry.roadName,
        createdAt = entry.createdAt,
        lastError = entry.lastError,
        pendingPhotoCount = entry.queuedPhotos.count { it.reference == null },
        state = entry.state,
        hasDraftSnapshot = entry.draftSnapshot != null,
    )

    companion object {
        private val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        private var instance: SubmissionQueue? = null
        private val initMutex = Mutex()

        /**
         * Returns the process-wide [SubmissionQueue], initialized on first use.
         */
        suspend fun shared(context: Context): SubmissionQueue {
            instance?.let { return it }
            return initMutex.withLock {
                instance?.let { return@withLock it }
                val base = File(context.applicationContext.filesDir, "BackroadStatus/SubmissionQueue")
                val q = SubmissionQueue(context.applicationContext, base)
                instance = q
                q
            }
        }

        fun sharedOrNull(): SubmissionQueue? = instance
    }
}

/**
 * Private on-disk shape. Keeps the public [PendingSubmission] free of the
 * full request body (which a UI never needs to inspect directly) while
 * making it easy for the queue to rebuild the exact submit call.
 */
@Serializable
internal data class StoredSubmission(
    val id: String,
    val input: CreateBulletinInput,
    val queuedPhotos: List<QueuedPhoto>,
    val roadName: String? = null,
    val createdAt: Long,
    val lastError: String? = null,
    val state: SubmissionState = SubmissionState.queued,
    /**
     * Opaque composer-draft JSON blob (an encoded `Draft` from the composer
     * module). The queue never decodes it — it's just passed back out on
     * [SubmissionQueue.takeForEdit].
     */
    val draftSnapshot: ByteArray? = null,
)

@Serializable
internal data class QueuedPhoto(
    val id: String,
    val caption: String,
    val reference: String? = null,
)
