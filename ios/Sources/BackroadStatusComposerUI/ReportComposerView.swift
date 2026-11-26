import SwiftUI
import UIKit
import PhotosUI
import BackroadStatusCore
import BackroadStatusMapKit

/// Mirrors the behavior of condition-slider.js from the web form, minus the
/// live map-preview updates. User selects conditions from a dropdown; each
/// pick adds a handle to the slider. Handles can be dragged to reposition.
/// Chips below the slider show each condition in order; tapping the × on a
/// chip removes that condition (merges neighbors if they match).
///
/// Submit builds the ordered `conditionRatings` array; UNKNOWN is auto-prepended
/// at the start handle's point if the user started past 0 m.
///
/// Generic over the host-supplied map view so the composer stays
/// map-SDK-agnostic. The `mapBuilder` closure produces a `View` that
/// hosts a native map and calls `ComposerMapBinding.onReady` with a
/// `MapProvider` the composer's `ComposerMapDriver` drives.
struct ReportComposerView<Map: View>: View {
    /// Current road the composer is anchored to. Starts at whatever the
    /// consumer passed to `init(roadId:)` and may be swapped at runtime when
    /// the user chooses to resume a draft that belongs to a different road.
    @State private var roadId: String
    let initialRoadName: String?
    let mapStyleURL: URL
    let mapBuilder: (ComposerMapBinding) -> Map
    /// Non-nil when the composer was opened to edit an existing pending
    /// submission. Triggers a third "Revert" option in the Cancel
    /// confirmation dialog that calls this to put the original queue
    /// entry back as-is. Nil for fresh-create flows.
    let onRevert: (() -> Void)?

    @Environment(\.dismiss) private var dismiss

    /// True while the "Sign in to continue" alert is visible. Set when the
    /// composer discovers there's no auth session — either because the user
    /// never signed in, or because the SDK's 401-retry path exhausted the
    /// refresh token mid-request and cleared local state. The composer keeps
    /// showing whatever cached geometry it had so the UX degrades to
    /// "loading + prompt" instead of "error card you can't act on".
    @State private var showSignInPrompt: Bool = false

    /// Drives the inline map view: installs layers, diff-pushes markers,
    /// runs the tile-stitching poll. Attached once the adapter calls
    /// `binding.onReady(_:)` with a `MapProvider`.
    @StateObject private var mapDriver = ComposerMapDriver()

    @State private var roadName: String
    @State private var description: String = ""
    @State private var reportDate: Date = Date()
    @State private var startStep: CGFloat = 0
    @State private var conditions: [ConditionEntry] = []
    @State private var sliderValues: [CGFloat] = [0]
    @State private var busy: Bool = false
    @State private var errorMessage: String?
    /// Friendly, user-facing message when `loadFullRoad` finished with
    /// neither a cached nor live `RoadDetail`. Non-nil surfaces the
    /// inline error card in the Conditions section where the loading
    /// spinner would otherwise sit. Cleared the moment any source lands.
    @State private var roadLoadError: String?
    @State private var submittedId: String?
    @State private var queuedMessage: String?
    @ObservedObject private var submissionQueue = SubmissionQueue.shared
    @ObservedObject private var draftStore = DraftStore.shared

    /// Stable idempotency key across this draft's lifetime. Fresh UUID on a
    /// blank composer; overwritten from the stored draft on resume so a
    /// queued submission can retry forever without creating duplicates.
    @State private var clientSubmissionId: String = UUID().uuidString
    /// Existing draft id (preserved across saves) and when it was first
    /// created. Set from storage on resume; stay at these defaults on a
    /// blank composer until the first save.
    @State private var draftId: UUID = UUID()
    @State private var draftCreatedAt: Date = Date()
    /// True once we know this composer "owns" the single draft slot. False
    /// when an existing draft belongs to a different road — we don't
    /// overwrite the other road's work until the user explicitly discards.
    @State private var draftActive: Bool = false
    /// Pending debounced disk write; swapped on each change to coalesce
    /// rapid keystrokes into one write.
    @State private var pendingDraftSave: DispatchWorkItem?
    /// Road detail currently cached into the draft. Non-nil once a live
    /// fetch has succeeded (or we loaded one from the resumed draft),
    /// at which point save-and-resume works offline.
    @State private var cachedRoadDetail: RoadDetail?
    /// The off-road draft (if any) we're asking the user to decide about.
    /// Non-nil surfaces the alert on composer open.
    @State private var pendingResumePrompt: Draft?
    /// Toggled when the user taps Cancel with in-progress work — surfaces a
    /// confirmation dialog so they can pick "Save for later" vs "Discard".
    @State private var showCancelDialog: Bool = false
    @State private var geometry: RoadGeometry
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var photos: [DraftPhotoState] = []
    @State private var discoveredFeatures: [RoadFeatureDetail] = []
    @State private var featureStatuses: [String: FeatureStatus] = [:]
    /// New type the user picked for a Changed feature (keyed by feature id).
    /// Nil / missing means "keep the original type". Only consulted when the
    /// feature's status is `.changed` and its type has transition options.
    @State private var featureChangeTypes: [String: String] = [:]
    /// Comment the user typed for a Changed feature (keyed by feature id).
    @State private var featureChangeComments: [String: String] = [:]
    /// Tracks the "No longer present" toggle inside the Changed flow.
    /// Keyed by feature id; missing / false both mean "still there."
    @State private var featureRemoved: [String: Bool] = [:]
    @State private var createdFeatures: [CreatedFeature] = []
    @State private var showAddFeatureSheet: Bool = false
    @State private var showConditionsInfo: Bool = false
    /// Modes of travel the user ticked for this trip. Optional; capped at
    /// `Composer.maxVehicles`. Order preserved so chips render in the order
    /// the user picked them.
    @State private var vehicles: [VehicleCategory] = []
    /// Current road conditions as of the time the API returned them. Powers
    /// verify mode's client-side conditionRatings computation — binding the
    /// user's "conditions unchanged" answer to what they saw on the map,
    /// not what the server happens to have when the request is processed.
    @State private var roadConditions: [RoadConditionPoint] = []
    @State private var verifyMode: Bool = false
    @State private var verifyValues: [CGFloat] = [0, 0]

    // Composer tunables. Stored on the non-generic `Composer` enum below
    // because Swift doesn't allow static stored properties on generic
    // types — the composer became `<Map: View>` when we abstracted the
    // map backend.

    /// Slider is step-index based, matching condition-slider.js on the web:
    /// values 0 … (coords.count - 1), one position per road coord.
    private var hasGeometry: Bool {
        geometry.coordinates.count >= Int(Composer.marginSteps + 1)
    }

    private var maxStepIndex: CGFloat {
        guard !geometry.coordinates.isEmpty else { return 0 }
        return CGFloat(geometry.coordinates.count - 1)
    }

    init(
        roadId: String,
        initialRoadName: String?,
        mapStyleURL: URL,
        onRevert: (() -> Void)? = nil,
        @ViewBuilder mapBuilder: @escaping (ComposerMapBinding) -> Map
    ) {
        _roadId = State(initialValue: roadId)
        self.initialRoadName = initialRoadName
        self.mapStyleURL = mapStyleURL
        self.onRevert = onRevert
        self.mapBuilder = mapBuilder
        _roadName = State(initialValue: initialRoadName ?? "")
        // Pre-seed the resume prompt synchronously so an off-road draft
        // shows the alert on the very first render — no brief flash of the
        // new road's loading state behind the alert.
        if let existing = DraftStore.peekDraftOnDisk(), existing.roadId != roadId {
            _pendingResumePrompt = State(initialValue: existing)
        }
        // Geometry starts empty. `loadFullRoad()` populates it from
        // `GeometryStore` (pre-downloaded with the offline pack) on cache
        // hit, then background-refreshes from the live API.
        _geometry = State(initialValue: RoadGeometry(coordinates: [], cumulativeDistances: []))
        _sliderValues = State(initialValue: [0])
    }

