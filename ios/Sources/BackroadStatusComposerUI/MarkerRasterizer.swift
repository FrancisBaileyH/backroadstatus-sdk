import SwiftUI
import UIKit

/// Rasterizes the composer's marker visuals to `UIImage` so the
/// `MapProvider.addAnnotation` path can carry them cross-SDK. Doing the
/// render here (instead of per-adapter) means the two map backends host
/// pixel-identical markers.
///
/// Caches by variant key so rapid slider drags reuse the same image.
enum MarkerRasterizer {

    // MARK: - Slider dot

    /// Small colored dot with white border + soft drop shadow — mirrors
    /// the web composer's `maplibregl.Marker({ color, scale: 0.6 })`.
    static func sliderDot(color: UIColor, diameter: CGFloat = 16) -> UIImage {
        let key = "slider-\(color.hexString)-\(Int(diameter))"
        if let cached = cache[key] { return cached }

        let padding: CGFloat = 4 // breathing room for the drop shadow
        let totalSize = CGSize(width: diameter + padding * 2, height: diameter + padding * 2)
        let renderer = UIGraphicsImageRenderer(
            size: totalSize,
            format: makeFormat()
        )
        let image = renderer.image { ctx in
            let cg = ctx.cgContext
            let rect = CGRect(x: padding, y: padding, width: diameter, height: diameter)

            // Soft drop shadow.
            cg.saveGState()
            cg.setShadow(
                offset: CGSize(width: 0, height: 1),
                blur: 2,
                color: UIColor.black.withAlphaComponent(0.3).cgColor
            )
            cg.setFillColor(color.cgColor)
            cg.fillEllipse(in: rect)
            cg.restoreGState()

            // White border.
            cg.setStrokeColor(UIColor.white.cgColor)
            cg.setLineWidth(2)
            cg.strokeEllipse(in: rect.insetBy(dx: 1, dy: 1))
        }
        cache[key] = image
        return image
    }

    // MARK: - Feature icon

    /// Pre-renders the bundled feature-type SVG into a `UIImage` at the
    /// composer's standard marker size.
    @MainActor
    static func featureIcon(typeName: String, size: CGFloat = 32) -> UIImage {
        let key = "feature-\(typeName)-\(Int(size))"
        if let cached = cache[key] { return cached }
        let renderer = ImageRenderer(
            content: FeatureIcon.image(for: typeName)
                .resizable()
                .scaledToFit()
                .frame(width: size, height: size)
        )
        renderer.scale = UIScreen.main.scale
        let image = renderer.uiImage ?? UIImage()
        cache[key] = image
        return image
    }

    // MARK: - Cache

    /// Small bounded cache — composer rarely sees more than ~15 unique
    /// visual variants (5 condition colors × 2 sizes + a feature-type per
    /// pin), so a simple dictionary is plenty.
    private static var cache: [String: UIImage] = [:]

    private static func makeFormat() -> UIGraphicsImageRendererFormat {
        let format = UIGraphicsImageRendererFormat.default()
        format.opaque = false
        format.scale = UIScreen.main.scale
        return format
    }
}

private extension UIColor {
    /// Stable string key for the marker cache. Resolves dynamic colors
    /// against the current trait collection so light/dark variants of the
    /// same base color key separately.
    var hexString: String {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        resolvedColor(with: UITraitCollection.current).getRed(&r, green: &g, blue: &b, alpha: &a)
        return String(
            format: "%02X%02X%02X%02X",
            Int((r * 255).rounded()),
            Int((g * 255).rounded()),
            Int((b * 255).rounded()),
            Int((a * 255).rounded())
        )
    }
}
