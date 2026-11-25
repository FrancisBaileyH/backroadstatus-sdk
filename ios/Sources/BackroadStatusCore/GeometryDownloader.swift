import Foundation

/// Fetches canonical road geometry for every tile inside an offline region's
/// bbox and stuffs each road into `GeometryStore` keyed by road id.
///
/// Download knobs:
///   - `zoom` — slippy-map zoom for the bulk endpoint. z11 is the default
///     (see the per-zoom tradeoff discussion in the SDK readme / design doc):
///     typical offline packs cover a single-digit number of tiles at z11 and
///     most backroads live inside one tile, so redundant copies across tiles
///     stay low.
///   - `maxInFlight` — concurrent requests cap. The tile server rate-limits
///     the bulk endpoint at 10 rps; staying a touch under lets natural
///     request latency smooth out bursts without tripping the limit.
///
/// Uses its own `URLSession`, not `URLSession.shared` — the host app has
/// likely pinned MLN's shared session to `httpMaximumConnectionsPerHost = 1`
/// for tile-server politeness, and we don't want the two downloaders
/// competing for the same single slot.
public final class GeometryDownloader: @unchecked Sendable {
    public static let defaultZoom: Int = 11
    public static let defaultMaxInFlight: Int = 8

    public struct Progress: Sendable {
        public let completedTiles: Int
        public let totalTiles: Int
        /// Tiles whose fetch raised an error (auth, 404, exhausted retries).
        /// Non-zero at `completedTiles == totalTiles` means the cache is
        /// incomplete and the composer will fall back to the live API for
        /// any roads in those tiles — surface this to the user so they
        /// can retry the download rather than discover it offline.
        public let failedTiles: Int
        public var fraction: Double {
            totalTiles > 0 ? Double(completedTiles) / Double(totalTiles) : 0
        }
    }

    private let api: APIClient

    public init() {
        let cfg = URLSessionConfiguration.default
        cfg.httpMaximumConnectionsPerHost = Self.defaultMaxInFlight
        cfg.waitsForConnectivity = false
        cfg.timeoutIntervalForRequest = 30
        cfg.timeoutIntervalForResource = 120
        api = APIClient(session: URLSession(configuration: cfg))
    }

    /// Fetches geometry for every tile in `bounds` at `zoom`, writing each
    /// returned road into `GeometryStore.shared`. Caller is expected to
    /// pair this with its MLN pack download so the two run concurrently
    /// against the same bbox.
    ///
    /// `onProgress` fires on an unspecified queue after each tile completes;
    /// hop to main before updating UI.
    public func download(
        bounds: GeographicBounds,
        zoom: Int = defaultZoom,
        maxInFlight: Int = defaultMaxInFlight,
        onProgress: @Sendable @escaping (Progress) -> Void = { _ in }
    ) async throws {
        let config = try BackroadStatus.shared.requireConfig()
        // Bulk geometry lives under `/api/integrations/v1`, so it requires
        // a valid bearer token like the rest of that namespace. Hand the
        // 401-retrying token provider through so each tile request can
        // refresh independently if the server rejects a stale token
        // mid-download.
        let tokenProvider = BackroadStatus.shared.tokenProvider(config: config)

        let tiles = TileMath.tiles(in: bounds, at: zoom)
        let total = tiles.count
        guard total > 0 else {
            onProgress(Progress(completedTiles: 0, totalTiles: 0, failedTiles: 0))
            return
        }

        let api = self.api
        let issuer = config.issuer
        let store = GeometryStore.shared

        let counter = ProgressCounter(total: total)
        try await withThrowingTaskGroup(of: Void.self) { group in
            var inFlight = 0
            for tile in tiles {
                if inFlight >= maxInFlight {
                    try await group.next()
                    inFlight -= 1
                }
                group.addTask {
                    var tileFailed = false
                    do {
                        let roads = try await Self.fetchTile(
                            api: api, issuer: issuer, tokenProvider: tokenProvider, tile: tile
                        )
                        for road in roads { store.upsert(road) }
                    } catch {
                        // Swallow per-tile errors so one bad tile doesn't
                        // abort the whole pack. Log, bump the fail counter
                        // so the caller can surface an aggregate to the
                        // user, and keep going.
                        print("[GeometryDownloader] tile z\(tile.z)/\(tile.x)/\(tile.y) failed: \(error.localizedDescription)")
                        tileFailed = true
                    }
                    let snapshot = await counter.recordCompletion(failed: tileFailed)
                    onProgress(Progress(
                        completedTiles: snapshot.completed,
                        totalTiles: total,
                        failedTiles: snapshot.failed
                    ))
                }
                inFlight += 1
            }
            try await group.waitForAll()
        }
    }

    /// Simple exponential backoff on 429/5xx. Max 3 attempts, 0.5s base.
    private static func fetchTile(
        api: APIClient,
        issuer: URL,
        tokenProvider: TokenProvider,
        tile: TileMath.TileCoord
    ) async throws -> [RoadDetail] {
        var attempt = 0
        while true {
            do {
                return try await api.bulkPaths(issuer: issuer, tokenProvider: tokenProvider, z: tile.z, x: tile.x, y: tile.y)
            } catch let BackroadStatusError.apiError(status, _) where (status == 429 || (500...599).contains(status)) && attempt < 3 {
                attempt += 1
                let backoff = UInt64(0.5 * pow(2.0, Double(attempt - 1)) * 1_000_000_000)
                try await Task.sleep(nanoseconds: backoff)
                continue
            }
        }
    }
}

/// Shared mutable counter the task group's children feed into. Actor
/// isolation keeps the increment race-free without extra locks.
private actor ProgressCounter {
    private var completed = 0
    private var failed = 0
    let total: Int

    struct Snapshot: Sendable {
        let completed: Int
        let failed: Int
    }

    init(total: Int) { self.total = total }

    func recordCompletion(failed tileFailed: Bool) -> Snapshot {
        completed += 1
        if tileFailed { failed += 1 }
        return Snapshot(completed: completed, failed: failed)
    }
}