    @MainActor
    private func loadFullRoad() async {
        // Pre-downloaded offline geometry first — carries canonical coords,
        // so condition-rating points we build from this match the server's
        // record exactly. Doesn't preempt the API: even on a cache hit we
        // refresh in the background to pick up any condition / feature
        // changes since the pack was downloaded.
        if let cached = BackroadStatus.shared.cachedRoad(id: roadId) {
            applyRoadDetail(cached)
            roadLoadError = nil
        }
        // Signed-out openers get the auth prompt instead of firing a doomed
        // API call. Keeps the composer visible (with cached geometry if any)
        // and lets the alert carry the "sign in to continue" action.
        guard BackroadStatus.shared.isAuthenticated else {
            showSignInPrompt = true
            return
        }
        do {
            let detail = try await BackroadStatus.shared.road(id: roadId)
            applyRoadDetail(detail)
            roadLoadError = nil
        } catch {
            print("[Composer] API road fetch failed: \(error.localizedDescription) — isAuthenticated=\(BackroadStatus.shared.isAuthenticated) isAuthFailure=\(Self.isAuthFailure(error))")
            // Any auth-flavored failure → prompt sign-in rather than drop an
            // unactionable error card. Covers: the SDK clearing state after
            // a terminal invalid_grant (isAuthenticated becomes false), *and*
            // the softer failure modes where AppAuth's error delegate
            // doesn't fire — refresh hit a 500 / network blip / non-standard
            // oauth error — so isAuthenticated stays true but the user still
            // can't make authed calls.
            if Self.isAuthFailure(error) || !BackroadStatus.shared.isAuthenticated {
                showSignInPrompt = true
                return
            }
            // Non-auth failures: only surface the error if we have nothing
            // to work with. A cache hit that just failed to refresh isn't
            // an error state — the user can still compose against the
            // cached snapshot.
            if !hasGeometry {
                roadLoadError = Self.friendlyRoadLoadError(error)
            }
        }
    }

    /// True for errors that mean "you need to re-authenticate", regardless of
    /// whether the SDK has already cleared local auth state. Split out so
    /// the catch block doesn't have to re-derive the matcher inline.
    private static func isAuthFailure(_ error: Error) -> Bool {
        guard let api = error as? BackroadStatusError else { return false }
        switch api {
        case .notAuthenticated, .oauthFailed:
            return true
        case .apiError(let status, _):
            return status == 401 || status == 403
        case .simulatedOffline, .notConfigured:
            return false
        }
    }

    /// Dismisses the composer when the user cancels the sign-in prompt. Uses
    /// the Environment dismiss action so the containing sheet /
    /// NavigationLink closes naturally — host apps don't need to wire
    /// anything up.
    @MainActor
    private func cancelSignIn() {
        dismiss()
    }

    /// Resolves AppAuth's presenting-VC anchor from the composer's SwiftUI
    /// context and runs the OAuth flow. On success, retries `loadFullRoad()`
    /// so the composer transitions from "prompt" straight to "loaded". On
    /// any failure — user cancelled the OAuth sheet, network died,
    /// anything — we close the composer: looping the alert on an unhappy
    /// path feels hostile and the host app can re-present the composer if
    /// the user asks again.
    @MainActor
    private func beginSignIn() async {
        guard let presenter = BackroadStatus.topmostPresentingViewController() else {
            cancelSignIn()
            return
        }
        do {
            try await BackroadStatus.shared.signIn(presenting: presenter)
            await loadFullRoad()
        } catch {
            print("[Composer] Sign-in failed: \(error.localizedDescription)")
            cancelSignIn()
        }
    }

    /// Turns the underlying SDK error into something actionable for the
    /// composer's inline "couldn't load road" indicator. Mirrors the
    /// shape of `friendlySubmitError` but with messaging about *reading*
    /// the road rather than submitting a bulletin.
    private static func friendlyRoadLoadError(_ error: Error) -> String {
        if let api = error as? BackroadStatusError {
            switch api {
            case .apiError(let status, _):
                switch status {
                case 401, 403: return "You're not authorized to view this road. Try signing in again."
                case 404: return "This road couldn't be found."
                case 400..<500: return "The server couldn't return this road. Try again shortly."
                case 500..<600: return "The server ran into a problem loading this road. Try again shortly."
                default: return "Couldn't load this road. Check your connection and try again."
                }
            case .notAuthenticated: return "You're not signed in."
            case .simulatedOffline, .notConfigured, .oauthFailed:
                return api.errorDescription ?? error.localizedDescription
            }
        }
        // URLSession offline / timeout errors flow through here. The
        // "can't reach the server" message is right for both.
        return "Couldn't reach the server to load this road. Download the area for offline use, or try again when you're online."
    }

    /// Centralized road-detail application so cached (draft) and live (API)
    /// sources update the same state. Called from `loadFullRoad` on API
    /// success and from `applyDraft` when resuming with a snapshot.
    @MainActor
    private func applyRoadDetail(_ detail: RoadDetail) {
        if roadName.isEmpty || roadName != detail.name { roadName = detail.name }
        adopt(RoadGeometry.from(coordinates: detail.coordinates))
        mergeFeatures(detail.features)
        roadConditions = detail.conditions
        cachedRoadDetail = detail
    }

    /// Union-by-id merge of newly-discovered features with whatever we already
    /// have. Both the API fetch and the mini-map's tile query feed through here.
    @MainActor
    private func mergeFeatures(_ newFeatures: [RoadFeatureDetail]) {
        var byId: [String: RoadFeatureDetail] = [:]
        for f in discoveredFeatures { byId[f.id] = f }
        var added = 0
        for f in newFeatures where byId[f.id] == nil {
            byId[f.id] = f
            added += 1
        }
        if added > 0 {
            discoveredFeatures = Array(byId.values).sorted { $0.displayName < $1.displayName }
        }
    }

    @MainActor
    private func adopt(_ newGeometry: RoadGeometry) {
        guard newGeometry.coordinates.count > geometry.coordinates.count else { return }
        geometry = newGeometry
        // Keep verify handles inside the new coord range. First time we get
        // geometry, seed the second handle at the midpoint — that's usually
        // closer to what the user wants to express than "I drove the whole
        // thing," and gives them a natural starting point to drag from.
        let maxIdx = max(0, CGFloat(newGeometry.coordinates.count - 1))
        if verifyValues == [0, 0] {
            verifyValues = [0, (maxIdx / 2).rounded()]
        } else {
            verifyValues = verifyValues.map { min($0, maxIdx) }
        }
    }

    private var canSubmit: Bool {
        // Don't require photos to have landed on the server — the queue
        // handles pending uploads. Do block while any photo is actively
        // uploading so we don't race the queue into double-uploading the
        // same bytes.
        let captionsOk = photos.allSatisfy { Composer.captionRange.contains($0.caption.count) }
        let nothingInFlight = photos.allSatisfy { !$0.isUploading }
        return description.count >= 8
            && captionsOk
            && nothingInFlight
            && !busy
            && submittedId == nil
            && queuedMessage == nil
    }

    // maxPhotos lives on `Composer` below — see comment above.

    /// Dropdown excludes only the *last* picked condition — contiguous
    /// duplicates aren't allowed (good → good), but repeats with another
    /// condition between are fine (good → degraded → good).
    private var pickableConditions: [Condition] {
        let last = conditions.last?.condition
        return Condition.userPickable.filter { $0 != last }
    }

    private var canAddMore: Bool {
        guard hasGeometry else { return false }
        guard conditions.count < Composer.maxConditions else { return false }
        guard !pickableConditions.isEmpty else { return false }
        let lastStep = conditions.last?.step ?? startStep
        return lastStep + Composer.marginSteps <= maxStepIndex
    }

