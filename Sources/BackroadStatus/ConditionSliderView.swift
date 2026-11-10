import SwiftUI
import UIKit
import MultiSlider

/// SwiftUI wrapper for yonat/MultiSlider. The native BackroadStatus composer
/// uses this to let users drop multiple condition handles along a road.
///
/// `values` is in the slider's own coordinate space (typically step indices
/// along a road's coordinate array). The caller translates between step
/// indices and `{point, condition}` entries expected by the API.
public struct ConditionSliderView: UIViewRepresentable {
    @Binding public var values: [CGFloat]
    public let range: ClosedRange<CGFloat>
    public let tintColor: UIColor
    public let orientation: NSLayoutConstraint.Axis

    public init(
        values: Binding<[CGFloat]>,
        range: ClosedRange<CGFloat>,
        tintColor: UIColor = .systemGreen,
        orientation: NSLayoutConstraint.Axis = .horizontal
    ) {
        self._values = values
        self.range = range
        self.tintColor = tintColor
        self.orientation = orientation
    }

    public func makeUIView(context: Context) -> MultiSlider {
        let slider = MultiSlider()
        slider.orientation = orientation
        slider.minimumValue = range.lowerBound
        slider.maximumValue = range.upperBound
        slider.value = values
        slider.outerTrackColor = .systemGray5
        slider.tintColor = tintColor
        slider.addTarget(
            context.coordinator,
            action: #selector(Coordinator.valueChanged(_:)),
            for: .valueChanged
        )
        return slider
    }

    public func updateUIView(_ uiView: MultiSlider, context: Context) {
        if uiView.value != values {
            uiView.value = values
        }
        if uiView.minimumValue != range.lowerBound {
            uiView.minimumValue = range.lowerBound
        }
        if uiView.maximumValue != range.upperBound {
            uiView.maximumValue = range.upperBound
        }
        uiView.tintColor = tintColor
        uiView.orientation = orientation
    }

    public func makeCoordinator() -> Coordinator {
        Coordinator(values: $values)
    }

    public final class Coordinator: NSObject {
        let values: Binding<[CGFloat]>
        init(values: Binding<[CGFloat]>) { self.values = values }

        @objc func valueChanged(_ slider: MultiSlider) {
            values.wrappedValue = slider.value
        }
    }
}
