import UIKit
import BackroadStatusCore
import BackroadStatusMapKit

/// MapLibre-Style-Spec JSON builders + source updates for the composer's
/// inline map. Entirely vendor-neutral — every mutation flows through
/// `MapProvider`, so swapping MapLibre for Mapbox is a question of which
/// adapter the host supplies, not of touching this code.
///
/// All methods are `@MainActor` because every call delegates to
/// `MapProvider`, which itself is main-actor-isolated.
@MainActor
enum ComposerMapLayers {

    enum LayerID {
        static let geometrySource          = "mini-backroadstatus-roads-geometry-src"
        static let focusedPathLayer        = "mini-backroadstatus-focused-path"
        static let focusedPathBorderLayer  = "mini-backroadstatus-focused-path-border"
        static let focusedPathDashLayer    = "mini-backroadstatus-focused-path-dash"
        static let focusedPathHighlight    = "mini-backroadstatus-focused-path-highlight"
        static let distanceMarkerSource    = "mini-backroadstatus-distance-markers-src"
        static let distanceMarkerLayer     = "mini-backroadstatus-distance-markers"
    }

    /// Fontstack name MapLibre adapters inject against. Mapbox hosts can
    /// opt into their own font by overriding the distance-marker layer's
    /// `text-font` after it's installed.
    static let distanceMarkerFontstack = "Noto Sans Regular"

    /// Width threshold (meters) at which the focused road rendering steps
    /// up to a slightly thicker line — matches `pathLength > 15000` in
    /// the web composer.
    private static let widthStepUpThreshold: Double = 15000

    /// Default interval between distance markers in meters.
    static let distanceInterval: Double = 500

    // MARK: - Host style manipulation

    /// ANDs `id != focusedPathId` into each condition tile layer's filter
    /// so the tile-rendered version of the focused road is hidden —
    /// the composer's overlay renders it instead.
    static func excludeFocusedPath(provider: MapProvider, pathId: String) {
        for layerId in BackroadStatusMapLayers.conditionLayerIds where provider.layerExists(id: layerId) {
            try? provider.addFilterCondition(
                layer: layerId,
                filter: ["!=", ["get", "id"], pathId]
            )
        }
    }

    // MARK: - Focused-path overlay

    /// Installs the condition-colored focused-road overlay: one GeoJSON
    /// source whose features are per-condition segments (with `condition`
    /// + `pathLength` properties), plus four stacked line layers mirroring
    /// the web composer's `focusedPathHighlight` / `focusedPathBorder` /
    /// `focusedPath` / `focusedPathDash` styles.
    static func installGeometryOverlay(provider: MapProvider) {
        guard !provider.sourceExists(id: LayerID.geometrySource) else { return }

        try? provider.addSource(id: LayerID.geometrySource, spec: [
            "type": "geojson",
            "data": emptyFeatureCollection
        ])

        let widthExpr = focusedPathWidthExpression
        let mainColorExpr = conditionColorExpression(darkPalette: false)
        let darkColorExpr = conditionColorExpression(darkPalette: true)

        let insertBelow = BackroadStatusMapLayers.featureCircleLayerId
        let belowKey: String? = provider.layerExists(id: insertBelow)
            ? insertBelow
            : (provider.layerExists(id: BackroadStatusMapLayers.featureIconLayerId)
                ? BackroadStatusMapLayers.featureIconLayerId
                : nil)

        // Bottom of the group: blurred condition-tinted glow.
        try? provider.addLayer(spec: [
            "id": LayerID.focusedPathHighlight,
            "type": "line",
            "source": LayerID.geometrySource,
            "paint": [
                "line-color": mainColorExpr,
                "line-opacity": [
                    "case",
                    ["==", ["get", "condition"], "HEAVILY_DEGRADED"], 0.4,
                    ["==", ["get", "condition"], "DEGRADED"], 0.5,
                    0.4
                ] as [Any],
                "line-blur": 2,
                "line-width": [
                    "interpolate", ["linear"], ["zoom"],
                    8, 8,
                    11, 14
                ] as [Any]
            ],
            "layout": [
                "line-cap": "round",
                "line-join": "round"
            ]
        ], below: belowKey)

        // Dark border using line-gap-width. NOMINAL/DEGRADED/HEAVILY_DEGRADED
        // only, from zoom 11 up.
        try? provider.addLayer(spec: [
            "id": LayerID.focusedPathBorderLayer,
            "type": "line",
            "source": LayerID.geometrySource,
            "filter": ["in", ["get", "condition"], ["literal", ["NOMINAL", "DEGRADED", "HEAVILY_DEGRADED"]]] as [Any],
            "minzoom": 11,
            "paint": [
                "line-color": darkColorExpr,
                "line-gap-width": widthExpr,
                "line-width": 0.5
            ],
            "layout": [
                "line-cap": "round",
                "line-join": "round"
            ]
        ], below: belowKey)

        // Solid colored line for navigable conditions.
        try? provider.addLayer(spec: [
            "id": LayerID.focusedPathLayer,
            "type": "line",
            "source": LayerID.geometrySource,
            "filter": ["in", ["get", "condition"], ["literal", ["NOMINAL", "DEGRADED", "HEAVILY_DEGRADED"]]] as [Any],
            "paint": [
                "line-color": mainColorExpr,
                "line-width": widthExpr
            ],
            "layout": [
                "line-cap": "round",
                "line-join": "round"
            ]
        ], below: belowKey)

        // Dashed line for IMPASSABLE / UNKNOWN stretches.
        try? provider.addLayer(spec: [
            "id": LayerID.focusedPathDashLayer,
            "type": "line",
            "source": LayerID.geometrySource,
            "filter": ["in", ["get", "condition"], ["literal", ["IMPASSABLE", "UNKNOWN"]]] as [Any],
            "paint": [
                "line-color": mainColorExpr,
                "line-width": widthExpr,
                "line-dasharray": [2, 1]
            ],
            "layout": [
                "line-cap": "butt",
                "line-join": "round"
            ]
        ], below: belowKey)
    }

