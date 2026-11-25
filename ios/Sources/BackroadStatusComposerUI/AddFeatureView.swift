import SwiftUI
import BackroadStatusCore
import BackroadStatusMapKit

/// Feature types the user is allowed to create. Matches the set exposed by
/// feature-add-modal.html. The "managed" PathFeatureType variants are
/// deliberately omitted — those are owned by scheduled jobs and should not be
/// created by hand.
enum CreatableFeatureType: String, CaseIterable, Identifiable, Codable {
    case openGate = "OPEN_GATE"
    case lockedGate = "LOCKED_GATE"
    case bridgeIn = "BRIDGE_IN"
    case bridgeOut = "BRIDGE_OUT"
    case impasse = "IMPASSE"
    case bypass = "BYPASS"
    case warning = "WARNING"
    case footBridge = "FOOT_BRIDGE"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .openGate:   return "Open Gate"
        case .lockedGate: return "Locked Gate"
        case .bridgeIn:   return "Bridge In"
        case .bridgeOut:  return "Bridge Out"
        case .impasse:    return "Impasse"
        case .bypass:     return "HC 4x4 Bypass"
        case .warning:    return "Warning"
        case .footBridge: return "Foot Bridge"
        }
    }

    var icon: Image { FeatureIcon.image(for: rawValue) }
}

/// A feature the user placed on the road in this composer session. Not yet
/// submitted — survives until the composer submits (becomes a
/// FeatureUpdateInput) or the user removes it.
struct CreatedFeature: Identifiable, Equatable, Codable {
    let id: UUID
    var type: CreatableFeatureType
    /// `[lon, lat]`. Always snapped to a vertex in the road's coordinate array.
    var position: [Double]
    var comment: String?

    init(id: UUID = UUID(), type: CreatableFeatureType, position: [Double], comment: String? = nil) {
        self.id = id
        self.type = type
        self.position = position
        self.comment = comment
    }
}

/// Picker sheet for staging a new road feature. Generic over the host-
/// supplied map view — the same `mapBuilder` the host passes to
/// `BackroadStatusReportView` flows through here so the picker map renders
/// via the host's chosen map SDK adapter.
struct AddFeatureView<Map: View>: View {
    let pathId: String
    let mapStyleURL: URL
    let roadCoordinates: [[Double]]
    /// Per-condition-segment anchors for the road. Threaded straight through
    /// to the picker map so its focused-path overlay colors match the main
    /// composer map above.
    let roadConditions: [RoadConditionPoint]
    /// Features the user has already added in this composer session. Shown
    /// on the picker map as context so they can place new ones with the
    /// others in view.
    let existingFeatures: [PlacedFeatureMarker]
    let onSave: (CreatedFeature) -> Void
    let mapBuilder: (ComposerMapBinding) -> Map

    @Environment(\.dismiss) private var dismiss

    @State private var selectedType: CreatableFeatureType?
    /// `[lon, lat]` of the dropped marker, already snapped to the nearest
    /// road vertex. Nil until the user taps the map.
    @State private var markerPosition: [Double]?
    @State private var comment: String = ""
    @State private var errorMessage: String?

    /// Shared driver installs the focused-path overlay + distance markers,
    /// and diff-pushes feature pins on the picker map. Same class the main
    /// composer uses — the picker map is a lightweight variant without
    /// slider markers / tile-stitching callbacks.
    @StateObject private var mapDriver = ComposerMapDriver()

    private var canSave: Bool {
        selectedType != nil && markerPosition != nil
    }

    /// Combined pin list fed to the driver: existing-feature context pins
    /// plus the user's in-progress "current" pin when one is placed. Both
    /// kinds share the same `PlacedFeatureMarker` type; the driver diffs
    /// on id so the "current" pin updates in place as the user taps or
    /// swaps type.
    private var allPickerMarkers: [PlacedFeatureMarker] {
        var out = existingFeatures
        if let markerPosition, let typeName = selectedType?.rawValue {
            out.append(PlacedFeatureMarker(
                id: AddFeatureConstants.currentMarkerId,
                typeName: typeName,
                position: markerPosition
            ))
        }
        return out
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    typePicker
                } header: {
                    Text("Feature Type")
                } footer: {
                    if let type = selectedType {
                        Text(type.displayName)
                    }
                }

