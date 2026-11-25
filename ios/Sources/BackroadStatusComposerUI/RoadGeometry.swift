import Foundation

/// Ordered coordinate array for a road plus precomputed cumulative haversine
/// distances (meters). Used by the composer slider for step-index → distance
/// label conversion.
public struct RoadGeometry: Equatable {
    public let coordinates: [[Double]]
    public let cumulativeDistances: [Double]

    public init(coordinates: [[Double]], cumulativeDistances: [Double]) {
        self.coordinates = coordinates
        self.cumulativeDistances = cumulativeDistances
    }

    public var totalDistance: Double { cumulativeDistances.last ?? 0 }

    public func coord(atMeters meters: Double) -> [Double]? {
        guard !coordinates.isEmpty else { return nil }
        let clamped = max(0, min(meters, totalDistance))
        var best = 0
        var bestDelta = abs(cumulativeDistances[0] - clamped)
        for i in 1..<cumulativeDistances.count {
            let d = abs(cumulativeDistances[i] - clamped)
            if d < bestDelta { best = i; bestDelta = d }
        }
        return coordinates[best]
    }

    public static func from(coordinates: [[Double]]) -> RoadGeometry {
        guard coordinates.count >= 2 else {
            return RoadGeometry(
                coordinates: coordinates,
                cumulativeDistances: coordinates.isEmpty ? [] : [0]
            )
        }
        var distances: [Double] = [0]
        distances.reserveCapacity(coordinates.count)
        var total: Double = 0
        for i in 1..<coordinates.count {
            total += haversine(coordinates[i - 1], coordinates[i])
            distances.append(total)
        }
        return RoadGeometry(coordinates: coordinates, cumulativeDistances: distances)
    }

    private static func haversine(_ a: [Double], _ b: [Double]) -> Double {
        let R = 6_371_000.0
        let lon1 = a[0] * .pi / 180, lat1 = a[1] * .pi / 180
        let lon2 = b[0] * .pi / 180, lat2 = b[1] * .pi / 180
        let dLat = lat2 - lat1
        let dLon = lon2 - lon1
        let h = sin(dLat / 2) * sin(dLat / 2)
            + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * asin(min(1, sqrt(h)))
    }
}
