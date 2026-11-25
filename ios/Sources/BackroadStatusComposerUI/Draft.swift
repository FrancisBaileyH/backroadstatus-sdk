import Foundation
import BackroadStatusCore

/// Per-photo metadata persisted with a draft. The bytes live in a sidecar
/// file at `DraftPhotos/<id>.jpg` so the draft JSON stays small and cheap
/// to rewrite on every keystroke. `reference` is populated when the photo
/// has made it up to the server; nil means it still needs uploading.
struct DraftPhotoMeta: Codable, Equatable, Identifiable {
    let id: UUID
    var caption: String
    var reference: String?
}

/// Snapshot of composer state persisted so the user can close the app in
/// the middle of writing a report (offline-while-driving, interruption,
/// plain forgetfulness) and pick up right where they left off.
struct Draft: Codable {
    /// SDK-internal draft id. Separate from `clientSubmissionId` which is
    /// what the server dedupes on; keeping them distinct lets us iterate
    /// the draft without rotating the idempotency key.
    let id: UUID
    /// Stable across the draft's lifetime so retries after Submit always
    /// dedupe on the server, even across app relaunches.
    let clientSubmissionId: String
    let roadId: String
    var roadName: String?
    var createdAt: Date
    var updatedAt: Date

    var reportDate: Date
    var description: String

    var startStep: CGFloat
    var conditions: [ConditionEntry]

    var verifyMode: Bool
    var verifyValues: [CGFloat]

    var featureStatuses: [String: FeatureStatus]
    var featureChangeTypes: [String: String]
    var featureChangeComments: [String: String]
    var featureRemoved: [String: Bool]
    var createdFeatures: [CreatedFeature]

    /// Vehicle categories the user ticked under "Modes of Travel". Optional
    /// on the API side; composer caps selection at 3.
    var vehicles: [VehicleCategory]

    /// Photos attached to the draft. Bytes live at
    /// `DraftPhotos/<id>.jpg`; the meta here carries id + caption + the
    /// upload reference once the server has it.
    var photos: [DraftPhotoMeta]

    /// Snapshot of the road detail at the time the draft was last saved.
    /// Lets the composer render the map and populate the condition slider
    /// without hitting `/paths/{id}` — critical for offline resume where
    /// the user zoomed over, tapped a road, and saved a draft before
    /// losing signal. A live fetch still runs in the background once
    /// online and upgrades this cache if the geometry gets richer.
    var cachedRoad: RoadDetail?

    /// Tolerates old drafts written before `photos` existed — missing
    /// field decodes as [] instead of blowing up and discarding the draft.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(UUID.self, forKey: .id)
        self.clientSubmissionId = try c.decode(String.self, forKey: .clientSubmissionId)
        self.roadId = try c.decode(String.self, forKey: .roadId)
        self.roadName = try c.decodeIfPresent(String.self, forKey: .roadName)
        self.createdAt = try c.decode(Date.self, forKey: .createdAt)
        self.updatedAt = try c.decode(Date.self, forKey: .updatedAt)
        self.reportDate = try c.decode(Date.self, forKey: .reportDate)
        self.description = try c.decode(String.self, forKey: .description)
        self.startStep = try c.decode(CGFloat.self, forKey: .startStep)
        self.conditions = try c.decode([ConditionEntry].self, forKey: .conditions)
        self.verifyMode = try c.decode(Bool.self, forKey: .verifyMode)
        self.verifyValues = try c.decode([CGFloat].self, forKey: .verifyValues)
        self.featureStatuses = try c.decode([String: FeatureStatus].self, forKey: .featureStatuses)
        self.featureChangeTypes = try c.decode([String: String].self, forKey: .featureChangeTypes)
        self.featureChangeComments = try c.decode([String: String].self, forKey: .featureChangeComments)
        self.featureRemoved = try c.decode([String: Bool].self, forKey: .featureRemoved)
        self.createdFeatures = try c.decode([CreatedFeature].self, forKey: .createdFeatures)
        self.vehicles = try c.decodeIfPresent([VehicleCategory].self, forKey: .vehicles) ?? []
        self.photos = try c.decodeIfPresent([DraftPhotoMeta].self, forKey: .photos) ?? []
        self.cachedRoad = try c.decodeIfPresent(RoadDetail.self, forKey: .cachedRoad)
    }

    init(
        id: UUID,
        clientSubmissionId: String,
        roadId: String,
        roadName: String?,
        createdAt: Date,
        updatedAt: Date,
        reportDate: Date,
        description: String,
        startStep: CGFloat,
        conditions: [ConditionEntry],
        verifyMode: Bool,
        verifyValues: [CGFloat],
        featureStatuses: [String: FeatureStatus],
        featureChangeTypes: [String: String],
        featureChangeComments: [String: String],
        featureRemoved: [String: Bool],
        createdFeatures: [CreatedFeature],
        vehicles: [VehicleCategory],
        photos: [DraftPhotoMeta],
        cachedRoad: RoadDetail?
    ) {
        self.id = id
        self.clientSubmissionId = clientSubmissionId
        self.roadId = roadId
        self.roadName = roadName
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.reportDate = reportDate
        self.description = description
        self.startStep = startStep
        self.conditions = conditions
        self.verifyMode = verifyMode
        self.verifyValues = verifyValues
        self.featureStatuses = featureStatuses
        self.featureChangeTypes = featureChangeTypes
        self.featureChangeComments = featureChangeComments
        self.featureRemoved = featureRemoved
        self.createdFeatures = createdFeatures
        self.vehicles = vehicles
        self.photos = photos
        self.cachedRoad = cachedRoad
    }
}

