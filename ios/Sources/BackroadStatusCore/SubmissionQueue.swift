import Foundation
import Network

/// Lifecycle of a persisted submission. `.queued` is transient — the queue
/// retries it automatically on connectivity flips. `.failed` is parked —
/// something about the submission itself (a 400, a malformed field) means
/// retrying unchanged will fail again, so the user has to either edit it,
/// retry manually (hoping the server's state has changed), or discard it.
public enum SubmissionState: String, Codable, Sendable {
    case queued
    case failed
}

/// Snapshot of a persisted bulletin submission that hasn't been successfully
/// delivered to the server yet. Everything a UI needs to render the entry
/// without reaching into the queue's internals.
public struct PendingSubmission: Identifiable, Sendable, Equatable {
    public let id: UUID
    public let clientSubmissionId: String
    public let pathId: String
    public let roadName: String?
    public let createdAt: Date
    public let lastError: String?
    /// Photos still waiting on a reference. 0 means the queue is only
    /// waiting on `createBulletin`; > 0 means uploads are outstanding too.
    public let pendingPhotoCount: Int
    public let state: SubmissionState
    /// True when the entry carries a composer-draft blob the caller can
    /// decode and prefill a composer with. False means the entry predates
    /// the draft-snapshot change — Retry / Discard still work, but Edit
    /// has nothing to resume from.
    public let hasDraftSnapshot: Bool
}

/// Public DTO returned by `SubmissionQueue.takeForEdit(...)`. Everything a
/// caller needs to resume the composer in edit mode: the opaque draft blob
/// it previously stored, plus the `clientSubmissionId` so the next Submit
/// still dedupes server-side.
public struct SubmissionForEdit: Sendable {
    public let id: UUID
    public let clientSubmissionId: String
    public let pathId: String
    public let roadName: String?
    public let draftSnapshot: Data?
    /// Opaque blob the caller can hand to `SubmissionQueue.restore(_:photosFrom:)`
    /// to put the original queue entry back exactly as it was (same state,
    /// same `createdAt`, same `lastError`). Lets the composer offer a
    /// "Revert" affordance for edits the user decides to back out of.
    public let restorationPayload: Data
}

/// A photo the composer is handing off to the submission queue. If
/// `reference` is set, the photo is already uploaded and the queue just
/// needs to include it in the bulletin body. Otherwise the queue will
/// upload the bytes first (and persist them so retries after app kill
/// still work).
public struct PendingPhoto: Identifiable, Sendable {
    public let id: UUID
    public var data: Data
    public var caption: String
    public var reference: String?

    public init(id: UUID = UUID(), data: Data, caption: String, reference: String? = nil) {
        self.id = id
        self.data = data
        self.caption = caption
        self.reference = reference
    }
}

/// Outcome of a `SubmissionQueue.submit(...)` call.
public enum SubmissionOutcome: Sendable {
    /// Request landed on the server. The bulletin is already persisted and
    /// nothing is on the queue for this submission.
    case completed(Bulletin)
    /// Network trouble (or similar retryable error) — the submission and
    /// any pending photo bytes were stashed on disk and will retry
    /// automatically once connectivity returns. Call `retry(id:)` to
    /// force an immediate retry. Non-retryable failures (e.g. 400) do
    /// NOT return this case — they throw and the entry is parked on disk
    /// as `.failed`; the UI surfaces it via `pending`.
    case queued(PendingSubmission)
}

/// On-disk retry queue for bulletin submissions. Pair with the server's
/// `clientSubmissionId` idempotency key so retries after a network blip, an
/// app background/kill, or a bad-signal road trip never create duplicate
/// bulletins.
///
/// - `submit(_:photos:roadName:)` to send a bulletin. If everything lands
///   synchronously, the call returns `.completed(bulletin)` and nothing hits
///   disk. If the error is retryable (network down, 5xx, 429), the
///   submission + any un-uploaded photo bytes are persisted and you get
///   `.queued(PendingSubmission)` — the queue takes it from there, handling
///   both the remaining photo uploads and the final `createBulletin`.
/// - `pending` publishes the current on-disk backlog so a SwiftUI view can
///   show a badge / banner.
/// - `isNetworkAvailable` is a rough reachability signal (`NWPathMonitor`) —
///   good enough for an "offline" UI banner.
@MainActor
public final class SubmissionQueue: ObservableObject {
    public static let shared = SubmissionQueue()

