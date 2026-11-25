import Foundation

/// Ensures a Mapbox style has a `glyphs` URL set so text-based symbol
/// layers (the distance-marker overlay the composer installs) render
/// regardless of what the consumer's style ships.
///
/// On `prepare(originalStyleURL:)`:
///   - If the style already has a `glyphs` field → return the original URL
///     unchanged.
///   - Otherwise → fetch the style JSON, inject a `glyphs` URL pointing at
///     the SDK's bundled Noto Sans Regular PBFs (same approach as the
///     MapLibre adapter), write the modified JSON to a temp file, and
///     return that URL.
///
/// Using the bundled PBFs instead of `mapbox://fonts/mapbox/...` sidesteps
/// any uncertainty about whether `Noto Sans Regular` is in the Mapbox
/// account's default fontstack library and avoids a network fetch for
/// labels users will see on the composer's mini-map.
///
/// Failures at any step fall back to the original URL so the map still
/// loads — only distance-marker text is sacrificed.
public enum MapboxStylePreparation {

    /// Fontstack name — matches the directory under `Resources/Fonts/` and
    /// the `text-font` string the composer's distance-marker layer uses.
    public static let bundledFontstack = "Noto Sans Regular"

    public static func prepare(originalStyleURL: URL) async -> URL {
        guard let fontsRoot = Bundle.module.resourceURL?.appendingPathComponent("Fonts") else {
            return originalStyleURL
        }
        do {
            let (data, _) = try await URLSession.shared.data(from: originalStyleURL)
            guard var style = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return originalStyleURL
            }
            // Consumer already set glyphs — honor it and bail.
            if style["glyphs"] is String { return originalStyleURL }

            // `{fontstack}` and `{range}` are placeholders Mapbox substitutes
            // per request, not URL components to encode here.
            style["glyphs"] = "\(fontsRoot.absoluteString)/{fontstack}/{range}.pbf"

            let modified = try JSONSerialization.data(withJSONObject: style, options: [.fragmentsAllowed])
            let tempURL = FileManager.default.temporaryDirectory
                .appendingPathComponent("backroadstatus-mapbox-style-\(UUID().uuidString).json")
            try modified.write(to: tempURL)
            return tempURL
        } catch {
            print("[BackroadStatus Mapbox] Style glyphs injection failed (\(error.localizedDescription)); using original style URL")
            return originalStyleURL
        }
    }
}
