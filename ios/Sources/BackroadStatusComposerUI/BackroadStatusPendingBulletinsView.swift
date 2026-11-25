import SwiftUI
import BackroadStatusCore
import BackroadStatusMapKit

/// Drop-in list view that surfaces every bulletin on disk that hasn't
/// landed on the server yet — both `.queued` (retrying automatically) and
/// `.failed` (parked after a non-retryable error).
///
/// Tapping a row opens an action dialog: Edit prefills the composer from
/// the row's stored draft snapshot, Retry re-attempts the submission, and
/// Discard drops it. Edit is disabled for legacy entries that predate the
/// draft-snapshot change — those rows can still Retry or Discard.
///
/// The view takes the same `map:` builder as `BackroadStatusReportView` so
/// it can present the composer sheet for the Edit flow using the host's
/// map SDK of choice.
public struct BackroadStatusPendingBulletinsView<Map: View>: View {
    let mapStyleURL: URL
    let mapBuilder: (ComposerMapBinding) -> Map

    @ObservedObject private var queue = SubmissionQueue.shared
    @ObservedObject private var draftStore = DraftStore.shared

    @State private var actionSheetTarget: PendingSubmission?
    @State private var discardConfirmTarget: PendingSubmission?
    @State private var draftConflictTarget: PendingSubmission?
    @State private var missingSnapshotMessage: String?

    /// Non-nil presents the composer sheet. Set after a successful
    /// takeForEdit so the sheet opens to the restored draft's road.
    @State private var editingRoadId: EditingRoad?

    /// Held alongside `editingRoadId` while the composer sheet is open so
    /// the Revert button can hand it back to the queue for restoration.
    /// Cleared when the sheet dismisses (via `.onChange` below) so a
    /// subsequent submit doesn't accidentally reinsert.
    @State private var editingSubmission: SubmissionForEdit?

    public init(
        mapStyleURL: URL,
        @ViewBuilder map mapBuilder: @escaping (ComposerMapBinding) -> Map
    ) {
        self.mapStyleURL = mapStyleURL
        self.mapBuilder = mapBuilder
    }

    public var body: some View {
        content
            .navigationTitle("Pending bulletins")
            .navigationBarTitleDisplayMode(.inline)
            .confirmationDialog(
                "Pending bulletin",
                isPresented: Binding(
                    get: { actionSheetTarget != nil },
                    set: { if !$0 { actionSheetTarget = nil } }
                ),
                titleVisibility: .visible,
                presenting: actionSheetTarget
            ) { pending in
                Button("Edit") { handleEditTap(pending) }
                    .disabled(!pending.hasDraftSnapshot)
                if pending.state == .failed {
                    Button("Retry now") { retry(pending) }
                }
                Button("Discard", role: .destructive) { discardConfirmTarget = pending }
                Button("Cancel", role: .cancel) {}
            } message: { pending in
                Text(pending.roadName ?? "Unnamed road")
            }
            .confirmationDialog(
                "Discard this bulletin?",
                isPresented: Binding(
                    get: { discardConfirmTarget != nil },
                    set: { if !$0 { discardConfirmTarget = nil } }
                ),
                titleVisibility: .visible,
                presenting: discardConfirmTarget
            ) { pending in
                Button("Discard", role: .destructive) { discard(pending) }
                Button("Cancel", role: .cancel) {}
            } message: { _ in
                Text("Your draft and any attached photos will be deleted.")
            }
            .confirmationDialog(
                "Replace current draft?",
                isPresented: Binding(
                    get: { draftConflictTarget != nil },
                    set: { if !$0 { draftConflictTarget = nil } }
                ),
                titleVisibility: .visible,
                presenting: draftConflictTarget
            ) { pending in
                Button("Replace draft", role: .destructive) { performEdit(pending) }
                Button("Cancel", role: .cancel) {}
            } message: { _ in
                Text("You have an unsaved draft \(draftConflictMessageTail). Editing this bulletin will discard it.")
            }
            .alert(
                "Can't edit this entry",
                isPresented: Binding(
                    get: { missingSnapshotMessage != nil },
                    set: { if !$0 { missingSnapshotMessage = nil } }
                ),
                presenting: missingSnapshotMessage
            ) { _ in
                Button("OK", role: .cancel) {}
            } message: { message in
                Text(message)
            }
            .sheet(item: $editingRoadId) { target in
                BackroadStatusReportView(
                    roadId: target.roadId,
                    mapStyleURL: mapStyleURL,
                    roadName: target.roadName,
                    onRevert: revertCurrentEdit,
                    map: mapBuilder
                )
            }
            .onChange(of: editingRoadId) { newValue in
                // Sheet dismissed — drop the held restoration payload.
                // If the user tapped Revert, revertCurrentEdit already ran
                // before dismiss; after that we don't want this lingering
                // payload to be used again.
                if newValue == nil { editingSubmission = nil }
            }
    }

