import SwiftUI
import UIKit

/// A multi-handle slider with per-segment coloring. Mirrors the BackroadStatus
/// web condition slider: thick colored track between consecutive handles,
/// handle borders tinted by the condition of their segment, optional tooltip
/// labels above each handle.
///
/// `segmentColors[i]` colors the track between handles `i` and `i+1`.
/// Count should be `max(0, values.count - 1)`; if short, segments default to
/// a neutral tint.
public struct SegmentedSliderView: View {
    @Binding public var values: [CGFloat]
    public let range: ClosedRange<CGFloat>
    public let segmentColors: [UIColor]
    public let snap: CGFloat
    /// Minimum distance (in the slider's value space) required between adjacent
    /// handles. Prevents segments from collapsing to zero width or handles
    /// from sharing the same point.
    public let minimumGap: CGFloat
    public let labels: [String]?
    public let trackHeight: CGFloat
    public let handleDiameter: CGFloat

    public init(
        values: Binding<[CGFloat]>,
        range: ClosedRange<CGFloat>,
        segmentColors: [UIColor] = [],
        snap: CGFloat = 0,
        minimumGap: CGFloat = 0,
        labels: [String]? = nil,
        trackHeight: CGFloat = 10,
        handleDiameter: CGFloat = 22
    ) {
        self._values = values
        self.range = range
        self.segmentColors = segmentColors
        self.snap = snap
        self.minimumGap = minimumGap
        self.labels = labels
        self.trackHeight = trackHeight
        self.handleDiameter = handleDiameter
    }

    public var body: some View {
        GeometryReader { geo in
            let usableWidth = max(0, geo.size.width - handleDiameter)
            ZStack(alignment: .topLeading) {
                labelRow(usableWidth: usableWidth)

                trackLayer(usableWidth: usableWidth)
                    .padding(.horizontal, handleDiameter / 2)
                    .padding(.top, labelRowHeight + 6)

                ForEach(values.indices, id: \.self) { i in
                    handle(index: i, usableWidth: usableWidth)
                        .padding(.top, labelRowHeight + 6)
                }
            }
        }
        .frame(height: handleDiameter + labelRowHeight + 6)
    }

    // MARK: Layers

    /// Labels are centered on their handles using `.position(x:y:)`, which
    /// places the view's center at the given point regardless of its intrinsic
    /// width. The earlier `alignmentGuide(.leading) { d.width/2 }` approach
    /// drifted when `.fixedSize()` + background interacted with the outer
    /// ZStack, leaving labels anchored at their leading edge.
    @ViewBuilder
    private func labelRow(usableWidth: CGFloat) -> some View {
        if let labels, labels.count == values.count {
            ZStack(alignment: .topLeading) {
                ForEach(values.indices, id: \.self) { i in
                    Text(labels[i])
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color(uiColor: .secondarySystemBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 4))
                        .fixedSize()
                        .position(
                            x: handleDiameter / 2 + x(for: values[i], usableWidth: usableWidth),
                            y: labelRowHeight / 2
                        )
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: labelRowHeight)
        } else {
            Color.clear.frame(height: 0)
        }
    }

    private func trackLayer(usableWidth: CGFloat) -> some View {
        ZStack(alignment: .topLeading) {
            // Base unselected track. systemFill is the semantic fill color
            // for inactive controls — adapts to both color schemes and keeps
            // enough contrast against the composer's grouped background,
            // where systemGray5 would sit almost on top of the card fill in
            // dark mode and disappear.
            Capsule()
                .fill(Color(uiColor: .systemFill))
                .frame(height: trackHeight)

            // Colored segments between consecutive handles
            ForEach(segmentIndices, id: \.self) { i in
                let from = x(for: values[i], usableWidth: usableWidth)
                let to = x(for: values[i + 1], usableWidth: usableWidth)
                let width = max(0, to - from)
                Capsule()
                    .fill(Color(uiColor: segmentColor(at: i)))
                    .frame(width: width, height: trackHeight)
                    .offset(x: from)
            }
        }
        .frame(height: trackHeight)
    }

    private func handle(index: Int, usableWidth: CGFloat) -> some View {
        let border = borderColor(forHandle: index)
        return Circle()
            .fill(Color.white)
            .overlay(Circle().strokeBorder(Color(uiColor: border), lineWidth: 3))
            .shadow(color: .black.opacity(0.15), radius: 1.5, x: 0, y: 1)
            .frame(width: handleDiameter, height: handleDiameter)
            .contentShape(Rectangle().inset(by: -10)) // enlarge hit area
            .offset(x: x(for: values[index], usableWidth: usableWidth), y: (trackHeight - handleDiameter) / 2)
            .gesture(dragGesture(for: index, usableWidth: usableWidth))
            .allowsHitTesting(true)
    }

    // MARK: Interaction

    private func dragGesture(for index: Int, usableWidth: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { drag in
                let raw = valueFromX(drag.location.x - handleDiameter / 2, usableWidth: usableWidth)
                apply(newValue: raw, to: index)
            }
    }

    private func apply(newValue raw: CGFloat, to index: Int) {
        var snapped = snapped(raw)
        snapped = max(range.lowerBound, min(range.upperBound, snapped))
        if index > 0 {
            snapped = max(snapped, values[index - 1] + minimumGap)
        }
        if index < values.count - 1 {
            snapped = min(snapped, values[index + 1] - minimumGap)
        }
        if values[index] != snapped {
            values[index] = snapped
        }
    }

    // MARK: Math

    private var labelRowHeight: CGFloat { labels == nil ? 0 : 20 }

    private var segmentIndices: Range<Int> {
        guard values.count >= 2 else { return 0..<0 }
        return 0..<(values.count - 1)
    }

    private func segmentColor(at index: Int) -> UIColor {
        guard index >= 0 && index < segmentColors.count else { return .systemGray3 }
        return segmentColors[index]
    }

    private func borderColor(forHandle index: Int) -> UIColor {
        if segmentColors.isEmpty { return .systemGray3 }
        if index == 0 { return segmentColors[0] }
        let segIndex = min(index - 1, segmentColors.count - 1)
        return segmentColors[segIndex]
    }

    private func x(for value: CGFloat, usableWidth: CGFloat) -> CGFloat {
        guard range.upperBound > range.lowerBound, usableWidth > 0 else { return 0 }
        let t = (value - range.lowerBound) / (range.upperBound - range.lowerBound)
        return t * usableWidth
    }

    private func valueFromX(_ x: CGFloat, usableWidth: CGFloat) -> CGFloat {
        guard usableWidth > 0 else { return range.lowerBound }
        let clamped = max(0, min(usableWidth, x))
        let t = clamped / usableWidth
        return range.lowerBound + t * (range.upperBound - range.lowerBound)
    }

    private func snapped(_ value: CGFloat) -> CGFloat {
        guard snap > 0 else { return value }
        return (value / snap).rounded() * snap
    }
}
