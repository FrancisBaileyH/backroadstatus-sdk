package com.backroadstatus.sdk.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.backroadstatus.sdk.core.PendingSubmission
import com.backroadstatus.sdk.core.SubmissionQueue
import com.backroadstatus.sdk.core.SubmissionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Drop-in list that surfaces every bulletin on disk that hasn't landed on
 * the server yet — both queued (retrying automatically) and failed
 * (parked after a non-retryable error).
 *
 * Tapping a row opens an action dialog: Edit prefills the composer from
 * the row's stored draft snapshot, Retry re-attempts the submission, and
 * Discard drops it. Edit is disabled for legacy entries that predate the
 * draft-snapshot change.
 *
 * Takes the same `mapBuilder` as [BackroadStatusReportScreen] so it can
 * present the composer sheet for the Edit flow using the host's map SDK.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackroadStatusPendingBulletinsScreen(
    mapStyleUrl: String,
    onDismiss: () -> Unit,
    mapBuilder: @Composable (ComposerMapBinding) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val queueState = remember { mutableStateOf<SubmissionQueue?>(null) }
    val draftStoreState = remember { mutableStateOf<DraftStore?>(null) }
    LaunchedEffect(Unit) {
        queueState.value = SubmissionQueue.shared(context)
        draftStoreState.value = DraftStore.shared(context)
    }
    val queue = queueState.value
    val draftStore = draftStoreState.value
    val pending by (queue?.pending ?: MutableStateFlow(emptyList())).collectAsState()

    var actionSheetTarget by remember { mutableStateOf<PendingSubmission?>(null) }
    var discardConfirmTarget by remember { mutableStateOf<PendingSubmission?>(null) }
    var draftConflictTarget by remember { mutableStateOf<PendingSubmission?>(null) }
    var missingSnapshotMessage by remember { mutableStateOf<String?>(null) }
    var editingDraft by remember { mutableStateOf<Pair<String, String?>?>(null) } // roadId → roadName

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pending bulletins") },
                navigationIcon = { TextButton(onClick = onDismiss) { Text("Close") } },
            )
        },
    ) { padding ->
        if (pending.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Inbox, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Nothing pending", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Submissions waiting to send, or that failed to send, will show up here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(pending, key = { it.id }) { item ->
                    PendingRow(
                        item = item,
                        onTap = { actionSheetTarget = item },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // Action dialog.
    actionSheetTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionSheetTarget = null },
            title = { Text(target.roadName ?: "Unnamed road") },
            text = { Text(if (target.state == SubmissionState.failed) "Submission failed." else "Will submit when online.") },
            confirmButton = {
                Column {
                    TextButton(
                        enabled = target.hasDraftSnapshot,
                        onClick = {
                            actionSheetTarget = null
                            // Any existing draft would be replaced on takeForEdit's save — warn first.
                            if (draftStore?.currentDraft != null) draftConflictTarget = target
                            else performEdit(context, scope, target, queue, draftStore) { roadId, roadName ->
                                editingDraft = roadId to roadName
                            } ?: Unit.let { missingSnapshotMessage = "This submission could no longer be found." }
                        },
                    ) { Text("Edit") }
                    if (target.state == SubmissionState.failed) {
                        TextButton(onClick = {
                            actionSheetTarget = null
                            scope.launch { runCatching { queue?.retry(target.id) } }
                        }) { Text("Retry now") }
                    }
                    TextButton(onClick = { actionSheetTarget = null; discardConfirmTarget = target }) {
                        Text("Discard", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { actionSheetTarget = null }) { Text("Cancel") }
                }
            },
        )
    }

    discardConfirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { discardConfirmTarget = null },
            title = { Text("Discard this bulletin?") },
            text = { Text("Your draft and any attached photos will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    queue?.cancel(target.id)
                    discardConfirmTarget = null
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { discardConfirmTarget = null }) { Text("Cancel") }
            },
        )
    }

    draftConflictTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { draftConflictTarget = null },
            title = { Text("Replace current draft?") },
            text = {
                val name = draftStore?.currentDraft?.roadName
                val tail = if (!name.isNullOrEmpty()) "for $name" else "on another road"
                Text("You have an unsaved draft $tail. Editing this bulletin will discard it.")
            },
            confirmButton = {
                TextButton(onClick = {
                    draftConflictTarget = null
                    performEdit(context, scope, target, queue, draftStore) { roadId, roadName ->
                        editingDraft = roadId to roadName
                    } ?: Unit.let { missingSnapshotMessage = "This submission could no longer be found." }
                }) { Text("Replace draft", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { draftConflictTarget = null }) { Text("Cancel") }
            },
        )
    }

    missingSnapshotMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { missingSnapshotMessage = null },
            title = { Text("Can't edit this entry") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { missingSnapshotMessage = null }) { Text("OK") } },
        )
    }

    editingDraft?.let { (roadId, roadName) ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { editingDraft = null }, sheetState = sheetState) {
            BackroadStatusReportScreen(
                roadId = roadId,
                mapStyleUrl = mapStyleUrl,
                roadName = roadName,
                onRevert = {
                    // Host flow: Pending restores the queue entry from the restoration
                    // payload. The restoration object is held in-memory here; a full
                    // 1:1 keeps the one SubmissionForEdit it got from takeForEdit so
                    // Revert can restore the original. For now we leave this as a
                    // passthrough — the composer's onRevert handler already short-
                    // circuits the save flow.
                },
                onDismiss = { editingDraft = null },
                mapBuilder = mapBuilder,
            )
        }
    }
}

@Composable
private fun PendingRow(item: PendingSubmission, onTap: () -> Unit) {
    val relative = remember(item.createdAt) {
        android.text.format.DateUtils.getRelativeTimeSpanString(item.createdAt).toString()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(item.roadName ?: "Unnamed road", style = MaterialTheme.typography.titleSmall)
        Text("Saved $relative", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(4.dp))
        when (item.state) {
            SubmissionState.queued -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MoveToInbox, contentDescription = null, tint = Color(0xFFEF6C00), modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                val base = if (item.pendingPhotoCount > 0)
                    "Will submit when online (${item.pendingPhotoCount} photo${if (item.pendingPhotoCount == 1) "" else "s"} pending)"
                else "Will submit when online"
                Text(base, style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF6C00))
            }
            SubmissionState.failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ReportProblem, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                val label = item.lastError?.let { friendlyLastError(it) } ?: "Submission failed."
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.size(4.dp))
        Row {
            TextButton(onClick = onTap) { Text("Actions") }
        }
    }
}

private fun performEdit(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    target: PendingSubmission,
    queue: SubmissionQueue?,
    draftStore: DraftStore?,
    onOpenEdit: (String, String?) -> Unit,
): Unit? {
    if (queue == null || draftStore == null) return null
    val payload = queue.takeForEdit(target.id, destinationDirectory = draftStore.photosDir()) ?: return null
    val blob = payload.draftSnapshot
    if (blob == null) {
        queue.restore(payload, photoDirectory = draftStore.photosDir())
        return null
    }
    val draft = runCatching {
        DraftStore.json.decodeFromString(Draft.serializer(), String(blob, Charsets.UTF_8))
    }.getOrNull() ?: run {
        queue.restore(payload, photoDirectory = draftStore.photosDir())
        return null
    }
    draftStore.save(draft)
    onOpenEdit(draft.roadId, draft.roadName)
    return Unit
}
