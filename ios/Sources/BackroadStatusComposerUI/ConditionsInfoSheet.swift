import SwiftUI
import BackroadStatusCore

/// Explains each user-pickable road condition rating. Mirrors
/// `fragments/conditions.html :: condition-popup` on the web.
struct ConditionsInfoSheet: View {

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(Condition.userPickable) { condition in
                        VStack(spacing: 8) {
                            badge(for: condition)
                            Text(condition.description)
                                .font(.footnote)
                                .foregroundStyle(.primary)
                                .multilineTextAlignment(.center)
                                .frame(maxWidth: .infinity)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                    }
                } footer: {
                    Text("Note that gates, avalanches and snow lines should not qualify a road as impassable.")
                        .font(.footnote)
                        .italic()
                        .foregroundStyle(.secondary)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Road Condition Ratings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func badge(for condition: Condition) -> some View {
        Text(condition.displayName)
            .font(.caption.weight(.semibold))
            .foregroundStyle(condition.chipForeground)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(condition.color, in: Capsule())
            .fixedSize()
    }
}