    /// Pushes the latest geometry into the focused-path overlay source,
    /// split into one LineString feature per condition segment. Each
    /// feature carries `condition` + `pathLength` properties so the style
    /// layers can key colors + widths off them. Falls back to a single
    /// UNKNOWN segment when condition metadata isn't available (e.g.
    /// tile-stitched fallback geometry).
    static func updateGeometryOverlay(
        provider: MapProvider,
        coords: [[Double]],
        conditions: [RoadConditionPoint]
    ) {
        guard coords.count >= 2 else {
            try? provider.setSourceData(id: LayerID.geometrySource, geoJSON: emptyFeatureCollection)
            return
        }

        let totalLen = totalLength(for: coords)
        let segments = conditionSegments(coords: coords, conditions: conditions)

        var features: [[String: Any]] = []
        features.reserveCapacity(segments.count)
        for segment in segments where segment.coords.count >= 2 {
            features.append([
                "type": "Feature",
                "properties": [
                    "condition": segment.condition,
                    "pathLength": totalLen
                ],
                "geometry": [
                    "type": "LineString",
                    "coordinates": segment.coords
                ]
            ])
        }

        try? provider.setSourceData(
            id: LayerID.geometrySource,
            geoJSON: [
                "type": "FeatureCollection",
                "features": features
            ]
        )
    }

    // MARK: - Distance markers

    /// Adds the distance-marker source + symbol layer. Styling mirrors the
    /// web composer's `distanceMarkerData` layer. MapLibre hosts get
    /// the bundled Noto Sans glyphs via `StylePreparation`; Mapbox hosts
    /// should override `text-font` to match their style's fontstack.
    static func installDistanceMarkerLayer(provider: MapProvider) {
        guard !provider.sourceExists(id: LayerID.distanceMarkerSource) else { return }

        try? provider.addSource(id: LayerID.distanceMarkerSource, spec: [
            "type": "geojson",
            "data": emptyFeatureCollection
        ])

        try? provider.addLayer(spec: [
            "id": LayerID.distanceMarkerLayer,
            "type": "symbol",
            "source": LayerID.distanceMarkerSource,
            "layout": [
                "symbol-placement": "point",
                "text-field": ["get", "label"] as [Any],
                "text-font": [distanceMarkerFontstack],
                "text-size": 12,
                "text-anchor": "right",
                "text-offset": [0, 1.5] as [Any],
                "text-justify": "center",
                "icon-allow-overlap": true,
                "icon-ignore-placement": true
            ],
            "paint": [
                "text-color": UIColor(red: 0x6f/255.0, green: 0x6f/255.0, blue: 0x6f/255.0, alpha: 1),
                "text-halo-color": UIColor(red: 0xfb/255.0, green: 0xfb/255.0, blue: 0xfb/255.0, alpha: 0xc5/255.0),
                "text-halo-width": 1.5
            ]
        ], below: nil)
    }