                Section {
                    mapSection
                        .frame(height: 320)
                        .listRowInsets(EdgeInsets())
                } header: {
                    Text("Location")
                } footer: {
                    Text(selectedType == nil
                         ? "Pick a feature type above, then tap the map to drop a marker."
                         : "Tap the map to drop a marker. It snaps to the nearest point on the road.")
                }

                Section {
                    TextField("Describe the feature…", text: $comment, axis: .vertical)
                        .lineLimit(2...4)
                        .onChange(of: comment) { new in
                            if new.count > 200 { comment = String(new.prefix(200)) }
                        }
                } header: {
                    Text("Comment (Optional)")
                } footer: {
                    Text("Maximum 200 characters.")
                }

                if let err = errorMessage {
                    Section {
                        Text(err).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Add Feature")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }.disabled(!canSave)
                }
            }
        }
    }

    @ViewBuilder
    private var mapSection: some View {
        mapBuilder(ComposerMapBinding(
            styleURL: mapStyleURL,
            onReady: { [weak mapDriver] provider in
                mapDriver?.attach(provider: provider, pathId: pathId)
                mapDriver?.updateCoordinates(roadCoordinates, conditions: roadConditions)
                mapDriver?.updateCreatedFeatureMarkers(allPickerMarkers)

                // Picker-specific: tap drops a pin snapped to the nearest
                // road vertex. Composer's driver doesn't consume taps,
                // so we attach the handler here on top of attach().
                provider.onTap = { coord in
                    guard selectedType != nil else { return }
                    guard let snapped = nearestRoadCoord(toLon: coord.longitude, lat: coord.latitude) else { return }
                    markerPosition = snapped
                }
            }
        ))
        .onChange(of: markerPosition) { _ in
            mapDriver.updateCreatedFeatureMarkers(allPickerMarkers)
        }
        .onChange(of: selectedType) { _ in
            mapDriver.updateCreatedFeatureMarkers(allPickerMarkers)
        }
        .onChange(of: existingFeatures) { _ in
            mapDriver.updateCreatedFeatureMarkers(allPickerMarkers)
        }
        .onChange(of: roadCoordinates) { coords in
            mapDriver.updateCoordinates(coords, conditions: roadConditions)
        }
        .onChange(of: roadConditions) { conditions in
            mapDriver.updateCoordinates(roadCoordinates, conditions: conditions)
        }
    }

    private var typePicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(CreatableFeatureType.allCases) { type in
                    let isSelected = selectedType == type
                    Button {
                        selectedType = type
                        errorMessage = nil
                    } label: {
                        VStack(spacing: 4) {
                            type.icon
                                .resizable()
                                .scaledToFit()
                                .frame(width: 28, height: 28)
                            Text(type.displayName)
                                .font(.caption2)
                                .lineLimit(1)
                        }
                        .frame(minWidth: 78, minHeight: 60)
                        .padding(.vertical, 6)
                        .padding(.horizontal, 8)
                        .background(isSelected ? Color.accentColor.opacity(0.18) : Color(uiColor: .secondarySystemBackground))
                        .foregroundStyle(isSelected ? Color.accentColor : .primary)
                        .overlay(
                            RoundedRectangle(cornerRadius: 10)
                                .stroke(isSelected ? Color.accentColor : .clear, lineWidth: 1.5)
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.vertical, 2)
        }
        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
    }

    private func save() {
        guard let type = selectedType, let position = markerPosition else { return }
        let trimmed = comment.trimmingCharacters(in: .whitespacesAndNewlines)
        onSave(CreatedFeature(
            type: type,
            position: position,
            comment: trimmed.isEmpty ? nil : trimmed
        ))
        dismiss()
    }

    /// Snaps a tap point to the nearest road vertex so the stored position
    /// matches a coord in `geometry.coordinates` — same guarantee the
    /// condition slider makes.
    private func nearestRoadCoord(toLon lon: Double, lat: Double) -> [Double]? {
        guard !roadCoordinates.isEmpty else { return nil }
        var bestIdx = 0
        var bestDist = Double.infinity
        for (i, c) in roadCoordinates.enumerated() {
            let dLon = c[0] - lon
            let dLat = c[1] - lat
            let d = dLon * dLon + dLat * dLat
            if d < bestDist { bestDist = d; bestIdx = i }
        }
        return roadCoordinates[bestIdx]
    }
}

/// Non-generic companion for `AddFeatureView<Map>`'s static constants.
enum AddFeatureConstants {
    static let currentMarkerId = "current"
}