    @Published public private(set) var pending: [PendingSubmission] = []
    @Published public private(set) var isNetworkAvailable: Bool = true

    private let storageURL: URL
    private let pathMonitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "backroadstatus.submission-queue.monitor")
    private var isRetryingAll = false

    init(storageURL: URL = SubmissionQueue.defaultStorageURL()) {
        self.storageURL = storageURL
        try? FileManager.default.createDirectory(at: storageURL, withIntermediateDirectories: true)
        loadFromDisk()
        startConnectivityMonitor()
    }

    // MARK: Public API

    public func submit(
        _ input: CreateBulletinInput,
        photos: [PendingPhoto] = [],
        roadName: String? = nil,
        draftSnapshot: Data? = nil
    ) async throws -> SubmissionOutcome {
        let entry = StoredSubmission(
            id: UUID(),
            input: input,
            queuedPhotos: photos.map { QueuedPhoto(id: $0.id, caption: $0.caption, reference: $0.reference) },
            roadName: roadName,
            createdAt: Date(),
            lastError: nil,
            state: .queued,
            draftSnapshot: draftSnapshot
        )
        let memory = Dictionary(uniqueKeysWithValues: photos.map { ($0.id, $0.data) })
        return try await attempt(entry, memoryPhotos: memory)
    }

    @discardableResult
    public func retry(id: UUID) async throws -> SubmissionOutcome {
        guard let entry = loadEntry(id: id) else {
            throw BackroadStatusError.apiError(status: -1, body: "No pending submission \(id)")
        }
        return try await attempt(entry, memoryPhotos: [:])
    }

    /// Auto-retry sweep. Walks only `.queued` entries — `.failed` items are
    /// parked until the user acts on them. Called on connectivity restoration
    /// and when the host app explicitly asks for a resume.
    public func retryAll() async {
        guard !isRetryingAll else { return }
        isRetryingAll = true
        defer { isRetryingAll = false }
        let snapshot = pending.filter { $0.state == .queued }
        for item in snapshot {
            guard let entry = loadEntry(id: item.id) else { continue }
            _ = try? await attempt(entry, memoryPhotos: [:])
        }
    }

    public func cancel(id: UUID) {
        removeFromDisk(id: id)
        reloadPending()
    }

    /// Pops a persisted submission off the queue so the composer can resume
    /// it in edit mode. Moves any photo bytes we were holding into
    /// `destinationDirectory` (the composer's DraftPhotos dir), removes the
    /// entry JSON and its photos dir, and returns the public DTO the caller
    /// needs to decode the draft blob and present the composer.
    ///
    /// The `clientSubmissionId` in the returned DTO is the original one —
    /// the composer plumbs it into the fresh `CreateBulletinInput` so the
    /// server still dedupes if the edited submission is later retried.
    public func takeForEdit(id: UUID, migratingPhotosTo destinationDirectory: URL) -> SubmissionForEdit? {
        guard let entry = loadEntry(id: id) else { return nil }
        // Snapshot the pre-pop record so the caller can hand it back for
        // Revert without us having to remember it ourselves. JSON over Data
        // so SubmissionForEdit can stay Sendable.
        let restorationPayload: Data = {
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            return (try? encoder.encode(entry)) ?? Data()
        }()
        let photosSrc = photosDir(entryId: entry.id)
        if FileManager.default.fileExists(atPath: photosSrc.path) {
            try? FileManager.default.createDirectory(at: destinationDirectory, withIntermediateDirectories: true)
            for photo in entry.queuedPhotos {
                let src = photoURL(entryId: entry.id, photoId: photo.id)
                guard FileManager.default.fileExists(atPath: src.path) else { continue }
                let dst = destinationDirectory.appendingPathComponent("\(photo.id.uuidString).jpg")
                // Overwrite any lingering bytes at the destination so the
                // composer sees the queue's copy as source of truth.
                try? FileManager.default.removeItem(at: dst)
                try? FileManager.default.moveItem(at: src, to: dst)
            }
        }
        removeFromDisk(id: entry.id)
        reloadPending()
        return SubmissionForEdit(
            id: entry.id,
            clientSubmissionId: entry.input.clientSubmissionId,
            pathId: entry.input.pathId,
            roadName: entry.roadName,
            draftSnapshot: entry.draftSnapshot,
            restorationPayload: restorationPayload
        )
    }

    /// Inverse of `takeForEdit(...)`. Reinstates the on-disk queue entry
    /// using `SubmissionForEdit.restorationPayload` and migrates the
    /// originally-tracked photo files back from `photoDirectory` (the
    /// composer's DraftPhotos dir) into the queue's sidecar dir. Called
    /// from the composer's "Revert" affordance.
    ///
    /// Photos the user added inside the composer but didn't originally
    /// belong to the entry stay at `photoDirectory`; the composer's
    /// `discardDraft()` sweep removes them along with the draft.
    public func restore(_ edit: SubmissionForEdit, photosFrom photoDirectory: URL) {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        guard let entry = try? decoder.decode(StoredSubmission.self, from: edit.restorationPayload) else {
            print("[BackroadStatus] restore(): couldn't decode restoration payload for \(edit.id); skipping")
            return
        }
        for photo in entry.queuedPhotos {
            let src = photoDirectory.appendingPathComponent("\(photo.id.uuidString).jpg")
            guard FileManager.default.fileExists(atPath: src.path) else { continue }
            let dst = photoURL(entryId: entry.id, photoId: photo.id)
            try? FileManager.default.createDirectory(at: photosDir(entryId: entry.id), withIntermediateDirectories: true)
            try? FileManager.default.removeItem(at: dst)
            try? FileManager.default.moveItem(at: src, to: dst)
        }
        saveToDisk(entry)
        reloadPending()
    }

    // MARK: Attempt + error classification

    /// Walks the two-stage pipeline: upload any photos missing references,
    /// then submit the bulletin. Either stage can go retryable or
    /// non-retryable; in both failure modes we persist the entry (with
    /// `state = .queued` or `.failed` respectively) so the user has a
    /// record of it in Pending Bulletins. The non-retryable branch still
    /// throws so the composer's submit-error card renders.
    private func attempt(_ entry: StoredSubmission, memoryPhotos: [UUID: Data]) async throws -> SubmissionOutcome {
        var updated = entry

        // Stage 1: upload every photo that doesn't have a reference yet.
        for i in updated.queuedPhotos.indices where updated.queuedPhotos[i].reference == nil {
            let photoId = updated.queuedPhotos[i].id
            let bytes = memoryPhotos[photoId] ?? loadPhotoBytes(entryId: entry.id, photoId: photoId)
            guard let bytes else {
                // The bytes we need are gone (app sandbox cleared, user
                // wiped Application Support, …). No path forward for this
                // entry — park it as failed so the user can see it and
                // discard rather than looping on a bytes-missing error.
                print("[BackroadStatus] Submission \(entry.id) photo \(photoId) bytes missing — marking failed")
                updated.state = .failed
                updated.lastError = "Draft photo bytes missing on disk."
                saveToDisk(updated)
                reloadPending()
                throw BackroadStatusError.apiError(status: -1, body: "Draft photo \(photoId) bytes missing on disk")
            }
            do {
                let photo = try await BackroadStatus.shared.uploadDraftPhoto(bytes)
                updated.queuedPhotos[i].reference = photo.reference
            } catch {
                let retryable = Self.isRetryable(error)
                print("[BackroadStatus] Submission \(entry.id) photo \(photoId) upload \(retryable ? "retryable" : "non-retryable"): \(error.localizedDescription)")
                updated.state = retryable ? .queued : .failed
                updated.lastError = error.localizedDescription
                persistUnreferencedPhotos(entry: updated, memoryPhotos: memoryPhotos)
                saveToDisk(updated)
                reloadPending()
                if retryable {
                    return .queued(Self.toPending(updated))
                } else {
                    throw error
                }
            }
        }

        // Stage 2: all photos have references; build the final photos list
        // on the bulletin input and submit.
        updated.input.photos = updated.queuedPhotos.map {
            PhotoInput(reference: $0.reference ?? "", caption: $0.caption)
        }

        do {
            let bulletin = try await BackroadStatus.shared.createBulletin(updated.input)
            removeFromDisk(id: entry.id)
            reloadPending()
            return .completed(bulletin)
        } catch {
            let retryable = Self.isRetryable(error)
            print("[BackroadStatus] Submission \(entry.id) createBulletin \(retryable ? "retryable" : "non-retryable"): \(error.localizedDescription)")
            updated.state = retryable ? .queued : .failed
            updated.lastError = error.localizedDescription
            // Photos are all referenced at this point — no bytes to keep
            // around. The entry JSON is enough for a retry or edit.
            saveToDisk(updated)
            reloadPending()
            if retryable {
                return .queued(Self.toPending(updated))
            } else {
                throw error
            }
        }
    }

    /// Writes every still-unreferenced photo's bytes to the entry's
    /// sidecar dir so a later retry can find them even after an app relaunch.
    /// Already-on-disk bytes (we loaded them off disk earlier) are left alone.
    private func persistUnreferencedPhotos(entry: StoredSubmission, memoryPhotos: [UUID: Data]) {
        for photo in entry.queuedPhotos where photo.reference == nil {
            guard let bytes = memoryPhotos[photo.id] else { continue }
            savePhotoBytes(entryId: entry.id, photoId: photo.id, data: bytes)
        }
    }

    /// Network-ish errors get retried; everything else bubbles up so the
    /// caller can show the user a real failure.
    private static func isRetryable(_ error: Error) -> Bool {
        if error is URLError { return true }
        if let api = error as? BackroadStatusError {
            switch api {
            case .simulatedOffline:
                return true
            case .apiError(let status, _):
                // >= 500: server hiccup. 429: rate-limited. < 0: our own
                // "couldn't even read a response" sentinel. All worth retrying.
                return status >= 500 || status == 429 || status < 0
            case .notConfigured, .notAuthenticated, .oauthFailed:
                return false
            }
        }
        return false
    }

    // MARK: Storage

    nonisolated static func defaultStorageURL() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base
            .appendingPathComponent("BackroadStatus", isDirectory: true)
            .appendingPathComponent("SubmissionQueue", isDirectory: true)
    }

    private func fileURL(for id: UUID) -> URL {
        storageURL.appendingPathComponent("\(id.uuidString).json")
    }

    private func photosDir(entryId: UUID) -> URL {
        storageURL.appendingPathComponent("\(entryId.uuidString)-photos", isDirectory: true)
    }

    private func photoURL(entryId: UUID, photoId: UUID) -> URL {
        photosDir(entryId: entryId).appendingPathComponent("\(photoId.uuidString).jpg")
    }

    private func savePhotoBytes(entryId: UUID, photoId: UUID, data: Data) {
        let dir = photosDir(entryId: entryId)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? data.write(to: photoURL(entryId: entryId, photoId: photoId), options: [.atomic])
    }

    private func loadPhotoBytes(entryId: UUID, photoId: UUID) -> Data? {
        try? Data(contentsOf: photoURL(entryId: entryId, photoId: photoId))
    }

    private func saveToDisk(_ entry: StoredSubmission) {
        do {
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            let data = try encoder.encode(entry)
            try data.write(to: fileURL(for: entry.id), options: [.atomic])
        } catch {
            print("[BackroadStatus] Failed to persist submission \(entry.id): \(error)")
        }
    }

    private func loadEntry(id: UUID) -> StoredSubmission? {
        let url = fileURL(for: id)
        guard let data = try? Data(contentsOf: url) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(StoredSubmission.self, from: data)
    }

    private func removeFromDisk(id: UUID) {
        try? FileManager.default.removeItem(at: fileURL(for: id))
        try? FileManager.default.removeItem(at: photosDir(entryId: id))
    }

    private func loadFromDisk() {
        let fm = FileManager.default
        guard let urls = try? fm.contentsOfDirectory(at: storageURL, includingPropertiesForKeys: nil) else { return }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let entries = urls
            .filter { $0.pathExtension == "json" }
            .compactMap { url -> StoredSubmission? in
                guard let data = try? Data(contentsOf: url) else { return nil }
                return try? decoder.decode(StoredSubmission.self, from: data)
            }
            .sorted { $0.createdAt < $1.createdAt }
        pending = entries.map(Self.toPending)
    }

    private func reloadPending() {
        let fm = FileManager.default
        guard let urls = try? fm.contentsOfDirectory(at: storageURL, includingPropertiesForKeys: nil) else {
            pending = []
            return
        }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        pending = urls
            .filter { $0.pathExtension == "json" }
            .compactMap { url in
                (try? Data(contentsOf: url)).flatMap { try? decoder.decode(StoredSubmission.self, from: $0) }
            }
            .sorted { $0.createdAt < $1.createdAt }
            .map(Self.toPending)
    }

    private static func toPending(_ entry: StoredSubmission) -> PendingSubmission {
        PendingSubmission(
            id: entry.id,
            clientSubmissionId: entry.input.clientSubmissionId,
            pathId: entry.input.pathId,
            roadName: entry.roadName,
            createdAt: entry.createdAt,
            lastError: entry.lastError,
            pendingPhotoCount: entry.queuedPhotos.filter { $0.reference == nil }.count,
            state: entry.state,
            hasDraftSnapshot: entry.draftSnapshot != nil
        )
    }

    // MARK: Connectivity

    private func startConnectivityMonitor() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor [weak self] in
                guard let self else { return }
                let online = path.status == .satisfied
                let becameOnline = online && !self.isNetworkAvailable
                self.isNetworkAvailable = online
                if becameOnline, !self.pending.isEmpty {
                    print("[BackroadStatus] Connectivity restored — retrying \(self.pending.count) pending submission(s)")
                    await self.retryAll()
                }
            }
        }
        pathMonitor.start(queue: monitorQueue)
    }

    deinit {
        pathMonitor.cancel()
    }
}

