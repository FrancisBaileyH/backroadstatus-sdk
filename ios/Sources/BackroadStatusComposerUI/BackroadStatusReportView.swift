import SwiftUI
import BackroadStatusMapKit

/// Drop-in composer for reporting a road's condition. Present this when
/// the user taps a road on your map.
///
/// The view fetches the road's geometry and features from the SDK on
/// appear (`BackroadStatus.shared.cachedRoad(id:)` first, then
/// `BackroadStatus.shared.road(id:)`), so the caller only needs the road
/// ID and a style URL.
///
/// ### Map backend
///
/// The composer is map-SDK agnostic. Pass a `map:` view builder that
/// returns your preferred adapter — e.g. `MapLibreComposerMap(binding:)`
/// from `BackroadStatusMapLibreUI`, or a Mapbox equivalent. The adapter
/// wires its native map view, creates a `MapProvider`, and hands it back
/// via `binding.onReady(_:)`.
///
/// See `BackroadStatusMapLayers` for the layer / source IDs the style
/// must include for the composer's road-focus to work.
public struct BackroadStatusReportView<Map: View>: View {
    let roadId: String
    let mapStyleURL: URL
    let roadName: String?
    let mapBuilder: (ComposerMapBinding) -> Map
    let onRevert: (() -> Void)?

    public init(
        roadId: String,
        mapStyleURL: URL,
        roadName: String? = nil,
        onRevert: (() -> Void)? = nil,
        @ViewBuilder map mapBuilder: @escaping (ComposerMapBinding) -> Map
    ) {
        self.roadId = roadId
        self.mapStyleURL = mapStyleURL
        self.roadName = roadName
        self.onRevert = onRevert
        self.mapBuilder = mapBuilder
    }

    public var body: some View {
        ReportComposerView(
            roadId: roadId,
            initialRoadName: roadName,
            mapStyleURL: mapStyleURL,
            onRevert: onRevert,
            mapBuilder: mapBuilder
        )
    }
}
