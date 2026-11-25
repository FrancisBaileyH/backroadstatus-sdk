import Foundation

/// Slippy-map tile math (XYZ / Web Mercator). Shared between the bulk
/// geometry downloader (enumerating tiles inside an offline region's
/// bbox) and the per-road cache lookup (figuring out which tile a tapped
/// road's bbox falls in).
public enum TileMath {

    public struct TileCoord: Hashable, Sendable {
        public let z: Int
        public let x: Int
        public let y: Int

        public init(z: Int, x: Int, y: Int) {
            self.z = z
            self.x = x
            self.y = y
        }
    }

    /// Returns every tile at `zoom` whose geographic extent intersects
    /// `bounds`. Clamps coords inside Web Mercator's ±85.0511° latitude
    /// range so the polar singularity doesn't produce NaN tile indices.
    public static func tiles(in bounds: GeographicBounds, at zoom: Int) -> [TileCoord] {
        let n = 1 << zoom
        let minX = tileX(longitude: bounds.westLongitude, zoom: zoom)
        let maxX = tileX(longitude: bounds.eastLongitude, zoom: zoom)
        // Latitude axis inverts under the slippy-map Y convention: north → smaller Y.
        let minY = tileY(latitude: bounds.northLatitude, zoom: zoom)
        let maxY = tileY(latitude: bounds.southLatitude, zoom: zoom)

        var result: [TileCoord] = []
        for x in max(0, minX)...min(n - 1, maxX) {
            for y in max(0, minY)...min(n - 1, maxY) {
                result.append(TileCoord(z: zoom, x: x, y: y))
            }
        }
        return result
    }

    public static func tileX(longitude: Double, zoom: Int) -> Int {
        let n = Double(1 << zoom)
        return Int(floor((longitude + 180) / 360 * n))
    }

    public static func tileY(latitude: Double, zoom: Int) -> Int {
        let clamped = max(-mercatorLatLimit, min(mercatorLatLimit, latitude))
        let rad = clamped * .pi / 180
        let n = Double(1 << zoom)
        return Int(floor((1 - log(tan(rad) + 1 / cos(rad)) / .pi) / 2 * n))
    }

    private static let mercatorLatLimit: Double = 85.05112878
}