    /// Walks the road's cumulative haversine distance and emits one
    /// labeled point every `interval` meters into the distance-marker
    /// source. The adapter's symbol layer handles collision + placement.
    static func updateDistanceMarkers(
        provider: MapProvider,
        coords: [[Double]],
        interval: Double = distanceInterval
    ) {
        guard coords.count >= 2 else {
            try? provider.setSourceData(id: LayerID.distanceMarkerSource, geoJSON: emptyFeatureCollection)
            return
        }

        var cumulative: [Double] = [0]
        cumulative.reserveCapacity(coords.count)
        for i in 1..<coords.count {
            cumulative.append(cumulative[i - 1] + haversine(coords[i - 1], coords[i]))
        }
        let total = cumulative.last ?? 0

        var features: [[String: Any]] = []
        var target = interval
        var cursor = 0
        while target < total {
            while cursor < cumulative.count - 1, cumulative[cursor] < target {
                cursor += 1
            }
            let coord = coords[cursor]
            let km = target / 1000
            let label = (km.rounded() == km)
                ? "\(Int(km)) km"
                : String(format: "%.1f km", km)
            features.append([
                "type": "Feature",
                "properties": ["label": label],
                "geometry": [
                    "type": "Point",
                    "coordinates": coord
                ]
            ])
            target += interval
        }

        try? provider.setSourceData(
            id: LayerID.distanceMarkerSource,
            geoJSON: [
                "type": "FeatureCollection",
                "features": features
            ]
        )
    }

    // MARK: - Shared expressions

    /// Zoom-interpolated line width for the focused path. Steps up for
    /// roads longer than `widthStepUpThreshold` meters.
    private static var focusedPathWidthExpression: [Any] {
        let longCase: [Any] = [">", ["get", "pathLength"], widthStepUpThreshold]
        return [
            "interpolate", ["linear"], ["zoom"],
            8, ["case", longCase, 1.75, 1.25] as [Any],
            11, ["case", longCase, 3.25, 2.25] as [Any]
        ]
    }

    /// Condition → color `match` expression. `darkPalette` toggles between
    /// `Condition.uiColor` (main line) and `Condition.darkUIColor` (border).
    private static func conditionColorExpression(darkPalette: Bool) -> [Any] {
        let color: (Condition) -> UIColor = { darkPalette ? $0.darkUIColor : $0.uiColor }
        return [
            "match",
            ["get", "condition"],
            "NOMINAL", color(.nominal),
            "DEGRADED", color(.degraded),
            "HEAVILY_DEGRADED", color(.heavilyDegraded),
            "IMPASSABLE", color(.impassable),
            color(.unknown)
        ]
    }

    // MARK: - Segment construction

    private struct ConditionSegment {
        let coords: [[Double]]
        let condition: String
    }

    /// Splits `coords` into contiguous slices aligned with `conditions`.
    /// Each condition's `point` is the end coord of its segment (matches
    /// the server's `Path.getConditionSegments` contract). When no
    /// conditions are supplied, emits the full road as a single UNKNOWN
    /// segment.
    private static func conditionSegments(
        coords: [[Double]],
        conditions: [RoadConditionPoint]
    ) -> [ConditionSegment] {
        guard !conditions.isEmpty else {
            return [ConditionSegment(coords: coords, condition: "UNKNOWN")]
        }

        var segments: [ConditionSegment] = []
        var startIdx = 0
        for condition in conditions {
            guard let endIdx = coords.firstIndex(where: { $0 == condition.point }),
                  endIdx >= startIdx else { continue }
            let slice = Array(coords[startIdx...endIdx])
            if slice.count >= 2 {
                segments.append(ConditionSegment(coords: slice, condition: condition.condition))
            }
            startIdx = endIdx
        }
        if startIdx < coords.count - 1, let last = conditions.last {
            let tail = Array(coords[startIdx..<coords.count])
            if tail.count >= 2 {
                segments.append(ConditionSegment(coords: tail, condition: last.condition))
            }
        }
        return segments
    }

    private static func totalLength(for coords: [[Double]]) -> Double {
        guard coords.count >= 2 else { return 0 }
        var total: Double = 0
        for i in 1..<coords.count {
            total += haversine(coords[i - 1], coords[i])
        }
        return total
    }

    static func haversine(_ a: [Double], _ b: [Double]) -> Double {
        let R = 6_371_000.0
        let lon1 = a[0] * .pi / 180, lat1 = a[1] * .pi / 180
        let lon2 = b[0] * .pi / 180, lat2 = b[1] * .pi / 180
        let dLat = lat2 - lat1
        let dLon = lon2 - lon1
        let h = sin(dLat / 2) * sin(dLat / 2)
            + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * asin(min(1, sqrt(h)))
    }

    private static let emptyFeatureCollection: [String: Any] = [
        "type": "FeatureCollection",
        "features": []
    ]
}
