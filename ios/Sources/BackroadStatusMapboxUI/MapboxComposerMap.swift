import SwiftUI
import MapboxMaps
import BackroadStatusMapKit
import BackroadStatusComposerUI

/// SwiftUI view that hosts a Mapbox `MapView`, wraps it in a
/// `MapboxMapProvider`, and surfaces the provider to the composer via
/// `ComposerMapBinding.onReady`. Drop this into
/// `BackroadStatusReportView`'s `map:` builder when the host app uses
/// Mapbox.
///
/// The Mapbox access token must be configured before this view is
/// constructed — either by setting `MapboxOptions.accessToken` at app
/// launch or by adding `MBXAccessToken` to the host app's Info.plist. The
/// adapter does not take a token itself.
///
/// ```swift
/// BackroadStatusReportView(roadId: ..., mapStyleURL: ...) { binding in
///     MapboxComposerMap(binding: binding)
/// }
/// ```
public struct MapboxComposerMap: View {
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

    func makeUIView(context: Context) -> MapView {
        let mapView = MapView(frame: .zero)

        // Build the provider BEFORE kicking off the style load so the
        // composer (which receives it via onReady) can register its
        // onStyleLoaded handler in time to see the first load event.
        let provider = MapboxMapProvider(mapView: mapView)
        context.coordinator.provider = provider
        binding.onReady(provider)

        Task { @MainActor in
            let preparedURL = await MapboxStylePreparation.prepare(originalStyleURL: binding.styleURL)
            try? await provider.loadStyle(url: preparedURL)
        }
        return mapView
    }

    func updateUIView(_ uiView: MapView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        /// Strong-holds the provider so it outlives SwiftUI diffs. The
        /// provider itself holds a weak reference back to the map view.
        var provider: MapboxMapProvider?
    }
}