    var body: some View {
        NavigationStack {
            Group {
                if pendingResumePrompt != nil {
                    // Keep the composer hidden until the user resolves the
                    // Resume / Discard prompt — otherwise they'd see Road B's
                    // loading state flash behind the alert for the half-second
                    // before `.task` fires.
                    Color(uiColor: .systemGroupedBackground).ignoresSafeArea()
                } else {
                    composerContent
                }
            }
            .navigationTitle("Create report")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { handleCancelTap() }.disabled(busy)
                }
                if pendingResumePrompt == nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(busy ? "Submitting…" : "Submit") {
                            Task { await submit() }
                        }
                        .disabled(!canSubmit)
                    }
                }
                // TextEditor swallows scroll gestures, so .scrollDismissesKeyboard
                // doesn't reach it. A Done button on the keyboard accessory bar
                // gives every text field a reliable escape hatch.
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") {
                        UIApplication.shared.sendAction(
                            #selector(UIResponder.resignFirstResponder),
                            to: nil, from: nil, for: nil
                        )
                    }
                }
            }
            .alert(
                "Unfinished draft",
                isPresented: Binding(
                    get: { pendingResumePrompt != nil },
                    set: { if !$0 { pendingResumePrompt = nil } }
                ),
                presenting: pendingResumePrompt
            ) { draft in
                Button("Resume") { resumeDraft(draft) }
                Button("Discard", role: .destructive) { discardOtherDraftAndContinue() }
                Button("Cancel", role: .cancel) { dismiss() }
            } message: { draft in
                let name = draft.roadName ?? "another road"
                Text("You have an unfinished report for \(name). Resume where you left off, or discard it to start a new report here?")
            }
            .confirmationDialog(
                "Save your work?",
                isPresented: $showCancelDialog,
                titleVisibility: .visible
            ) {
                Button("Save for later") { saveDraftAndDismiss() }
                Button("Discard draft", role: .destructive) {
                    discardDraft()
                    dismiss()
                }
                Button("Keep editing", role: .cancel) {}
            }
        }
    }

    @ViewBuilder
    private var composerContent: some View {
        GeometryReader { geo in
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 0) {
                        // Edge-to-edge map — pulled out of the grouped-section
                        // helper below so it bleeds to the screen edges.
                        // The host-supplied `mapBuilder` returns a view that
                        // wraps its native map SDK and hands the composer's
                        // driver a MapProvider via binding.onReady.
                        mapBuilder(ComposerMapBinding(
                            styleURL: mapStyleURL,
                            onReady: { [weak mapDriver] provider in
                                mapDriver?.attach(provider: provider, pathId: roadId)
                                mapDriver?.updateCoordinates(geometry.coordinates, conditions: roadConditions)
                                mapDriver?.updateSliderMarkers(sliderMarkers)
                                mapDriver?.updateCreatedFeatureMarkers(createdFeatureMarkers)
                            }
                        ))
                        .frame(width: geo.size.width, height: geo.size.height * 0.5)
                        .onChange(of: geometry.coordinates) { newCoords in
                            mapDriver.updateCoordinates(newCoords, conditions: roadConditions)
                        }
                        .onChange(of: roadConditions) { newConditions in
                            mapDriver.updateCoordinates(geometry.coordinates, conditions: newConditions)
                        }
                        .onChange(of: sliderMarkers) { newMarkers in
                            mapDriver.updateSliderMarkers(newMarkers)
                        }
                        .onChange(of: createdFeatureMarkers) { newFeatures in
                            mapDriver.updateCreatedFeatureMarkers(newFeatures)
                        }

                        VStack(spacing: 24) {
                            if let err = errorMessage {
                                submitErrorCard(err)
                                    .id(Composer.submitErrorAnchorId)
                                    .transition(.opacity)
                            }

                            groupedSection("Road") {
                                Text(roadName.isEmpty ? "Loading…" : roadName).font(.headline)
                            }

                            conditionsSection

                            groupedSection("Report date") {
                                DatePicker("Date", selection: $reportDate, in: ...Date(), displayedComponents: .date)
                            }

                            groupedSection("Description", footer: {
                                Text("At least 8 characters. 1000 max.")
                                    .foregroundStyle(description.count < 8 ? .red : .secondary)
                            }) {
                                ZStack(alignment: .topLeading) {
                                    TextEditor(text: $description)
                                        .frame(minHeight: 140)
                                        .scrollContentBackground(.hidden)
                                    if description.isEmpty {
                                        Text("Describe the road conditions you found…")
                                            .foregroundStyle(.secondary)
                                            .padding(.horizontal, 5)
                                            .padding(.vertical, 8)
                                            .allowsHitTesting(false)
                                    }
                                }
                            }

                            vehiclesSection

                            featuresSection

                            photosSection

                            if submittedId != nil {
                                Label("Report submitted", systemImage: "checkmark.circle.fill")
                                    .foregroundStyle(.green)
                                    .padding(.horizontal, 16)
                            }

                            if let message = queuedMessage {
                                Label(message, systemImage: "arrow.up.circle.badge.clock")
                                    .foregroundStyle(.orange)
                                    .padding(.horizontal, 16)
                            }

                            draftFooter
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 20)
                        .animation(.easeInOut(duration: 0.2), value: errorMessage)
                    }
                }
                .background(Color(uiColor: .systemGroupedBackground))
                .scrollDismissesKeyboard(.interactively)
                .safeAreaInset(edge: .top, spacing: 0) {
                    if !submissionQueue.isNetworkAvailable {
                        offlineBanner
                    }
                }
                .onChange(of: errorMessage) { newValue in
                    guard newValue != nil else { return }
                    // Let the card insert first, then scroll it just below the
                    // map so the whole error is visible.
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                        withAnimation(.easeInOut(duration: 0.3)) {
                            proxy.scrollTo(Composer.submitErrorAnchorId, anchor: .top)
                        }
                    }
                }
            }
            .interactiveDismissDisabled(busy)
            .task {
                loadDraftIfAny()
                await loadFullRoad()
            }
            .alert("Sign in to continue", isPresented: $showSignInPrompt) {
                Button("Cancel", role: .cancel) { cancelSignIn() }
                Button("Sign In") { Task { await beginSignIn() } }
            } message: {
                Text("You need to be signed in to view road details and submit a bulletin.")
            }
            .onChange(of: draftFingerprint) { _ in
                scheduleDraftSave()
            }
            .onChange(of: roadId) { _ in
                Task { await loadFullRoad() }
            }
            .onChange(of: submissionQueue.isNetworkAvailable) { online in
                if online { reuploadPendingPhotosIfOnline() }
            }
            .sheet(isPresented: $showAddFeatureSheet) {
                AddFeatureView(
                    pathId: roadId,
                    mapStyleURL: mapStyleURL,
                    roadCoordinates: geometry.coordinates,
                    roadConditions: roadConditions,
                    existingFeatures: createdFeatureMarkers,
                    onSave: { createdFeatures.append($0) },
                    mapBuilder: mapBuilder
                )
            }
            .sheet(isPresented: $showConditionsInfo) {
                ConditionsInfoSheet()
            }
        }
    }

    // MARK: Map markers

    /// Mirrors `setSliderMarker(...)` in condition-slider.js: one marker per
    /// condition handle, plus a start marker colored with the first
    /// condition's color. In verify mode it degenerates to two gray markers
    /// at the drove-from / drove-to points.
    private var sliderMarkers: [SliderMarker] {
        guard hasGeometry else { return [] }
        if verifyMode {
            guard verifyValues.count == 2 else { return [] }
            var markers: [SliderMarker] = []
            if let from = coord(atStep: Int(verifyValues[0])) {
                markers.append(SliderMarker(id: "verify-from", position: from, color: .systemBlue))
            }
            if let to = coord(atStep: Int(verifyValues[1])) {
                markers.append(SliderMarker(id: "verify-to", position: to, color: .systemBlue))
            }
            return markers
        }
        guard !conditions.isEmpty else { return [] }
        var markers: [SliderMarker] = []
        if let startColor = conditions.first?.condition.uiColor,
           let startPos = coord(atStep: Int(startStep)) {
            markers.append(SliderMarker(id: "start", position: startPos, color: startColor))
        }
        for entry in conditions {
            guard let pos = coord(atStep: Int(entry.step)) else { continue }
            markers.append(SliderMarker(id: entry.id.uuidString, position: pos, color: entry.condition.uiColor))
        }
        return markers
    }

    // MARK: Features

    /// One row in the merged features list — either a discovered feature from
    /// the server or one the user placed via Add Feature. Keeping them in a
    /// single enum lets us sort + render both kinds in a single pass so a
    /// user-added feature at 0.8 km appears above a discovered one at 1.2 km.
    private enum FeatureRow: Identifiable {
        case discovered(RoadFeatureDetail)
        case created(CreatedFeature)

        var id: String {
            switch self {
            case .discovered(let f): return "d-\(f.id)"
            case .created(let f):    return "c-\(f.id.uuidString)"
            }
        }

        var position: [Double] {
            switch self {
            case .discovered(let f): return f.position
            case .created(let f):    return f.position
            }
        }
    }

    /// Discovered + user-created features, sorted by distance along the road.
    /// Features whose coord isn't found in `geometry.coordinates` (shouldn't
    /// happen in practice) sink to the end so they still render.
    private var orderedFeatureRows: [FeatureRow] {
        let rows = discoveredFeatures.map(FeatureRow.discovered) + createdFeatures.map(FeatureRow.created)
        guard hasGeometry else { return rows }
        return rows.sorted { distanceOrderIndex(for: $0.position) < distanceOrderIndex(for: $1.position) }
    }

    private func distanceOrderIndex(for position: [Double]) -> Int {
        geometry.coordinates.firstIndex(where: { $0 == position }) ?? Int.max
    }

    /// Positions + types of user-created features, flattened to a tiny value
    /// type the map coordinators can diff. Passed to both the composer's
    /// mini-map preview and the Add Feature sheet's picker map so the user
    /// can see what they've already placed.
    private var createdFeatureMarkers: [PlacedFeatureMarker] {
        createdFeatures.map { PlacedFeatureMarker(id: $0.id.uuidString, typeName: $0.type.rawValue, position: $0.position) }
    }

    @ViewBuilder
    private var featuresSection: some View {
        groupedSection("Road Features", footer: {
            if discoveredFeatures.isEmpty && createdFeatures.isEmpty {
                Text("Add gates, bridges, or warnings you came across on this road.")
            } else if !discoveredFeatures.isEmpty {
                Text("Mark each discovered feature as you verified it, or pick Changed to update its type / add a comment.")
            } else {
                Text("New features will be submitted with your report.")
            }
        }) {
            ForEach(Array(orderedFeatureRows.enumerated()), id: \.element.id) { index, row in
                if index > 0 { Divider() }
                switch row {
                case .discovered(let feature):
                    discoveredFeatureRow(feature: feature)
                case .created(let feature):
                    createdFeatureRow(feature: feature)
                }
            }

            if !orderedFeatureRows.isEmpty {
                Divider()
            }
            Button {
                showAddFeatureSheet = true
            } label: {
                Label("Add Feature", systemImage: "plus.circle")
            }
            .disabled(!hasGeometry)
        }
    }

    @ViewBuilder
    private func createdFeatureRow(feature: CreatedFeature) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .center, spacing: 10) {
                feature.type.icon
                    .resizable()
                    .scaledToFit()
                    .frame(width: 24, height: 24)
                featureTitleLine(
                    name: feature.type.displayName,
                    position: feature.position,
                    badge: "New",
                    badgeColor: .green
                )
                .layoutPriority(1)
                Spacer(minLength: 4)
                Button {
                    createdFeatures.removeAll { $0.id == feature.id }
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
            if let comment = feature.comment {
                Text(comment)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
            }
        }
    }

    @ViewBuilder
    private func discoveredFeatureRow(feature: RoadFeatureDetail) -> some View {
        let status = featureStatuses[feature.id] ?? .unverified
        let options = FeatureTransition.options(for: feature.type)
        // In .changed state the icon tracks the user's current type pick so
        // the row reflects the transition immediately (e.g. open-gate ↔
        // locked-gate glyph swap on toggle). Falls back to the original
        // type for every other state.
        let effectiveType = (status == .changed)
            ? (featureChangeTypes[feature.id] ?? feature.type)
            : feature.type

        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .center, spacing: 10) {
                FeatureIcon.image(for: effectiveType)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 24, height: 24)
                featureTitleLine(
                    name: feature.displayName,
                    position: feature.position
                )
                .layoutPriority(1)
                Spacer(minLength: 4)
                Picker("", selection: Binding(
                    get: { featureStatuses[feature.id] ?? .unverified },
                    set: { featureStatuses[feature.id] = $0 }
                )) {
                    ForEach(FeatureStatus.allCases) { s in
                        Text(s.displayName).tag(s)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
                .fixedSize()
            }

            // Description sits on its own line, flush-left with the icon.
            if let description = feature.description {
                Text(description)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
            }

            if status == .changed {
                let removedBinding = Binding<Bool>(
                    get: { featureRemoved[feature.id] ?? false },
                    set: { featureRemoved[feature.id] = $0 }
                )
                let isRemoved = removedBinding.wrappedValue

                if let options {
                    Picker("New Status", selection: Binding(
                        get: { featureChangeTypes[feature.id] ?? feature.type },
                        set: { featureChangeTypes[feature.id] = $0 }
                    )) {
                        ForEach(options) { opt in
                            Text(opt.displayName).tag(opt.rawValue)
                        }
                    }
                    .pickerStyle(.segmented)
                    .disabled(isRemoved)
                    .opacity(isRemoved ? 0.5 : 1)
                }

                commentEditor(for: feature.id)
                    .disabled(isRemoved)
                    .opacity(isRemoved ? 0.5 : 1)

                Toggle("No longer present", isOn: removedBinding)
                    .font(.footnote)
                    .padding(.top, 8)
            }
        }
    }

    // MARK: Photos

    @ViewBuilder
    private var photosSection: some View {
        groupedSection("Photos", footer: {
            if photos.contains(where: { $0.uploadError != nil }) {
                Text("Fix the failed uploads or remove them before submitting.")
                    .foregroundStyle(.red)
            } else if photos.count >= Composer.maxPhotos {
                Text("Maximum of \(Composer.maxPhotos) photos per bulletin.")
            } else if !photos.isEmpty {
                let hasInvalid = photos.contains { !Composer.captionRange.contains($0.caption.count) }
                Text("At least \(Composer.captionRange.lowerBound) characters. \(Composer.captionRange.upperBound) max.")
                    .foregroundStyle(hasInvalid ? Color.red : Color.secondary)
            } else {
                EmptyView()
            }
        }) {
            PhotosPicker(
                selection: $pickerItems,
                maxSelectionCount: Composer.maxPhotos - photos.count,
                matching: .images,
                photoLibrary: .shared()
            ) {
                Label("Add photos", systemImage: "photo.on.rectangle.angled")
            }
            .onChange(of: pickerItems) { items in
                Task { await ingest(pickerItems: items) }
            }
            .disabled(photos.count >= Composer.maxPhotos)

            ForEach($photos) { photo in
                photoRow(photo: photo)
            }
        }
    }

    private func photoRow(photo: Binding<DraftPhotoState>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Group {
                if let img = photo.wrappedValue.previewImage {
                    img
                        .resizable()
                        .scaledToFit()
                } else {
                    Rectangle().fill(Color(uiColor: .systemGray5))
                        .frame(height: 120)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: 240)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay {
                if photo.wrappedValue.isUploading {
                    ZStack {
                        Color.black.opacity(0.35)
                        ProgressView().tint(.white)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                }
            }
            .overlay(alignment: .topTrailing) {
                Button {
                    remove(photo.wrappedValue)
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title3)
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, .black.opacity(0.55))
                }
                .buttonStyle(.plain)
                .padding(6)
            }

            if let error = photo.wrappedValue.uploadError {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .lineLimit(2)
            } else if photo.wrappedValue.reference != nil {
                Label("Uploaded", systemImage: "checkmark.circle.fill")
                    .font(.footnote)
                    .foregroundStyle(.green)
            } else if photo.wrappedValue.isUploading {
                Label("Uploading…", systemImage: "arrow.up.circle")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                // No reference, no error, no live upload — offline
                // pick that'll be sent by the submission queue.
                Label("Will upload when online", systemImage: "arrow.up.circle.badge.clock")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }

            captionEditor(photo: photo)
        }
    }

    /// Full-width caption field under each photo row. Uses TextField with
    /// axis:.vertical for native placeholder behavior (TextEditor has no
    /// built-in prompt), styled with the same rounded fill the feature-
    /// change comment editor uses so the composer's inline text inputs
    /// share a visual language.
    @ViewBuilder
    private func captionEditor(photo: Binding<DraftPhotoState>) -> some View {
        let count = photo.wrappedValue.caption.count
        let valid = Composer.captionRange.contains(count)
        let cappedBinding = Binding<String>(
            get: { photo.wrappedValue.caption },
            set: { photo.wrappedValue.caption = String($0.prefix(Composer.captionRange.upperBound)) }
        )
        VStack(alignment: .trailing, spacing: 4) {
            TextField("Caption", text: cappedBinding, axis: .vertical)
                .lineLimit(2...4)
                .font(.footnote)
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(uiColor: .secondarySystemFill), in: RoundedRectangle(cornerRadius: 10))
            Text("\(count) / \(Composer.captionRange.upperBound)")
                .font(.caption2)
                .foregroundStyle(valid ? Color.secondary : Color.red)
        }
    }

    /// Allowed caption length. Mirrors the server's validation so we don't
    /// submit something it'll reject.
    // captionRange lives on `Composer` below — see comment above.

    @MainActor
    private func ingest(pickerItems items: [PhotosPickerItem]) async {
        for item in items {
            guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
            let photoId = UUID()
            // Persist bytes immediately so draft-save / offline Submit can
            // find them even if the app is killed mid-compose.
            draftStore.writePhoto(id: photoId, data: data)
            var draft = DraftPhotoState(id: photoId, data: data, caption: "")
            // Only attempt an eager upload when we actually have network —
            // otherwise skip and let the submission queue carry it up when
            // the user hits Submit.
            if submissionQueue.isNetworkAvailable {
                draft.isUploading = true
            }
            photos.append(draft)
            if submissionQueue.isNetworkAvailable {
                Task { await upload(draftId: photoId, data: data) }
            }
        }
        pickerItems = []
    }

    @MainActor
    private func upload(draftId: UUID, data: Data) async {
        do {
            let photo = try await BackroadStatus.shared.uploadDraftPhoto(data)
            if let idx = photos.firstIndex(where: { $0.id == draftId }) {
                photos[idx].reference = photo.reference
                photos[idx].uploadError = nil
                photos[idx].isUploading = false
            }
        } catch {
            print("[Composer] Eager upload for photo \(draftId) failed: \(error.localizedDescription)")
            if let idx = photos.firstIndex(where: { $0.id == draftId }) {
                photos[idx].uploadError = error.localizedDescription
                photos[idx].isUploading = false
            }
        }
    }

    private func remove(_ photo: DraftPhotoState) {
        photos.removeAll { $0.id == photo.id }
        draftStore.removePhoto(id: photo.id)
    }

    /// Kicks off eager uploads for any photo that's sitting in
    /// `isPendingUpload` (picked offline, or a previous eager upload
    /// failed and we're trying again). Called on resume and whenever
    /// `isNetworkAvailable` flips true.
    @MainActor
    private func reuploadPendingPhotosIfOnline() {
        guard submissionQueue.isNetworkAvailable else { return }
        for idx in photos.indices where photos[idx].isPendingUpload || photos[idx].uploadError != nil {
            photos[idx].isUploading = true
            photos[idx].uploadError = nil
            let id = photos[idx].id
            let data = photos[idx].data
            Task { await upload(draftId: id, data: data) }
        }
    }

    @ViewBuilder
    private func roadLoadErrorCard(_ message: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "wifi.exclamationmark")
                .foregroundStyle(.red)
                .font(.title3)
            VStack(alignment: .leading, spacing: 6) {
                Text("Couldn't load road")
                    .font(.subheadline.weight(.semibold))
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Button {
                    roadLoadError = nil
                    Task { await loadFullRoad() }
                } label: {
                    Label("Try again", systemImage: "arrow.clockwise")
                        .font(.footnote.weight(.medium))
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .padding(.top, 2)
            }
            Spacer(minLength: 0)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            Color.red.opacity(0.1),
            in: RoundedRectangle(cornerRadius: 10)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(Color.red.opacity(0.25), lineWidth: 1)
        )
    }

    @ViewBuilder
    private func submitErrorCard(_ message: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.red)
                .font(.title3)
            VStack(alignment: .leading, spacing: 4) {
                Text("Couldn't submit report")
                    .font(.subheadline.weight(.semibold))
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            Color.red.opacity(0.1),
            in: RoundedRectangle(cornerRadius: 10)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(Color.red.opacity(0.25), lineWidth: 1)
        )
    }

    // MARK: Draft footer

    /// Footer section that surfaces when the composer is actively holding a
    /// draft for this road. The different-road case is now handled by the
    /// Resume / Discard alert on open, so we only need a simple discard
    /// here for the user to blow their own work away mid-composition.
    @ViewBuilder
    private var draftFooter: some View {
        if draftActive, draftStore.currentDraft?.roadId == roadId {
            groupedSection(footer: {
                Text("Your changes are saved automatically. Save and continue later to come back to this report, or discard it to throw it away.")
            }) {
                Button {
                    saveDraftAndDismiss()
                } label: {
                    Label("Save and continue later", systemImage: "square.and.arrow.down")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                Divider()
                Button(role: .destructive) {
                    discardDraft()
                    dismiss()
                } label: {
                    Label("Discard draft", systemImage: "trash")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    private func discardDraft() {
        pendingDraftSave?.cancel()
        pendingDraftSave = nil
        draftStore.discard()
    }

    /// Flushes any pending debounced save synchronously so the latest
    /// keystroke isn't lost, then dismisses. Tied to both the explicit
    /// "Save for later" button in the footer and the matching Cancel
    /// confirmation-dialog action.
    @MainActor
    private func saveDraftAndDismiss() {
        pendingDraftSave?.cancel()
        pendingDraftSave = nil
        if draftActive {
            draftStore.save(buildDraftSnapshot())
        }
        dismiss()
    }

    /// Cancel-button behaviour.
    ///
    /// Edit-of-pending-submission flow (`onRevert` non-nil): we treat
    /// Cancel as "put the queue entry back exactly as it was, discard
    /// any in-session edits, and dismiss." No dialog — the user chose to
    /// edit and chose to cancel; prompting would be noise. Only Submit
    /// actually updates the pending bulletin.
    ///
    /// Fresh-create flow: if there's in-progress content, prompt so the
    /// Save-for-later / Discard choice is visible. Otherwise just dismiss.
    @MainActor
    private func handleCancelTap() {
        if let onRevert {
            onRevert()
            discardDraft()
            dismiss()
            return
        }
        if hasInProgressWork {
            showCancelDialog = true
        } else {
            dismiss()
        }
    }

    private var hasInProgressWork: Bool {
        guard draftActive else { return false }
        if draftStore.currentDraft?.roadId == roadId { return true }
        return !description.isEmpty
            || !conditions.isEmpty
            || !createdFeatures.isEmpty
            || !photos.isEmpty
            || !vehicles.isEmpty
            || verifyMode
            || !featureStatuses.values.allSatisfy({ $0 == .unverified })
    }

    private var offlineBanner: some View {
        HStack(spacing: 8) {
            Image(systemName: "wifi.exclamationmark")
            Text("Offline — your work is saved locally.")
                .font(.footnote)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .foregroundStyle(.white)
        .background(Color.orange)
    }

    @ViewBuilder
    private var conditionsSection: some View {
        if !hasGeometry {
            if let error = roadLoadError {
                roadLoadErrorCard(error)
            } else {
                groupedSection {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("Loading road…")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        } else {
            groupedSection("Road Conditions (Optional)", headerAccessory: {
                Button {
                    showConditionsInfo = true
                } label: {
                    Image(systemName: "info.circle")
                        .font(.footnote)
                }
                .accessibilityLabel("About road condition ratings")
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
            }, footer: {
                if verifyMode {
                    Text(roadConditions.isEmpty
                        ? "Current conditions aren't loaded — turn this off and pick conditions manually."
                        : "Drag the handles to mark where you drove. Current conditions will be applied to that range.")
                } else if !conditions.isEmpty {
                    Text("Drag handles where conditions changed.")
                } else {
                    EmptyView()
                }
            }) {
                // Dropdown is the primary call-to-action in the default flow.
                // Hidden once the user is in verify mode — the two paths are
                // mutually exclusive, and the toggle below is their way back.
                if !verifyMode {
                    conditionDropdown
                }

                // Only shown in the default empty state. Once the user has
                // picked a condition the dropdown's role is self-evident, and
                // in verify mode the toggle's label + footer explain the mode.
                if conditions.isEmpty && !verifyMode {
                    Text("Select multiple road conditions from the dropdown and drag condition handles to where conditions changed.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                // The toggle is only meaningful before the user has started
                // marking individual condition changes — the two paths are
                // mutually exclusive. Once any condition is added, hide the
                // toggle; it reappears if the user clears them back out.
                if conditions.isEmpty {
                    Toggle("Conditions unchanged", isOn: $verifyMode)
                        .disabled(roadConditions.isEmpty)
                }

                if verifyMode {
                    verifySliderBody
                } else if !conditions.isEmpty {
                    conditionsEditor
                }
            }
        }
    }

    @ViewBuilder
    private var conditionDropdown: some View {
        Menu {
            ForEach(pickableConditions) { c in
                Button(c.displayName) { addCondition(c) }
            }
        } label: {
            HStack {
                Text(canAddMore ? "Add condition" : "Can't add more")
                Spacer()
                Image(systemName: "chevron.up.chevron.down")
            }
            .foregroundStyle(canAddMore ? .primary : .secondary)
        }
        .disabled(!canAddMore)
    }

    @ViewBuilder
    private var conditionsEditor: some View {
        SegmentedSliderView(
            values: $sliderValues,
            range: 0...maxStepIndex,
            segmentColors: conditions.map(\.condition.uiColor),
            snap: Composer.snapStep,
            minimumGap: Composer.marginSteps,
            labels: sliderValues.map { formatStepDistance(Int($0)) }
        )
        .onChange(of: sliderValues) { new in
            syncFromSlider(new)
        }

        chipsRow
    }

    @ViewBuilder
    private var verifySliderBody: some View {
        SegmentedSliderView(
            values: $verifyValues,
            range: 0...maxStepIndex,
            segmentColors: [UIColor.systemBlue],
            snap: Composer.snapStep,
            minimumGap: Composer.marginSteps,
            labels: verifyValues.map { formatStepDistance(Int($0)) }
        )
    }

    private var chipsRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Array(conditions.enumerated()), id: \.element.id) { index, entry in
                    conditionChip(entry: entry, index: index)
                }
            }
            .padding(.vertical, 4)
        }
    }

    // MARK: Vehicles

    private var pickableVehicles: [VehicleCategory] {
        VehicleCategory.userPickable.filter { !vehicles.contains($0) }
    }

    private var canAddVehicle: Bool {
        vehicles.count < Composer.maxVehicles && !pickableVehicles.isEmpty
    }

    @ViewBuilder
    private var vehiclesSection: some View {
        groupedSection("Modes of Travel", footer: {
            Text(vehicles.isEmpty
                 ? "Optional. Pick up to \(Composer.maxVehicles) modes you used on this road."
                 : "Up to \(Composer.maxVehicles). Tap × to remove.")
        }) {
            Menu {
                ForEach(pickableVehicles) { v in
                    Button(v.displayName) { addVehicle(v) }
                }
            } label: {
                HStack {
                    Text(canAddVehicle ? "Add mode" : "Can't add more")
                    Spacer()
                    Image(systemName: "chevron.up.chevron.down")
                }
                .foregroundStyle(canAddVehicle ? .primary : .secondary)
            }
            .disabled(!canAddVehicle)

            if !vehicles.isEmpty {
                vehicleChipsRow
            }
        }
    }

    private var vehicleChipsRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(vehicles) { v in
                    vehicleChip(v)
                }
            }
            .padding(.vertical, 4)
        }
    }

    private func vehicleChip(_ v: VehicleCategory) -> some View {
        HStack(spacing: 6) {
            Text(v.displayName)
                .font(.footnote)
                .fontWeight(.medium)
            Button {
                vehicles.removeAll { $0 == v }
            } label: {
                Image(systemName: "xmark")
                    .font(.caption2)
                    .fontWeight(.bold)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .foregroundStyle(.primary)
        .background(Color(uiColor: .secondarySystemFill))
        .clipShape(Capsule())
    }

    private func addVehicle(_ v: VehicleCategory) {
        guard canAddVehicle, !vehicles.contains(v) else { return }
        vehicles.append(v)
    }

    private func conditionChip(entry: ConditionEntry, index: Int) -> some View {
        HStack(spacing: 6) {
            Text(entry.condition.displayName)
                .font(.footnote)
                .fontWeight(.medium)
            Button {
                deleteCondition(at: index)
            } label: {
                Image(systemName: "xmark")
                    .font(.caption2)
                    .fontWeight(.bold)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .foregroundStyle(entry.condition.chipForeground)
        .background(entry.condition.color)
        .clipShape(Capsule())
    }

    // MARK: State mutations

    private func addCondition(_ condition: Condition) {
        guard canAddMore else { return }
        // Picking a condition commits the user to the "changed" flow.
        // Leaves verifyMode cleanly so the verify slider doesn't linger if
        // they switched modes mid-flight via the dropdown.
        verifyMode = false
        let lastStep = conditions.last?.step ?? startStep
        let jump = max(Composer.marginSteps, (maxStepIndex * Composer.jumpFraction).rounded())
        let step = min(lastStep + jump, maxStepIndex)
        let entry = ConditionEntry(condition: condition, step: step)
        conditions.append(entry)
        rebuildSliderValues()
    }

    private func deleteCondition(at index: Int) {
        guard index >= 0 && index < conditions.count else { return }
        // Mirror the JS: if the deleted entry sits between two identical conditions,
        // merge them by removing both the prior and the deleted entry — but with the
        // no-duplicates rule we're enforcing, that merge case shouldn't fire. Keep
        // the branch anyway in case the server ever sends such data.
        if index > 0, index < conditions.count - 1,
           conditions[index - 1].condition == conditions[index + 1].condition {
            conditions.removeSubrange((index - 1)...index)
        } else {
            conditions.remove(at: index)
        }
        if conditions.isEmpty {
            startStep = 0
        }
        rebuildSliderValues()
    }

    private func rebuildSliderValues() {
        sliderValues = [startStep] + conditions.map(\.step)
    }

    private func syncFromSlider(_ newValues: [CGFloat]) {
        guard newValues.count == conditions.count + 1 else { return }
        startStep = newValues[0]
        var sorted = conditions
        for (i, v) in newValues.dropFirst().enumerated() where i < sorted.count {
            sorted[i].step = v
        }
        if sorted != conditions {
            conditions = sorted
        }
    }

    // MARK: Draft persistence

    /// Fingerprint used as the `.onChange` key — every value here is part of
    /// the draft, so when any of them differ we schedule a save. Keeping the
    /// whole thing Equatable in one place is cheaper than wiring a dozen
    /// separate `.onChange` modifiers.
    private struct DraftFingerprint: Equatable {
        let description: String
        let reportDate: Date
        let startStep: CGFloat
        let conditions: [ConditionEntry]
        let verifyMode: Bool
        let verifyValues: [CGFloat]
        let featureStatuses: [String: FeatureStatus]
        let featureChangeTypes: [String: String]
        let featureChangeComments: [String: String]
        let featureRemoved: [String: Bool]
        let createdFeatures: [CreatedFeature]
        let vehicles: [VehicleCategory]
        let photos: [DraftPhotoMeta]
        let roadName: String
    }

    private var draftFingerprint: DraftFingerprint {
        DraftFingerprint(
            description: description,
            reportDate: reportDate,
            startStep: startStep,
            conditions: conditions,
            verifyMode: verifyMode,
            verifyValues: verifyValues,
            featureStatuses: featureStatuses,
            featureChangeTypes: featureChangeTypes,
            featureChangeComments: featureChangeComments,
            featureRemoved: featureRemoved,
            createdFeatures: createdFeatures,
            vehicles: vehicles,
            photos: photoMetas,
            roadName: roadName
        )
    }

    /// Current photos in the shape persisted to disk. Bytes aren't part of
    /// the fingerprint — writing them is a separate write that happens
    /// at pick time, not on every keystroke.
    private var photoMetas: [DraftPhotoMeta] {
        photos.map { DraftPhotoMeta(id: $0.id, caption: $0.caption, reference: $0.reference) }
    }

    private func buildDraftSnapshot() -> Draft {
        Draft(
            id: draftId,
            clientSubmissionId: clientSubmissionId,
            roadId: roadId,
            roadName: roadName.isEmpty ? nil : roadName,
            createdAt: draftCreatedAt,
            updatedAt: Date(),
            reportDate: reportDate,
            description: description,
            startStep: startStep,
            conditions: conditions,
            verifyMode: verifyMode,
            verifyValues: verifyValues,
            featureStatuses: featureStatuses,
            featureChangeTypes: featureChangeTypes,
            featureChangeComments: featureChangeComments,
            featureRemoved: featureRemoved,
            createdFeatures: createdFeatures,
            vehicles: vehicles,
            photos: photoMetas,
            cachedRoad: cachedRoadDetail
        )
    }

    /// Called once on `.task`. If a stored draft matches this road, restore
    /// everything; if it's for another road, leave it alone (don't save on
    /// top of the other road's work). If no draft exists, we're free to
    /// save on first change.
    @MainActor
    private func loadDraftIfAny() {
        guard let draft = draftStore.currentDraft else {
            // Still prune — a previous app-kill between pick and save can
            // leave photo bytes on disk that no current draft references.
            draftStore.pruneOrphanPhotos(keeping: [])
            draftActive = true
            return
        }
        guard draft.roadId == roadId else {
            // Different-road draft exists. Pause draft-saving for this slot
            // and surface the Resume / Discard / Cancel alert; the user
            // picks what happens next.
            draftActive = false
            pendingResumePrompt = draft
            return
        }
        draftStore.pruneOrphanPhotos(keeping: Set(draft.photos.map(\.id)))
        applyDraft(draft)
    }

    /// Applies a draft that matches the current `roadId` into composer state.
    @MainActor
    private func applyDraft(_ draft: Draft) {
        draftId = draft.id
        draftCreatedAt = draft.createdAt
        clientSubmissionId = draft.clientSubmissionId
        if let name = draft.roadName { roadName = name }
        reportDate = draft.reportDate
        description = draft.description
        startStep = draft.startStep
        conditions = draft.conditions
        verifyMode = draft.verifyMode
        verifyValues = draft.verifyValues
        featureStatuses = draft.featureStatuses
        featureChangeTypes = draft.featureChangeTypes
        featureChangeComments = draft.featureChangeComments
        featureRemoved = draft.featureRemoved
        createdFeatures = draft.createdFeatures
        vehicles = draft.vehicles
        sliderValues = [startStep] + conditions.map(\.step)
        photos = draft.photos.compactMap { meta -> DraftPhotoState? in
            // Bytes gone from disk (user cleared app storage, etc.) → drop
            // the row rather than render a broken thumbnail.
            guard let data = draftStore.readPhoto(id: meta.id) else {
                print("[Composer] Draft photo \(meta.id) bytes missing on disk, dropping row")
                return nil
            }
            return DraftPhotoState(
                id: meta.id,
                data: data,
                caption: meta.caption,
                reference: meta.reference,
                uploadError: nil,
                isUploading: false
            )
        }
        draftActive = true
        // Replay the cached road detail so the map / slider / features list
        // work immediately, before the live API fetch (which may fail offline).
        if let cached = draft.cachedRoad {
            applyRoadDetail(cached)
        }
        // Kick off eager uploads for any photos we skipped while offline —
        // the user may be online now (either immediately or shortly), and
        // getting these uploaded during composition means Submit lands
        // synchronously instead of being queued.
        reuploadPendingPhotosIfOnline()
    }

    /// Switch the composer to the draft's road. The `.onChange(of: roadId)`
    /// observer wipes road-dependent state and re-fetches geometry; this
    /// method applies the draft body once the switch is queued.
    @MainActor
    private func resumeDraft(_ draft: Draft) {
        pendingResumePrompt = nil
        if draft.roadId != roadId {
            // Clear state that depends on the previous road so re-fetch
            // starts from a clean slate before applying the draft.
            geometry = RoadGeometry(coordinates: [], cumulativeDistances: [])
            roadConditions = []
            discoveredFeatures = []
            roadName = ""
            roadId = draft.roadId
        }
        applyDraft(draft)
    }

    /// Discard the alert-presented draft and claim the draft slot for the
    /// current road so new edits start saving immediately.
    @MainActor
    private func discardOtherDraftAndContinue() {
        pendingResumePrompt = nil
        draftStore.discard()
        draftActive = true
        // Nudge a save so the slot now reflects this road even before the
        // user makes an edit.
        scheduleDraftSave()
    }

    private func clearDraftAfterSubmit() {
        pendingDraftSave?.cancel()
        pendingDraftSave = nil
        DraftStore.shared.discard()
        draftActive = false
    }

    private func scheduleDraftSave() {
        guard draftActive else { return }
        // In edit-of-pending-submission mode, the composer's rule is
        // "only Submit updates the pending bulletin." Autosaving would
        // silently commit partial edits to DraftStore, so if the app were
        // killed mid-edit the user would resume with half-made changes
        // they never meant to persist. Keep the in-session draft purely
        // in memory until Submit (or Cancel = revert).
        guard onRevert == nil else { return }
        pendingDraftSave?.cancel()
        let snapshot = buildDraftSnapshot()
        let work = DispatchWorkItem {
            DraftStore.shared.save(snapshot)
        }
        pendingDraftSave = work
        // 500ms debounce — covers typing bursts without hammering the disk.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: work)
    }

    // MARK: Submit

    @MainActor
    private func submit() async {
        busy = true
        errorMessage = nil
        defer { busy = false }

        let ratings = verifyMode ? buildVerifiedConditionRatings() : buildConditionRatings()
        // Hand each photo to the queue with whatever we know about it:
        // the bytes are always present; a `reference` is set iff the eager
        // upload already succeeded. The queue uploads any remaining bytes
        // before calling createBulletin.
        let pendingPhotos: [PendingPhoto] = photos.compactMap { p in
            let caption = p.caption.trimmingCharacters(in: .whitespaces)
            guard !caption.isEmpty else { return nil }
            return PendingPhoto(id: p.id, data: p.data, caption: caption, reference: p.reference)
        }
        let input = CreateBulletinInput(
            pathId: roadId,
            reportDate: reportDate,
            description: description,
            // Photos get filled in by the queue once every pendingPhoto
            // has a reference; keep this empty to avoid two sources of truth.
            photos: [],
            conditionRatings: ratings,
            vehicles: vehicles.map { VehicleInput(category: $0.rawValue) },
            featureUpdates: buildFeatureUpdates(),
            clientSubmissionId: clientSubmissionId
        )

        // Snapshot the draft state into the queue entry so a failed /
        // queued submission can be edited from Pending Bulletins with
        // full composer fidelity (slider step indices, verifyMode, feature
        // statuses — things we can't recover from CreateBulletinInput alone).
        let snapshotData = try? JSONEncoder.iso8601.encode(buildDraftSnapshot())

        do {
            let outcome = try await SubmissionQueue.shared.submit(input, photos: pendingPhotos, roadName: roadName, draftSnapshot: snapshotData)
            switch outcome {
            case .completed(let bulletin):
                submittedId = bulletin.id
            case .queued(let pending):
                print("[Composer] Submission queued (\(pending.id); \(pending.pendingPhotoCount) photo(s) pending): \(pending.lastError ?? "unknown error")")
                queuedMessage = "You're offline. We'll finish submitting as soon as you're back online."
            }
            // Either way the user has "sent" the report. The submission
            // queue owns the data now and shares our clientSubmissionId so
            // a later retry is still deduped on the server. Keeping the
            // draft around after this point would just resurrect their
            // typed content if they reopened the composer.
            clearDraftAfterSubmit()
            try? await Task.sleep(nanoseconds: 1_400_000_000)
            dismiss()
        } catch {
            print("[Composer] Submit failed non-retryable: \(error.localizedDescription)")
            errorMessage = friendlySubmitError(error)
        }
    }

    /// Matches `getValues()` in condition-slider.js:
    ///   - If startStep > 0, prepend {point at startStep, condition: UNKNOWN}
    ///     (skipped if the user explicitly started with UNKNOWN — avoids
    ///     emitting two back-to-back UNKNOWN entries).
    ///   - Then append each condition entry in order.
    /// Handles are step indices into `geometry.coordinates`, so we look up
    /// the coord directly rather than translating meters.
    private func buildConditionRatings() -> [ConditionRatingInput] {
        guard hasGeometry, !conditions.isEmpty else { return [] }

        var ratings: [ConditionRatingInput] = []

        let firstIsUnknown = conditions.first?.condition == .unknown
        if startStep > 0, !firstIsUnknown, let startCoord = coord(atStep: Int(startStep)) {
            ratings.append(ConditionRatingInput(point: startCoord, condition: Condition.unknown.rawValue))
        }

        for entry in conditions {
            guard let coord = coord(atStep: Int(entry.step)) else { continue }
            ratings.append(ConditionRatingInput(point: coord, condition: entry.condition.rawValue))
        }

        return ratings
    }

    private func coord(atStep step: Int) -> [Double]? {
        guard step >= 0, step < geometry.coordinates.count else { return nil }
        return geometry.coordinates[step]
    }

    /// Verify mode: user has asserted "conditions on the map still apply to
    /// the stretch I drove." Produces the same conditionRatings the server
    /// would build in `PathBulletinService.createFromCurrentConditions`, but
    /// against the conditions we captured at API-fetch time. Doing this
    /// client-side means an offline submission later still reflects what the
    /// user saw, not whatever the server has moved on to.
    ///
    /// Algorithm — keep in sync with the Kotlin original:
    ///   1. If the user started past coord 0, prepend UNKNOWN at verifyFromPoint
    ///   2. Walk roadConditions in coord order. For each condition:
    ///      - Include it verbatim if its index is in [fromIdx, atIdx]
    ///      - Otherwise, if verifyAtPoint falls strictly between the last
    ///        included condition's index and this one, emit this condition's
    ///        value at verifyAtPoint (conditions apply backwards, so an
    ///        end-point between two change points inherits the later one)
    private func buildVerifiedConditionRatings() -> [ConditionRatingInput] {
        guard hasGeometry, verifyValues.count == 2, !roadConditions.isEmpty else { return [] }
        let fromIdx = Int(verifyValues[0])
        let atIdx = Int(verifyValues[1])
        guard fromIdx >= 0, atIdx >= fromIdx, atIdx < geometry.coordinates.count else { return [] }

        let verifyFromPoint = geometry.coordinates[fromIdx]
        let verifyAtPoint = geometry.coordinates[atIdx]

        var ratings: [ConditionRatingInput] = []

        if fromIdx > 0 {
            ratings.append(ConditionRatingInput(point: verifyFromPoint, condition: Condition.unknown.rawValue))
        }

        var lastConditionPointIndex = 0
        for cp in roadConditions {
            guard let cpIdx = geometry.coordinates.firstIndex(where: { $0 == cp.point }) else { continue }

            if cpIdx >= fromIdx, cpIdx <= atIdx {
                lastConditionPointIndex = cpIdx
                ratings.append(ConditionRatingInput(point: cp.point, condition: cp.condition))
            }

            if atIdx > lastConditionPointIndex, atIdx < cpIdx {
                ratings.append(ConditionRatingInput(point: verifyAtPoint, condition: cp.condition))
                lastConditionPointIndex = cpIdx
            }
        }

        // /bulletins' validator (`PathService.isValidConditionUpdate`) rejects
        // adjacent-equal conditions. The server-side verify path avoids this
        // by going straight to deduplicateConditionRatings — we don't have
        // that affordance here, so collapse any neighbors ourselves before
        // submit. Happens most often when the road has no real pathConditions
        // and we've emitted both a start-UNKNOWN and an end-UNKNOWN from the
        // synthetic trailing segment.
        return dedupeAdjacentRatings(ratings)
    }

    private func dedupeAdjacentRatings(_ ratings: [ConditionRatingInput]) -> [ConditionRatingInput] {
        var result: [ConditionRatingInput] = []
        for r in ratings where result.last?.condition != r.condition {
            result.append(r)
        }
        return result
    }

    /// Emits a FeatureUpdateInput for each feature the user explicitly acted on
    /// (verified or removed) plus every new feature placed via "Add Feature".
    /// Features left at .unverified are not touched — the server treats absent
    /// entries as "no comment."
    private func buildFeatureUpdates() -> [FeatureUpdateInput] {
        var updates: [FeatureUpdateInput] = discoveredFeatures.compactMap { feature in
            let status = featureStatuses[feature.id] ?? .unverified
            switch status {
            case .unverified:
                return nil
            case .verified:
                return FeatureUpdateInput(
                    featureId: feature.id,
                    finalType: feature.type,
                    removed: false,
                    created: false
                )
            case .changed:
                // If the type has no transition options (impasse, warning, …),
                // keep the original type and let the comment carry the update.
                let finalType = FeatureTransition.options(for: feature.type) != nil
                    ? (featureChangeTypes[feature.id] ?? feature.type)
                    : feature.type
                let trimmed = (featureChangeComments[feature.id] ?? "")
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                let removed = featureRemoved[feature.id] ?? false
                return FeatureUpdateInput(
                    featureId: feature.id,
                    finalType: finalType,
                    comment: trimmed.isEmpty ? nil : trimmed,
                    removed: removed,
                    created: false
                )
            }
        }
        updates.append(contentsOf: createdFeatures.map { feature in
            FeatureUpdateInput(
                featureId: nil,
                finalType: feature.type.rawValue,
                comment: feature.comment,
                removed: false,
                created: true,
                position: feature.position
            )
        })
        return updates
    }

    // MARK: Helpers

    private func formatDistance(_ meters: Double) -> String {
        if meters < 1000 {
            return "\(Int(meters.rounded())) m"
        }
        return String(format: "%.1f km", meters / 1000)
    }

    /// Formats a step index as distance-from-start using the cumulative
    /// haversine distances we precomputed on `geometry`.
    private func formatStepDistance(_ step: Int) -> String {
        guard step >= 0, step < geometry.cumulativeDistances.count else { return "0" }
        return formatDistance(geometry.cumulativeDistances[step])
    }

    /// Distance-from-start label for a feature's position. Matches the web
    /// composer's "@ 1.2 km" display format. Returns nil if geometry hasn't
    /// loaded yet or the feature's coord isn't in the path.
    private func featureDistanceLabel(position: [Double]) -> String? {
        guard position.count >= 2, hasGeometry else { return nil }
        guard let idx = geometry.coordinates.firstIndex(where: { $0 == position }),
              idx < geometry.cumulativeDistances.count else { return nil }
        let km = geometry.cumulativeDistances[idx] / 1000
        return String(format: "@ %.1f km", km)
    }

    /// Replicates the insetGrouped Form section visual in a plain VStack so
    /// the map above us can bleed to the screen edges while the rest of the
    /// composer keeps the familiar iOS grouped-form look. Uppercase header
    /// tab, rounded-rect body, optional small-type footer — same spacing
    /// Apple uses in Settings.
    @ViewBuilder
    private func groupedSection<Content: View, Footer: View, HeaderAccessory: View>(
        _ header: String? = nil,
        @ViewBuilder headerAccessory: () -> HeaderAccessory,
        @ViewBuilder footer: () -> Footer,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            if let header {
                HStack(spacing: 6) {
                    Text(header.uppercased())
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    headerAccessory()
                    Spacer(minLength: 0)
                }
                .padding(.leading, 16)
                .padding(.trailing, 16)
            }
            VStack(alignment: .leading, spacing: 12) {
                content()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(
                Color(uiColor: .secondarySystemGroupedBackground),
                in: RoundedRectangle(cornerRadius: 10)
            )
            footer()
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 16)
        }
    }

    @ViewBuilder
    private func groupedSection<Content: View, Footer: View>(
        _ header: String? = nil,
        @ViewBuilder footer: () -> Footer,
        @ViewBuilder content: () -> Content
    ) -> some View {
        groupedSection(header, headerAccessory: { EmptyView() }, footer: footer, content: content)
    }

    @ViewBuilder
    private func groupedSection<Content: View>(
        _ header: String? = nil,
        @ViewBuilder content: () -> Content
    ) -> some View {
        groupedSection(header, headerAccessory: { EmptyView() }, footer: { EmptyView() }, content: content)
    }

    /// Multi-line comment editor used by the "Changed" feature flow. Styled
    /// as a rounded fill (matches how comment fields look elsewhere in iOS
    /// Settings-style forms) with a lightweight placeholder overlay since
    /// TextEditor has no built-in prompt.
    @ViewBuilder
    private func commentEditor(for featureId: String) -> some View {
        let binding = Binding<String>(
            get: { featureChangeComments[featureId] ?? "" },
            set: { featureChangeComments[featureId] = String($0.prefix(200)) }
        )
        ZStack(alignment: .topLeading) {
            TextEditor(text: binding)
                .scrollContentBackground(.hidden)
                .padding(6)
                .frame(minHeight: 84) // ~3 lines of .footnote + padding
                .font(.footnote)
            if binding.wrappedValue.isEmpty {
                Text("Comment (optional)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 14)
                    .allowsHitTesting(false)
            }
        }
        .background(Color(uiColor: .secondarySystemFill), in: RoundedRectangle(cornerRadius: 10))
    }

    /// Shared title line for feature rows: bold name, optional "@ X.X km"
    /// distance tag, and an optional badge (e.g. "New" for created features).
    @ViewBuilder
    private func featureTitleLine(name: String, position: [Double], badge: String? = nil, badgeColor: Color = .accentColor) -> some View {
        HStack(spacing: 6) {
            Text(name)
                .font(.callout)
                .fontWeight(.semibold)
                .lineLimit(1)
                .minimumScaleFactor(0.85)
            if let distance = featureDistanceLabel(position: position) {
                Text(distance)
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .fixedSize()
            }
            if let badge {
                Text(badge)
                    .font(.caption2)
                    .fontWeight(.semibold)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(badgeColor.opacity(0.18), in: Capsule())
                    .foregroundStyle(badgeColor)
                    .fixedSize()
            }
            Spacer(minLength: 0)
        }
    }
}

struct ConditionEntry: Identifiable, Equatable, Codable {
    let id: UUID
    var condition: Condition
    /// Step index into `geometry.coordinates`. Matches `condition-slider.js`.
    var step: CGFloat

    init(id: UUID = UUID(), condition: Condition, step: CGFloat) {
        self.id = id
        self.condition = condition
        self.step = step
    }
}

struct DraftPhotoState: Identifiable {
    let id: UUID
    let data: Data
    var caption: String
    var reference: String?
    var uploadError: String?
    /// True while an upload Task is live. Explicit rather than derived
    /// from (reference == nil && uploadError == nil) because an
    /// offline-captured photo sits in that "neither succeeded nor failed"
    /// state without an upload actually being in flight — the submission
    /// queue will pick it up on Submit.
    var isUploading: Bool

    init(id: UUID = UUID(), data: Data, caption: String, reference: String? = nil, uploadError: String? = nil, isUploading: Bool = false) {
        self.id = id
        self.data = data
        self.caption = caption
        self.reference = reference
        self.uploadError = uploadError
        self.isUploading = isUploading
    }

    /// Photo has no server reference yet AND we aren't actively uploading
    /// — it's waiting for the submission queue (or a retry of a failed
    /// eager upload) to carry it the rest of the way.
    var isPendingUpload: Bool {
        reference == nil && uploadError == nil && !isUploading
    }

    /// Returns a decoded SwiftUI `Image` for preview; nil if the bytes can't
    /// be turned into a `UIImage`.
    var previewImage: Image? {
        guard let uiImage = UIImage(data: data) else { return nil }
        return Image(uiImage: uiImage)
    }
}

/// Non-generic companion holding the composer's tunable constants. Lives
/// outside `ReportComposerView` because Swift forbids static stored
/// properties on generic types and the composer took on a `<Map: View>`
/// parameter when we abstracted the map backend.
enum Composer {
    static let submitErrorAnchorId = "submit-error-card"
    static let maxConditions = 5
    static let snapStep: CGFloat = 1
    static let marginSteps: CGFloat = 2
    static let jumpFraction: CGFloat = 0.10
    static let maxPhotos = 8
    static let maxVehicles = 3
    static let captionRange = 8...200
}
