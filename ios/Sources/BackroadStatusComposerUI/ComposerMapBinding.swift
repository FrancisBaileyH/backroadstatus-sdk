import Foundation
import BackroadStatusMapKit

/// Everything the adapter view needs to stand up its native map view and
/// hand the composer a drivable `MapProvider`. Passed by
/// `BackroadStatusReportView` into the host-supplied `map:` builder.
///
/// The adapter's job is minimal: create the native map view, wrap it in a
/// `MapProvider`, and call `onReady(provider)` as soon as the view is
/// instantiated. The composer takes it from there — it registers its own
/// `onStyleLoaded` handler on the provider, drives overlay data, etc.
public struct ComposerMapBinding {
    /// Style URL the adapter should load (any glyph-preparation /
    /// per-SDK massaging is the adapter's responsibility).
    public let styleURL: URL
    /// Called exactly once by the adapter after it instantiates its native
    /// map view and wraps it in a `MapProvider`. Fires before the style
    /// loads so the composer can subscribe to `onStyleLoaded` in time.
    public let onReady: (MapProvider) -> Void

    public init(styleURL: URL, onReady: @escaping (MapProvider) -> Void) {
        self.styleURL = styleURL
        self.onReady = onReady
    }
}
