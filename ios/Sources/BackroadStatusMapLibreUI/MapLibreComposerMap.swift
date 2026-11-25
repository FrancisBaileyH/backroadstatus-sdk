import SwiftUI
import MapLibre
import BackroadStatusMapKit
import BackroadStatusComposerUI

/// SwiftUI View that hosts an `MLNMapView`, wraps it in a
/// `MapLibreMapProvider`, and surfaces the provider to the composer via
/// `ComposerMapBinding.onReady`. Drop this into
/// `BackroadStatusReportView`'s `map:` builder when the host app uses
/// MapLibre.
///
/// ```swift
/// BackroadStatusReportView(roadId: ..., mapStyleURL: ...) { binding in
///     MapLibreComposerMap(binding: binding)
/// }
/// ```
public struct MapLibreComposerMap: View {
    public let binding: ComposerMapBinding

    public init(binding: ComposerMapBinding) {
        self.binding = binding
    }

    public var body: some View {
        Representable(binding: binding)
    }
}

private struct Representable: UIViewRepresentable {
    let binding: ComposerMapBinding

    func makeUIView(context: Context) -> MLNMapView {
        let mapView = MLNMapView(frame: .zero)
        mapView.minimumZoomLevel = 4
        mapView.maximumZoomLevel = 18

        // Create the provider BEFORE the style load kicks off so the
        // composer (which receives it via onReady) can register its
        // onStyleLoaded handler in time to see the first load event.
        let provider = MapLibreMapProvider(mapView: mapView)
        context.coordinator.provider = provider
        binding.onReady(provider)

        Task { @MainActor in
            let preparedURL = await StylePreparation.prepare(originalStyleURL: binding.styleURL)
            try? await provider.loadStyle(url: preparedURL)
        }
        return mapView
    }

    func updateUIView(_ uiView: MLNMapView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        /// Strong-holds the provider so it outlives SwiftUI diffs. The
        /// provider itself holds a weak reference back to the map view.
        var provider: MapLibreMapProvider?
    }
}