// MARK: - On-disk entry

/// Private storage shape. Keeps the public `PendingSubmission` free of the
/// full request body (which a UI never needs to inspect directly) while
/// making it easy for the queue to rebuild the exact submit call.
struct StoredSubmission: Codable {
    let id: UUID
    var input: CreateBulletinInput
    var queuedPhotos: [QueuedPhoto]
    let roadName: String?
    let createdAt: Date
    var lastError: String?
    var state: SubmissionState
    /// Opaque composer-draft JSON blob (an encoded `Draft` from
    /// BackroadStatusComposerUI). The queue never decodes it — it's just
    /// passed back out on `takeForEdit(...)` so the composer can resume
    /// the exact state the user submitted.
    var draftSnapshot: Data?

    /// Tolerates legacy on-disk entries that predate `state` /
    /// `draftSnapshot` — decode treats both as sensible defaults rather
    /// than refusing the whole file.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(UUID.self, forKey: .id)
        self.input = try c.decode(CreateBulletinInput.self, forKey: .input)
        self.queuedPhotos = try c.decode([QueuedPhoto].self, forKey: .queuedPhotos)
        self.roadName = try c.decodeIfPresent(String.self, forKey: .roadName)
        self.createdAt = try c.decode(Date.self, forKey: .createdAt)
        self.lastError = try c.decodeIfPresent(String.self, forKey: .lastError)
        self.state = try c.decodeIfPresent(SubmissionState.self, forKey: .state) ?? .queued
        self.draftSnapshot = try c.decodeIfPresent(Data.self, forKey: .draftSnapshot)
    }

    init(
        id: UUID,
        input: CreateBulletinInput,
        queuedPhotos: [QueuedPhoto],
        roadName: String?,
        createdAt: Date,
        lastError: String?,
        state: SubmissionState,
        draftSnapshot: Data?
    ) {
        self.id = id
        self.input = input
        self.queuedPhotos = queuedPhotos
        self.roadName = roadName
        self.createdAt = createdAt
        self.lastError = lastError
        self.state = state
        self.draftSnapshot = draftSnapshot
    }
}

/// On-disk per-photo record. Bytes live in a sidecar file at
/// `<entry>-photos/<id>.jpg`, keyed by the same id, so the JSON stays
/// cheap to rewrite.
struct QueuedPhoto: Codable {
    let id: UUID
    var caption: String
    var reference: String?
}
