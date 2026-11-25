import Foundation

/// Disk cache of canonical `RoadDetail`s keyed by road id. Populated by
/// the bulk geometry downloader during offline-region preparation and
/// consulted by the composer before it falls back to `BackroadStatus.road`.
///
/// Storage format is one JSON file per road under
/// `Application Support/BackroadStatus/geometry/<id>.json`. File-per-road
/// keeps writes independent (no lock contention between parallel tile
/// downloads unpacking the same file) and makes manual inspection easy
/// while the API shape is still settling. Migrating to SQLite is cheap
/// if road counts grow beyond what a flat directory handles well.
public final class GeometryStore: @unchecked Sendable {
    public static let shared = GeometryStore()

    private let root: URL
    private let queue = DispatchQueue(label: "com.backroadstatus.geometry-store", attributes: .concurrent)
    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = []
        return e
    }()
    private let decoder = JSONDecoder()

    private init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        root = support
            .appendingPathComponent("BackroadStatus", isDirectory: true)
            .appendingPathComponent("geometry", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        // Application Support is NOT backed up to iCloud by default on iOS,
        // which is what we want — geometry cache is derived data.
        var r = root
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? r.setResourceValues(values)
    }

    public func road(id: String) -> RoadDetail? {
        queue.sync {
            guard let data = try? Data(contentsOf: fileURL(id: id)) else { return nil }
            return try? decoder.decode(RoadDetail.self, from: data)
        }
    }

    public func upsert(_ road: RoadDetail) {
        queue.async(flags: .barrier) { [self] in
            let url = fileURL(id: road.id)
            guard let data = try? encoder.encode(road) else { return }
            try? data.write(to: url, options: .atomic)
        }
    }

    public func remove(id: String) {
        queue.async(flags: .barrier) { [self] in
            try? FileManager.default.removeItem(at: fileURL(id: road_id_safe(id)))
        }
    }

    /// Wipes the entire cache. Intended for "Clear offline data"-style
    /// user actions; not called automatically on pack removal (packs can
    /// overlap, and a road might still be in another pack).
    public func clear() {
        queue.async(flags: .barrier) { [self] in
            try? FileManager.default.removeItem(at: root)
            try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        }
    }

    public func cachedRoadCount() -> Int {
        queue.sync {
            (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil))?.count ?? 0
        }
    }

    private func fileURL(id: String) -> URL {
        root.appendingPathComponent("\(road_id_safe(id)).json")
    }

    /// Road ids are server-controlled (`ften-104752` and the like), so in
    /// practice they're already filesystem-safe. We still sanitize defensively.
    private func road_id_safe(_ id: String) -> String {
        id.replacingOccurrences(of: "/", with: "_")
          .replacingOccurrences(of: ":", with: "_")
    }
}