/// Single-draft store backed by a single JSON file in Application Support.
///
/// The SDK enforces "one draft at a time" so the composer doesn't have to
/// juggle a list — a draft either exists or it doesn't. If the user tries
/// to start a report on a road that doesn't match the existing draft, the
/// composer prompts them (handled in ReportComposerView, not here).
///
/// `@Published currentDraft` lets SwiftUI views observe whether a draft
/// exists without reaching into the file system.
@MainActor
final class DraftStore: ObservableObject {
    static let shared = DraftStore()

    @Published private(set) var currentDraft: Draft?

    private let storageURL: URL

    init(storageURL: URL = DraftStore.defaultStorageURL()) {
        self.storageURL = storageURL
        try? FileManager.default.createDirectory(
            at: storageURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        self.currentDraft = Self.loadFromDisk(url: storageURL)
    }

    func save(_ draft: Draft) {
        var updated = draft
        updated.updatedAt = Date()
        do {
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            encoder.outputFormatting = [.prettyPrinted]
            let data = try encoder.encode(updated)
            try data.write(to: storageURL, options: [.atomic])
            currentDraft = updated
        } catch {
            print("[BackroadStatus] Failed to persist draft: \(error)")
        }
    }

    func discard() {
        try? FileManager.default.removeItem(at: storageURL)
        try? FileManager.default.removeItem(at: Self.photosDirectory())
        currentDraft = nil
    }

    // MARK: Photo bytes

    /// Writes the raw photo bytes for a draft photo id. The composer calls
    /// this once per picked image; the draft JSON's `DraftPhotoMeta` entry
    /// references the id so load-on-resume can pair them back up.
    func writePhoto(id: UUID, data: Data) {
        let dir = Self.photosDirectory()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("\(id.uuidString).jpg")
        do {
            try data.write(to: url, options: [.atomic])
        } catch {
            print("[BackroadStatus] Failed to persist draft photo \(id): \(error)")
        }
    }

    func readPhoto(id: UUID) -> Data? {
        let url = Self.photosDirectory().appendingPathComponent("\(id.uuidString).jpg")
        return try? Data(contentsOf: url)
    }

    func removePhoto(id: UUID) {
        let url = Self.photosDirectory().appendingPathComponent("\(id.uuidString).jpg")
        try? FileManager.default.removeItem(at: url)
    }

    /// Prunes photo files that are no longer referenced by the given set of
    /// ids. Called after each draft save so photos the user removed don't
    /// linger on disk.
    func pruneOrphanPhotos(keeping ids: Set<UUID>) {
        let dir = Self.photosDirectory()
        guard let contents = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        for url in contents {
            let stem = url.deletingPathExtension().lastPathComponent
            if let uuid = UUID(uuidString: stem), !ids.contains(uuid) {
                try? FileManager.default.removeItem(at: url)
            }
        }
    }

    // MARK: Storage

    nonisolated static func defaultStorageURL() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base
            .appendingPathComponent("BackroadStatus", isDirectory: true)
            .appendingPathComponent("draft.json")
    }

    nonisolated static func photosDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base
            .appendingPathComponent("BackroadStatus", isDirectory: true)
            .appendingPathComponent("DraftPhotos", isDirectory: true)
    }

    private static func loadFromDisk(url: URL) -> Draft? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(Draft.self, from: data)
    }

    /// Non-isolated synchronous peek at the on-disk draft (if any). Lets the
    /// composer decide on its *initial* render whether to surface the resume
    /// alert without needing to hop onto the main actor first — the file
    /// read is just a few hundred bytes of JSON.
    nonisolated static func peekDraftOnDisk() -> Draft? {
        let url = defaultStorageURL()
        guard let data = try? Data(contentsOf: url) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(Draft.self, from: data)
    }
}