    private func revertCurrentEdit() {
        guard let submission = editingSubmission else { return }
        queue.restore(submission, photosFrom: DraftStore.photosDirectory())
        editingSubmission = nil
    }

    @ViewBuilder
    private var content: some View {
        if queue.pending.isEmpty {
            emptyState
        } else {
            List {
                ForEach(queue.pending) { pending in
                    row(for: pending)
                        .contentShape(Rectangle())
                        .onTapGesture { actionSheetTarget = pending }
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) {
                                discardConfirmTarget = pending
                            } label: {
                                Label("Discard", systemImage: "trash")
                            }
                        }
                }
            }
            .listStyle(.insetGrouped)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "tray")
                .font(.system(size: 42))
                .foregroundStyle(.secondary)
            Text("Nothing pending")
                .font(.headline)
            Text("Submissions waiting to send, or that failed to send, will show up here.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(uiColor: .systemGroupedBackground))
    }

    @ViewBuilder
    private func row(for pending: PendingSubmission) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(pending.roadName ?? "Unnamed road")
                .font(.headline)
                .lineLimit(1)
            Text("Saved \(PendingBulletinsFormatters.relative.localizedString(for: pending.createdAt, relativeTo: Date()))")
                .font(.caption)
                .foregroundStyle(.secondary)
            statusChip(for: pending)
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func statusChip(for pending: PendingSubmission) -> some View {
        switch pending.state {
        case .queued:
            Label(queuedLabel(pending), systemImage: "arrow.up.circle.badge.clock")
                .font(.footnote)
                .foregroundStyle(.orange)
        case .failed:
            Label(failedLabel(pending), systemImage: "exclamationmark.triangle.fill")
                .font(.footnote)
                .foregroundStyle(.red)
                .lineLimit(3)
        }
    }

    private func queuedLabel(_ pending: PendingSubmission) -> String {
        if pending.pendingPhotoCount > 0 {
            return "Will submit when online (\(pending.pendingPhotoCount) photo\(pending.pendingPhotoCount == 1 ? "" : "s") pending)"
        }
        return "Will submit when online"
    }

    private func failedLabel(_ pending: PendingSubmission) -> String {
        if let err = pending.lastError, !err.isEmpty {
            return friendlyLastError(err)
        }
        return "Submission failed."
    }

    // MARK: Actions

    private func handleEditTap(_ pending: PendingSubmission) {
        guard pending.hasDraftSnapshot else {
            missingSnapshotMessage = "This submission was saved before Edit was available. Retry it as-is or discard and start a new report."
            return
        }
        // Any existing draft would be replaced on takeForEdit's save — warn
        // before clobbering it, including same-road drafts, since the user
        // could be mid-authoring a fresh report for the same road.
        if draftStore.currentDraft != nil {
            draftConflictTarget = pending
            return
        }
        performEdit(pending)
    }

    private func performEdit(_ pending: PendingSubmission) {
        draftConflictTarget = nil
        guard let payload = queue.takeForEdit(
            id: pending.id,
            migratingPhotosTo: DraftStore.photosDirectory()
        ) else {
            missingSnapshotMessage = "This submission could no longer be found."
            return
        }
        guard let blob = payload.draftSnapshot,
              let draft = try? JSONDecoder.iso8601.decode(Draft.self, from: blob) else {
            // Draft couldn't be reconstructed — put the queue entry back
            // using the restoration payload so the user doesn't lose the
            // pending bulletin just because we can't open it for edit.
            queue.restore(payload, photosFrom: DraftStore.photosDirectory())
            missingSnapshotMessage = "This submission's draft couldn't be restored."
            return
        }
        draftStore.save(draft)
        editingSubmission = payload
        editingRoadId = EditingRoad(roadId: draft.roadId, roadName: draft.roadName)
    }

    private func retry(_ pending: PendingSubmission) {
        Task { _ = try? await queue.retry(id: pending.id) }
    }

    private func discard(_ pending: PendingSubmission) {
        queue.cancel(id: pending.id)
    }

    // MARK: Helpers

    private var draftConflictMessageTail: String {
        if let name = draftStore.currentDraft?.roadName, !name.isEmpty {
            return "for \(name)"
        }
        return "on another road"
    }

}

/// Static state parked on a non-generic companion because Swift forbids
/// `static let` on generic types — same pattern as `Composer` next to
/// `ReportComposerView`.
private enum PendingBulletinsFormatters {
    static let relative: RelativeDateTimeFormatter = {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .abbreviated
        return f
    }()
}

/// Sheet-presentation payload. We don't reuse `PendingSubmission` because by
/// the time the sheet opens, the entry has already been popped off the
/// queue — this struct carries just the road identity the composer needs.
private struct EditingRoad: Identifiable, Equatable {
    let roadId: String
    let roadName: String?
    var id: String { roadId }
}
