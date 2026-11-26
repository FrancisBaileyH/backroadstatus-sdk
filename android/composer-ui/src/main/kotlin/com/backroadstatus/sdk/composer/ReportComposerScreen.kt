package com.backroadstatus.sdk.composer

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.backroadstatus.sdk.core.BackroadStatus
import com.backroadstatus.sdk.core.BackroadStatusError
import com.backroadstatus.sdk.core.Condition
import com.backroadstatus.sdk.core.ConditionRatingInput
import com.backroadstatus.sdk.core.CreateBulletinInput
import com.backroadstatus.sdk.core.FeatureUpdateInput
import com.backroadstatus.sdk.core.PendingPhoto
import com.backroadstatus.sdk.core.RoadConditionPoint
import com.backroadstatus.sdk.core.RoadDetail
import com.backroadstatus.sdk.core.RoadFeatureDetail
import com.backroadstatus.sdk.core.SubmissionOutcome
import com.backroadstatus.sdk.core.SubmissionQueue
import com.backroadstatus.sdk.core.VehicleCategory
import com.backroadstatus.sdk.core.VehicleInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Drop-in composer for reporting a road's condition. Present this when
 * the user taps a road on your map.
 *
 * The screen fetches the road's geometry and features from the SDK on
 * launch (`BackroadStatus.shared.cachedRoad(id)` first, then
 * `BackroadStatus.shared.road(id)`), so the caller only needs the road ID
 * and a style URL.
 *
 * ### Map backend
 *
 * The composer is map-SDK agnostic. Pass a [mapBuilder] composable that
 * stands up your preferred adapter — e.g. `MapLibreComposerMap` from
 * `BackroadStatusMapLibreUI`, or a Mapbox equivalent. The adapter wires
 * its native map view, creates a `MapProvider`, and hands it back via
 * `binding.onReady(_)`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackroadStatusReportScreen(
    roadId: String,
    mapStyleUrl: String,
    roadName: String? = null,
    /**
     * Non-null when the composer was opened to edit an existing pending
     * submission. Triggers a third "Revert" option in the Cancel
     * confirmation dialog that calls this to put the original queue entry
     * back as-is. Null for fresh-create flows.
     */
    onRevert: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    /**
     * Parent-driven dismiss request. Increment this (e.g., in a bottom
     * sheet's `onDismissRequest`) to route through the same Discard/Save
     * prompt the Cancel button uses, so swipe-away doesn't silently
     * destroy in-progress work.
     */
    dismissRequestTick: Int = 0,
    mapBuilder: @Composable (ComposerMapBinding) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- State ---
    var currentRoadId by remember { mutableStateOf(roadId) }
    var showSignInPrompt by remember { mutableStateOf(false) }

    val mapDriver = remember { ComposerMapDriver(context) }

    var roadNameState by remember { mutableStateOf(roadName.orEmpty()) }
    var description by remember { mutableStateOf("") }
    var reportDate by remember { mutableStateOf(Date()) }
    var startStep by remember { mutableStateOf(0f) }
    val conditions = remember { mutableStateListOf<ConditionEntry>() }
    var sliderValues by remember { mutableStateOf(listOf(0f)) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var roadLoadError by remember { mutableStateOf<String?>(null) }
    var submittedId by remember { mutableStateOf<String?>(null) }
    var queuedMessage by remember { mutableStateOf<String?>(null) }

    // IDs / draft-lifecycle state.
    var clientSubmissionId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var draftId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var draftCreatedAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var draftActive by remember { mutableStateOf(false) }
    var pendingDraftSaveJob by remember { mutableStateOf<Job?>(null) }
    var cachedRoadDetail by remember { mutableStateOf<RoadDetail?>(null) }
    var pendingResumePrompt by remember {
        mutableStateOf<Draft?>(
            DraftStore.peekDraftOnDisk(context)
                ?.takeIf { it.roadId != roadId }
                ?.takeIf { it.hasMeaningfulContent() },
        )
    }
    var showCancelDialog by remember { mutableStateOf(false) }
    var geometry by remember { mutableStateOf(RoadGeometry(emptyList(), emptyList())) }
    val photos = remember { mutableStateListOf<DraftPhotoState>() }
    val discoveredFeatures = remember { mutableStateListOf<RoadFeatureDetail>() }
    val featureStatuses = remember { mutableStateMapOf<String, FeatureStatus>() }
    val featureChangeTypes = remember { mutableStateMapOf<String, String>() }
    val featureChangeComments = remember { mutableStateMapOf<String, String>() }
    val featureRemoved = remember { mutableStateMapOf<String, Boolean>() }
    val createdFeatures = remember { mutableStateListOf<CreatedFeature>() }
    var showAddFeatureSheet by remember { mutableStateOf(false) }
    var showConditionsInfo by remember { mutableStateOf(false) }
    val vehicles = remember { mutableStateListOf<VehicleCategory>() }
    var roadConditions by remember { mutableStateOf<List<RoadConditionPoint>>(emptyList()) }
    var verifyMode by remember { mutableStateOf(false) }
    var verifyValues by remember { mutableStateOf(listOf(0f, 0f)) }

    // --- Derived ---
    val hasGeometry = geometry.coordinates.size >= (Composer.marginSteps + 1).toInt()
    val maxStepIndex = if (geometry.coordinates.isEmpty()) 0f else (geometry.coordinates.size - 1).toFloat()

    val canAddMore = run {
        val last = conditions.lastOrNull()?.condition
        val pickable = Condition.userPickable.filter { it != last }
        hasGeometry && conditions.size < Composer.maxConditions && pickable.isNotEmpty() &&
            ((conditions.lastOrNull()?.step ?: startStep) + Composer.marginSteps) <= maxStepIndex
    }

    val canSubmit: Boolean = run {
        val captionsOk = photos.all { it.caption.length in Composer.captionRange }
        val nothingInFlight = photos.none { it.isUploading }
        description.length >= 8 && captionsOk && nothingInFlight && !busy &&
            submittedId == null && queuedMessage == null
    }

    val sliderMarkers: List<SliderMarker> = remember(
        hasGeometry, verifyMode, verifyValues, conditions.toList(), startStep, geometry.coordinates,
    ) {
        computeSliderMarkers(hasGeometry, verifyMode, verifyValues, conditions, startStep, geometry.coordinates)
    }
    val createdFeatureMarkers: List<PlacedFeatureMarker> = remember(createdFeatures.toList()) {
        createdFeatures.map { PlacedFeatureMarker(it.id, it.type.wire, it.position) }
    }

    // --- Submission queue (observed) ---
    val queueState = remember { mutableStateOf<SubmissionQueue?>(null) }
    LaunchedEffect(Unit) { queueState.value = SubmissionQueue.shared(context) }
    val queue = queueState.value
    val isNetworkAvailable by (queue?.isNetworkAvailable ?: kotlinx.coroutines.flow.MutableStateFlow(true)).collectAsState()

    // --- DraftStore handle ---
    val draftStoreState = remember { mutableStateOf<DraftStore?>(null) }
    LaunchedEffect(Unit) { draftStoreState.value = DraftStore.shared(context) }
    val draftStore = draftStoreState.value

    // --- First load: apply draft, then load road ---
    LaunchedEffect(currentRoadId, draftStore) {
        if (draftStore == null) return@LaunchedEffect
        val existing = draftStore.currentDraft
        when {
            existing == null || !existing.hasMeaningfulContent() -> {
                if (existing != null) draftStore.discard()
                draftStore.pruneOrphanPhotos(emptySet())
                draftActive = true
            }
            existing.roadId != currentRoadId -> {
                draftActive = false
                pendingResumePrompt = existing
            }
            else -> {
                draftStore.pruneOrphanPhotos(existing.photos.map { it.id }.toSet())
                applyDraft(
                    draft = existing,
                    draftStore = draftStore,
                    assignRoadName = { roadNameState = it },
                    assignCached = { cachedRoadDetail = it },
                    applyRoadDetail = { d ->
                        applyRoadDetailInto(
                            detail = d,
                            roadName = { n -> if (roadNameState.isEmpty() || roadNameState != n) roadNameState = n },
                            setGeometry = { g -> geometry = g },
                            setRoadConditions = { c -> roadConditions = c },
                            mergeFeatures = { fs -> mergeFeatures(fs, discoveredFeatures) },
                            setCachedRoadDetail = { cd -> cachedRoadDetail = cd },
                            verifyValues = verifyValues,
                            updateVerifyValues = { v -> verifyValues = v },
                        )
                    },
                    assignDraftId = { draftId = it },
                    assignDraftCreated = { draftCreatedAt = it },
                    assignSubmissionId = { clientSubmissionId = it },
                    assignReportDate = { reportDate = Date(it) },
                    assignDescription = { description = it },
                    assignStartStep = { startStep = it },
                    conditions = conditions,
                    assignVerifyMode = { verifyMode = it },
                    assignVerifyValues = { verifyValues = it },
                    featureStatuses = featureStatuses,
                    featureChangeTypes = featureChangeTypes,
                    featureChangeComments = featureChangeComments,
                    featureRemoved = featureRemoved,
                    createdFeaturesOut = createdFeatures,
                    vehiclesOut = vehicles,
                    assignSliderValues = { sliderValues = it },
                    photosOut = photos,
                )
                draftActive = true
            }
        }

        loadFullRoad(
            context = context,
            roadId = currentRoadId,
            hasGeometry = { hasGeometry },
            applyRoadDetail = { d ->
                applyRoadDetailInto(
                    detail = d,
                    roadName = { n -> if (roadNameState.isEmpty() || roadNameState != n) roadNameState = n },
                    setGeometry = { g -> geometry = g },
                    setRoadConditions = { c -> roadConditions = c },
                    mergeFeatures = { fs -> mergeFeatures(fs, discoveredFeatures) },
                    setCachedRoadDetail = { cd -> cachedRoadDetail = cd },
                    verifyValues = verifyValues,
                    updateVerifyValues = { v -> verifyValues = v },
                )
            },
            setRoadLoadError = { roadLoadError = it },
            setShowSignInPrompt = { showSignInPrompt = it },
        )
    }

    // --- Push data into the map driver on changes ---
    LaunchedEffect(geometry.coordinates, roadConditions) {
        mapDriver.updateCoordinates(geometry.coordinates, roadConditions)
    }
    LaunchedEffect(sliderMarkers) { mapDriver.updateSliderMarkers(sliderMarkers) }
    LaunchedEffect(createdFeatureMarkers) { mapDriver.updateCreatedFeatureMarkers(createdFeatureMarkers) }

    val hasInProgressWork: () -> Boolean = {
        description.isNotEmpty() || conditions.isNotEmpty() ||
            createdFeatures.isNotEmpty() || photos.isNotEmpty() ||
            vehicles.isNotEmpty() || verifyMode ||
            featureStatuses.values.any { it != FeatureStatus.unverified }
    }

    // --- Debounced draft save on any fingerprint-changing input ---
    LaunchedEffect(draftActive, onRevert) {
        if (!draftActive || onRevert != null || draftStore == null) return@LaunchedEffect
        snapshotFlow {
            draftFingerprint(
                description, reportDate.time, startStep,
                conditions.toList(), verifyMode, verifyValues,
                featureStatuses.toMap(), featureChangeTypes.toMap(),
                featureChangeComments.toMap(), featureRemoved.toMap(),
                createdFeatures.toList(), vehicles.toList(),
                photos.map { PhotoMetaKey(it.id, it.caption, it.reference) },
                roadNameState,
            )
        }
            .drop(1) // skip the initial value
            .distinctUntilChanged()
            .debounce(500)
            .onEach {
                if (hasInProgressWork()) {
                    val snapshot = buildDraftSnapshot(
                        draftId, clientSubmissionId, currentRoadId, roadNameState,
                        draftCreatedAt, reportDate, description, startStep,
                        conditions.toList(), verifyMode, verifyValues,
                        featureStatuses.toMap(), featureChangeTypes.toMap(),
                        featureChangeComments.toMap(), featureRemoved.toMap(),
                        createdFeatures.toList(), vehicles.toList(),
                        photos.map { DraftPhotoMeta(it.id, it.caption, it.reference) },
                        cachedRoadDetail,
                    )
                    withContext(Dispatchers.IO) { draftStore.save(snapshot) }
                } else {
                    withContext(Dispatchers.IO) { draftStore.discard() }
                }
            }
            .collect {}
    }

    // --- Re-upload pending photos when connectivity returns ---
    LaunchedEffect(isNetworkAvailable) {
        if (!isNetworkAvailable) return@LaunchedEffect
        for (i in photos.indices) {
            if (photos[i].isPendingUpload || photos[i].uploadError != null) {
                photos[i] = photos[i].copy(isUploading = true, uploadError = null)
                val id = photos[i].id
                val data = photos[i].data
                scope.launch { uploadDraftPhoto(context, id, data, photos) }
            }
        }
    }

    // --- Photo picker launcher ---
    val photoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isNullOrEmpty() || draftStore == null) return@rememberLauncherForActivityResult
        scope.launch {
            for (uri in uris) {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                } ?: continue
                val photoId = UUID.randomUUID().toString()
                withContext(Dispatchers.IO) { draftStore.writePhoto(photoId, bytes) }
                val isUploading = isNetworkAvailable
                photos.add(
                    DraftPhotoState(
                        id = photoId,
                        data = bytes,
                        caption = "",
                        isUploading = isUploading,
                    ),
                )
                if (isUploading) {
                    scope.launch { uploadDraftPhoto(context, photoId, bytes, photos) }
                }
                if (photos.size >= Composer.maxPhotos) break
            }
        }
    }

    val requestDismiss: () -> Unit = {
        if (!busy) {
            handleCancelTap(
                onRevert = onRevert,
                draftActive = draftActive,
                hasInProgressWork = hasInProgressWork,
                onShowDialog = { showCancelDialog = true },
                onDiscardAndDismiss = {
                    pendingDraftSaveJob?.cancel()
                    draftStore?.discard()
                    onDismiss()
                },
                onPlainDismiss = onDismiss,
            )
        }
    }

    LaunchedEffect(dismissRequestTick) {
        if (dismissRequestTick > 0) requestDismiss()
    }

    // --- UI ---
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Create report") },
                navigationIcon = {
                    TextButton(
                        enabled = !busy,
                        onClick = requestDismiss,
                    ) { Text("Cancel") }
                },
                actions = {
                    if (pendingResumePrompt == null) {
                        TextButton(
                            enabled = canSubmit,
                            onClick = {
                                scope.launch {
                                    submit(
                                        context = context,
                                        verifyMode = verifyMode,
                                        geometry = geometry,
                                        verifyValues = verifyValues,
                                        roadConditions = roadConditions,
                                        conditions = conditions.toList(),
                                        startStep = startStep,
                                        photos = photos.toList(),
                                        pathId = currentRoadId,
                                        reportDate = reportDate,
                                        description = description,
                                        vehicles = vehicles.toList(),
                                        discoveredFeatures = discoveredFeatures.toList(),
                                        featureStatuses = featureStatuses.toMap(),
                                        featureChangeTypes = featureChangeTypes.toMap(),
                                        featureChangeComments = featureChangeComments.toMap(),
                                        featureRemoved = featureRemoved.toMap(),
                                        createdFeatures = createdFeatures.toList(),
                                        clientSubmissionId = clientSubmissionId,
                                        roadName = roadNameState,
                                        draftSnapshot = buildDraftSnapshot(
                                            draftId, clientSubmissionId, currentRoadId, roadNameState,
                                            draftCreatedAt, reportDate, description, startStep,
                                            conditions.toList(), verifyMode, verifyValues,
                                            featureStatuses.toMap(), featureChangeTypes.toMap(),
                                            featureChangeComments.toMap(), featureRemoved.toMap(),
                                            createdFeatures.toList(), vehicles.toList(),
                                            photos.map { DraftPhotoMeta(it.id, it.caption, it.reference) },
                                            cachedRoadDetail,
                                        ),
                                        setBusy = { busy = it },
                                        setError = { errorMessage = it },
                                        setSubmittedId = { submittedId = it },
                                        setQueuedMessage = { queuedMessage = it },
                                        clearDraft = {
                                            pendingDraftSaveJob?.cancel()
                                            draftStore?.discard()
                                            draftActive = false
                                        },
                                        onDone = { onDismiss() },
                                    )
                                }
                            },
                        ) { Text(if (busy) "Submitting…" else "Submit") }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (pendingResumePrompt != null) {
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize().background(MaterialTheme.colorScheme.surface))
        } else {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (!isNetworkAvailable) OfflineBanner()

                // Map region.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 320.dp, max = 480.dp)
                        .height(360.dp),
                ) {
                    mapBuilder(
                        ComposerMapBinding(
                            styleUrl = mapStyleUrl,
                            onReady = { provider ->
                                mapDriver.attach(provider, pathId = currentRoadId)
                                mapDriver.updateCoordinates(geometry.coordinates, roadConditions)
                                mapDriver.updateSliderMarkers(sliderMarkers)
                                mapDriver.updateCreatedFeatureMarkers(createdFeatureMarkers)
                            },
                        ),
                    )
                }

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    errorMessage?.let { SubmitErrorCard(it) }

                    GroupedSection(header = "Road") {
                        Text(if (roadNameState.isEmpty()) "Loading…" else roadNameState, style = MaterialTheme.typography.titleMedium)
                    }

                    ConditionsSection(
                        hasGeometry = hasGeometry,
                        roadLoadError = roadLoadError,
                        onReloadRoad = {
                            roadLoadError = null
                            scope.launch {
                                loadFullRoad(
                                    context, currentRoadId,
                                    { hasGeometry },
                                    applyRoadDetail = { d ->
                                        applyRoadDetailInto(
                                            detail = d,
                                            roadName = { n -> if (roadNameState.isEmpty() || roadNameState != n) roadNameState = n },
                                            setGeometry = { g -> geometry = g },
                                            setRoadConditions = { c -> roadConditions = c },
                                            mergeFeatures = { fs -> mergeFeatures(fs, discoveredFeatures) },
                                            setCachedRoadDetail = { cd -> cachedRoadDetail = cd },
                                            verifyValues = verifyValues,
                                            updateVerifyValues = { v -> verifyValues = v },
                                        )
                                    },
                                    setRoadLoadError = { roadLoadError = it },
                                    setShowSignInPrompt = { showSignInPrompt = it },
                                )
                            }
                        },
                        conditions = conditions,
                        sliderValues = sliderValues,
                        onSliderValuesChange = { new ->
                            sliderValues = new
                            syncFromSlider(new, conditions, startStepSetter = { startStep = it })
                        },
                        maxStepIndex = maxStepIndex,
                        canAddMore = canAddMore,
                        verifyMode = verifyMode,
                        verifyValues = verifyValues,
                        roadConditionsAvailable = roadConditions.isNotEmpty(),
                        onToggleVerify = { verifyMode = it },
                        onVerifyValuesChange = { verifyValues = it },
                        geometry = geometry,
                        onAddCondition = { condition ->
                            if (!canAddMore) return@ConditionsSection
                            verifyMode = false
                            val lastStep = conditions.lastOrNull()?.step ?: startStep
                            val jump = maxOf(Composer.marginSteps, kotlin.math.round(maxStepIndex * Composer.jumpFraction))
                            val step = minOf(lastStep + jump, maxStepIndex)
                            conditions.add(ConditionEntry(condition = condition, step = step))
                            sliderValues = listOf(startStep) + conditions.map { it.step }
                        },
                        onDeleteCondition = { index ->
                            if (index < 0 || index >= conditions.size) return@ConditionsSection
                            if (index > 0 && index < conditions.size - 1 &&
                                conditions[index - 1].condition == conditions[index + 1].condition
                            ) {
                                conditions.removeAt(index)
                                conditions.removeAt(index - 1)
                            } else {
                                conditions.removeAt(index)
                            }
                            if (conditions.isEmpty()) startStep = 0f
                            sliderValues = listOf(startStep) + conditions.map { it.step }
                        },
                        onShowInfo = { showConditionsInfo = true },
                    )

                    GroupedSection(header = "Report date") {
                        ReportDatePicker(
                            selectedDate = reportDate,
                            onChange = { reportDate = it },
                        )
                    }

                    GroupedSection(header = "Description", footerText = "At least 8 characters. 1000 max.", footerError = description.length < 8) {
                        TextField(
                            value = description,
                            onValueChange = { new -> description = if (new.length > 1000) new.take(1000) else new },
                            placeholder = { Text("Describe the road conditions you found…") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                            minLines = 5,
                            colors = borderlessTextFieldColors(),
                        )
                    }

                    VehiclesSection(
                        vehicles = vehicles,
                        onAdd = { v -> if (vehicles.size < Composer.maxVehicles && v !in vehicles) vehicles.add(v) },
                        onRemove = { v -> vehicles.removeAll { it == v } },
                    )

                    FeaturesSection(
                        discovered = discoveredFeatures.toList(),
                        created = createdFeatures.toList(),
                        hasGeometry = hasGeometry,
                        featureStatuses = featureStatuses,
                        featureChangeTypes = featureChangeTypes,
                        featureChangeComments = featureChangeComments,
                        featureRemoved = featureRemoved,
                        geometry = geometry,
                        onRemoveCreated = { id -> createdFeatures.removeAll { it.id == id } },
                        onAddFeature = { showAddFeatureSheet = true },
                    )

                    PhotosSection(
                        photos = photos,
                        onPickPhotos = { photoLauncher.launch("image/*") },
                        onRemove = { photo ->
                            photos.removeAll { it.id == photo.id }
                            draftStore?.removePhoto(photo.id)
                        },
                    )

                    if (submittedId != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
                            Spacer(Modifier.size(8.dp))
                            Text("Report submitted", color = Color(0xFF2E7D32))
                        }
                    }
                    queuedMessage?.let { msg ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Save, contentDescription = null, tint = Color(0xFFEF6C00))
                            Spacer(Modifier.size(8.dp))
                            Text(msg, color = Color(0xFFEF6C00))
                        }
                    }

                    DraftFooter(
                        visible = draftActive && draftStore?.currentDraft?.roadId == currentRoadId,
                        onSaveForLater = {
                            pendingDraftSaveJob?.cancel()
                            if (draftActive && draftStore != null) {
                                val snap = buildDraftSnapshot(
                                    draftId, clientSubmissionId, currentRoadId, roadNameState,
                                    draftCreatedAt, reportDate, description, startStep,
                                    conditions.toList(), verifyMode, verifyValues,
                                    featureStatuses.toMap(), featureChangeTypes.toMap(),
                                    featureChangeComments.toMap(), featureRemoved.toMap(),
                                    createdFeatures.toList(), vehicles.toList(),
                                    photos.map { DraftPhotoMeta(it.id, it.caption, it.reference) },
                                    cachedRoadDetail,
                                )
                                draftStore.save(snap)
                            }
                            onDismiss()
                        },
                        onDiscard = {
                            pendingDraftSaveJob?.cancel()
                            draftStore?.discard()
                            onDismiss()
                        },
                    )
                }
            }
        }
    }

    // --- Dialogs & sheets ---
    val existingDraft = pendingResumePrompt
    if (existingDraft != null) {
        AlertDialog(
            onDismissRequest = { pendingResumePrompt = null },
            title = { Text("Unfinished draft") },
            text = {
                val name = existingDraft.roadName ?: "another road"
                Text("You have an unfinished report for $name. Resume where you left off, or discard it to start a new report here?")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingResumePrompt = null
                    if (existingDraft.roadId != currentRoadId) {
                        geometry = RoadGeometry(emptyList(), emptyList())
                        roadConditions = emptyList()
                        discoveredFeatures.clear()
                        roadNameState = ""
                        currentRoadId = existingDraft.roadId
                    }
                    draftStoreState.value?.let { ds ->
                        applyDraft(
                            draft = existingDraft,
                            draftStore = ds,
                            assignRoadName = { roadNameState = it },
                            assignCached = { cachedRoadDetail = it },
                            applyRoadDetail = { d ->
                                applyRoadDetailInto(
                                    detail = d,
                                    roadName = { n -> if (roadNameState.isEmpty() || roadNameState != n) roadNameState = n },
                                    setGeometry = { g -> geometry = g },
                                    setRoadConditions = { c -> roadConditions = c },
                                    mergeFeatures = { fs -> mergeFeatures(fs, discoveredFeatures) },
                                    setCachedRoadDetail = { cd -> cachedRoadDetail = cd },
                                    verifyValues = verifyValues,
                                    updateVerifyValues = { v -> verifyValues = v },
                                )
                            },
                            assignDraftId = { draftId = it },
                            assignDraftCreated = { draftCreatedAt = it },
                            assignSubmissionId = { clientSubmissionId = it },
                            assignReportDate = { reportDate = Date(it) },
                            assignDescription = { description = it },
                            assignStartStep = { startStep = it },
                            conditions = conditions,
                            assignVerifyMode = { verifyMode = it },
                            assignVerifyValues = { verifyValues = it },
                            featureStatuses = featureStatuses,
                            featureChangeTypes = featureChangeTypes,
                            featureChangeComments = featureChangeComments,
                            featureRemoved = featureRemoved,
                            createdFeaturesOut = createdFeatures,
                            vehiclesOut = vehicles,
                            assignSliderValues = { sliderValues = it },
                            photosOut = photos,
                        )
                        draftActive = true
                    }
                }) { Text("Resume") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingResumePrompt = null
                        draftStore?.discard()
                        draftActive = true
                    }) { Text("Discard") }
                    TextButton(onClick = {
                        pendingResumePrompt = null
                        onDismiss()
                    }) { Text("Cancel") }
                }
            },
        )
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Save your work?") },
            text = { Text("Your draft can be resumed later.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    if (draftActive && draftStore != null) {
                        val snap = buildDraftSnapshot(
                            draftId, clientSubmissionId, currentRoadId, roadNameState,
                            draftCreatedAt, reportDate, description, startStep,
                            conditions.toList(), verifyMode, verifyValues,
                            featureStatuses.toMap(), featureChangeTypes.toMap(),
                            featureChangeComments.toMap(), featureRemoved.toMap(),
                            createdFeatures.toList(), vehicles.toList(),
                            photos.map { DraftPhotoMeta(it.id, it.caption, it.reference) },
                            cachedRoadDetail,
                        )
                        draftStore.save(snap)
                    }
                    onDismiss()
                }) { Text("Save for later") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showCancelDialog = false
                        draftStore?.discard()
                        onDismiss()
                    }) { Text("Discard") }
                    TextButton(onClick = { showCancelDialog = false }) { Text("Keep editing") }
                }
            },
        )
    }

    if (showSignInPrompt) {
        AlertDialog(
            onDismissRequest = { showSignInPrompt = false; onDismiss() },
            title = { Text("Sign in to continue") },
            text = { Text("You need to be signed in to view road details and submit a bulletin.") },
            confirmButton = {
                TextButton(onClick = {
                    showSignInPrompt = false
                    val activity = context as? ComponentActivity
                    if (activity != null) {
                        scope.launch {
                            try {
                                BackroadStatus.shared.signIn(activity)
                                loadFullRoad(
                                    context = context,
                                    roadId = currentRoadId,
                                    hasGeometry = { hasGeometry },
                                    applyRoadDetail = { d ->
                                        applyRoadDetailInto(
                                            detail = d,
                                            roadName = { n -> if (roadNameState.isEmpty() || roadNameState != n) roadNameState = n },
                                            setGeometry = { g -> geometry = g },
                                            setRoadConditions = { c -> roadConditions = c },
                                            mergeFeatures = { fs -> mergeFeatures(fs, discoveredFeatures) },
                                            setCachedRoadDetail = { cd -> cachedRoadDetail = cd },
                                            verifyValues = verifyValues,
                                            updateVerifyValues = { v -> verifyValues = v },
                                        )
                                    },
                                    setRoadLoadError = { roadLoadError = it },
                                    setShowSignInPrompt = { showSignInPrompt = it },
                                )
                            } catch (t: Throwable) {
                                Log.i("Composer", "Sign-in failed: ${t.message}")
                                onDismiss()
                            }
                        }
                    } else onDismiss()
                }) { Text("Sign In") }
            },
            dismissButton = {
                TextButton(onClick = { showSignInPrompt = false; onDismiss() }) { Text("Cancel") }
            },
        )
    }

    if (showConditionsInfo) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { showConditionsInfo = false }, sheetState = sheetState) {
            ConditionsInfoSheet(onDismiss = { showConditionsInfo = false })
        }
    }

    if (showAddFeatureSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showAddFeatureSheet = false }, sheetState = sheetState) {
            AddFeatureScreen(
                pathId = currentRoadId,
                mapStyleUrl = mapStyleUrl,
                roadCoordinates = geometry.coordinates,
                roadConditions = roadConditions,
                existingFeatures = createdFeatureMarkers,
                onSave = { f ->
                    createdFeatures.add(f)
                    showAddFeatureSheet = false
                },
                onCancel = { showAddFeatureSheet = false },
                mapBuilder = mapBuilder,
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Section composables                                                 */
/* ------------------------------------------------------------------ */

@Composable
private fun OfflineBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFEF6C00))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.WifiOff, contentDescription = null, tint = Color.White)
        Spacer(Modifier.size(8.dp))
        Text("Offline — your work is saved locally.", color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun borderlessTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

@Composable
private fun GroupedSection(
    header: String? = null,
    footerText: String? = null,
    footerError: Boolean = false,
    headerAccessory: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column {
        if (header != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) {
                Text(
                    text = header.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                headerAccessory?.invoke()
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
        if (footerText != null) {
            Text(
                text = footerText,
                style = MaterialTheme.typography.bodySmall,
                color = if (footerError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportDatePicker(selectedDate: Date, onChange: (Date) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val formatted = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(selectedDate)
    TextButton(onClick = { showDialog = true }) {
        Text(formatted, style = MaterialTheme.typography.bodyMedium)
    }
    if (showDialog) {
        val state = rememberDatePickerState(initialSelectedDateMillis = selectedDate.time)
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Date(it)) }
                    showDialog = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun ConditionsSection(
    hasGeometry: Boolean,
    roadLoadError: String?,
    onReloadRoad: () -> Unit,
    conditions: MutableList<ConditionEntry>,
    sliderValues: List<Float>,
    onSliderValuesChange: (List<Float>) -> Unit,
    maxStepIndex: Float,
    canAddMore: Boolean,
    verifyMode: Boolean,
    verifyValues: List<Float>,
    roadConditionsAvailable: Boolean,
    onToggleVerify: (Boolean) -> Unit,
    onVerifyValuesChange: (List<Float>) -> Unit,
    geometry: RoadGeometry,
    onAddCondition: (Condition) -> Unit,
    onDeleteCondition: (Int) -> Unit,
    onShowInfo: () -> Unit,
) {
    if (!hasGeometry) {
        if (roadLoadError != null) {
            GroupedSection {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Couldn't load road", style = MaterialTheme.typography.titleSmall)
                        Text(roadLoadError, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onReloadRoad) { Text("Retry") }
                }
            }
        } else {
            GroupedSection { Text("Loading road…", style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        return
    }
    GroupedSection(
        header = "Road Conditions (Optional)",
        headerAccessory = { IconButton(onClick = onShowInfo, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Info, contentDescription = "About road condition ratings") } },
    ) {
        if (!verifyMode) ConditionDropdown(canAddMore = canAddMore, lastCondition = conditions.lastOrNull()?.condition, onPick = onAddCondition)
        if (conditions.isEmpty() && !verifyMode) {
            Text("Select multiple road conditions from the dropdown and drag condition handles to where conditions changed.", style = MaterialTheme.typography.bodySmall)
        }
        if (conditions.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Conditions unchanged", modifier = Modifier.weight(1f))
                Switch(checked = verifyMode, enabled = roadConditionsAvailable, onCheckedChange = onToggleVerify)
            }
        }
        if (verifyMode) {
            SegmentedSliderView(
                values = verifyValues,
                onValuesChange = onVerifyValuesChange,
                valueRange = 0f..maxStepIndex,
                segmentColors = listOf(Color(0xFF007AFF)),
                snap = Composer.snapStep,
                minimumGap = Composer.marginSteps,
                labels = verifyValues.map { formatStepDistance(geometry, it.toInt()) },
            )
        } else if (conditions.isNotEmpty()) {
            SegmentedSliderView(
                values = sliderValues,
                onValuesChange = onSliderValuesChange,
                valueRange = 0f..maxStepIndex,
                segmentColors = conditions.map { it.condition.color },
                snap = Composer.snapStep,
                minimumGap = Composer.marginSteps,
                labels = sliderValues.map { formatStepDistance(geometry, it.toInt()) },
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                conditions.forEachIndexed { index, entry ->
                    ConditionChip(entry.condition, onRemove = { onDeleteCondition(index) })
                }
            }
        }
    }
}

@Composable
private fun ConditionDropdown(canAddMore: Boolean, lastCondition: Condition?, onPick: (Condition) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val pickable = Condition.userPickable.filter { it != lastCondition }
    Box {
        TextButton(enabled = canAddMore && pickable.isNotEmpty(), onClick = { expanded = true }) {
            Text(if (canAddMore) "Add condition" else "Can't add more")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (c in pickable) {
                DropdownMenuItem(
                    text = { Text(c.displayName) },
                    onClick = { expanded = false; onPick(c) },
                )
            }
        }
    }
}

@Composable
private fun ConditionChip(condition: Condition, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(condition.color, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(condition.displayName, style = MaterialTheme.typography.labelMedium, color = condition.chipForeground)
        Spacer(Modifier.size(6.dp))
        IconButton(onClick = onRemove, modifier = Modifier.size(18.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = condition.chipForeground, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun VehiclesSection(
    vehicles: MutableList<VehicleCategory>,
    onAdd: (VehicleCategory) -> Unit,
    onRemove: (VehicleCategory) -> Unit,
) {
    val pickable = VehicleCategory.userPickable.filter { it !in vehicles }
    val canAdd = vehicles.size < Composer.maxVehicles && pickable.isNotEmpty()
    GroupedSection(
        header = "Modes of Travel",
        footerText = if (vehicles.isEmpty())
            "Optional. Pick up to ${Composer.maxVehicles} modes you used on this road."
        else "Up to ${Composer.maxVehicles}. Tap × to remove.",
    ) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            TextButton(enabled = canAdd, onClick = { expanded = true }) {
                Text(if (canAdd) "Add mode" else "Can't add more")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (v in pickable) {
                    DropdownMenuItem(text = { Text(v.displayName) }, onClick = { expanded = false; onAdd(v) })
                }
            }
        }
        if (vehicles.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (v in vehicles) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(v.displayName, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.size(6.dp))
                        IconButton(onClick = { onRemove(v) }, modifier = Modifier.size(18.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturesSection(
    discovered: List<RoadFeatureDetail>,
    created: List<CreatedFeature>,
    hasGeometry: Boolean,
    featureStatuses: androidx.compose.runtime.snapshots.SnapshotStateMap<String, FeatureStatus>,
    featureChangeTypes: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    featureChangeComments: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    featureRemoved: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    geometry: RoadGeometry,
    onRemoveCreated: (String) -> Unit,
    onAddFeature: () -> Unit,
) {
    val footer: String = when {
        discovered.isEmpty() && created.isEmpty() -> "Add gates, bridges, or warnings you came across on this road."
        discovered.isNotEmpty() -> "Mark each discovered feature as you verified it, or pick Changed to update its type / add a comment."
        else -> "New features will be submitted with your report."
    }

    GroupedSection(header = "Road Features", footerText = footer) {
        val rows: List<Pair<String, Any>> = (discovered.map { "d-${it.id}" to it } + created.map { "c-${it.id}" to it })
            .sortedBy { (_, item) ->
                val pos = when (item) {
                    is RoadFeatureDetail -> item.position
                    is CreatedFeature -> item.position
                    else -> emptyList()
                }
                geometry.coordinates.indexOfFirst { it == pos }.takeIf { it >= 0 } ?: Int.MAX_VALUE
            }
        rows.forEachIndexed { index, pair ->
            val (key, item) = pair
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer)
            when (item) {
                is RoadFeatureDetail -> DiscoveredFeatureRow(
                    feature = item,
                    featureStatuses = featureStatuses,
                    featureChangeTypes = featureChangeTypes,
                    featureChangeComments = featureChangeComments,
                    featureRemoved = featureRemoved,
                    geometry = geometry,
                )
                is CreatedFeature -> CreatedFeatureRow(feature = item, geometry = geometry, onRemove = { onRemoveCreated(item.id) })
            }
            key(key) {}
        }
        if (rows.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer)
        TextButton(onClick = onAddFeature, enabled = hasGeometry) {
            Icon(Icons.Filled.AddCircleOutline, contentDescription = null)
            Spacer(Modifier.size(4.dp))
            Text("Add Feature")
        }
    }
}

@Composable
private fun DiscoveredFeatureRow(
    feature: RoadFeatureDetail,
    featureStatuses: androidx.compose.runtime.snapshots.SnapshotStateMap<String, FeatureStatus>,
    featureChangeTypes: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    featureChangeComments: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    featureRemoved: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    geometry: RoadGeometry,
) {
    val status = featureStatuses[feature.id] ?: FeatureStatus.unverified
    val options = FeatureTransition.options(feature.type)
    val effectiveType = if (status == FeatureStatus.changed)
        featureChangeTypes[feature.id] ?: feature.type else feature.type

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FeatureSvgImage(typeName = effectiveType, modifier = Modifier.size(24.dp))
            Spacer(Modifier.size(10.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    feature.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                featureDistanceLabel(geometry, feature.position)?.let {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            // Status picker.
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = true }) { Text(status.displayName) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (s in FeatureStatus.entries) {
                    DropdownMenuItem(text = { Text(s.displayName) }, onClick = { expanded = false; featureStatuses[feature.id] = s })
                }
            }
        }
        feature.description?.let { desc -> Text(desc, style = MaterialTheme.typography.bodySmall) }
        if (status == FeatureStatus.changed) {
            val removed = featureRemoved[feature.id] ?: false
            if (options != null) {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (opt in options) {
                        val selected = (featureChangeTypes[feature.id] ?: feature.type) == opt.rawValue
                        TextButton(
                            enabled = !removed,
                            onClick = { featureChangeTypes[feature.id] = opt.rawValue },
                            modifier = Modifier.background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
                                RoundedCornerShape(10.dp),
                            ),
                        ) { Text(opt.displayName) }
                    }
                }
            }
            TextField(
                value = featureChangeComments[feature.id].orEmpty(),
                onValueChange = { new -> featureChangeComments[feature.id] = if (new.length > 200) new.take(200) else new },
                placeholder = { Text("Comment (optional)") },
                enabled = !removed,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                colors = borderlessTextFieldColors(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("No longer present", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = removed, onCheckedChange = { featureRemoved[feature.id] = it })
            }
        }
    }
}

@Composable
private fun CreatedFeatureRow(feature: CreatedFeature, geometry: RoadGeometry, onRemove: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FeatureSvgImage(typeName = feature.type.wire, modifier = Modifier.size(24.dp))
            Spacer(Modifier.size(10.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    feature.type.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                featureDistanceLabel(geometry, feature.position)?.let {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    "New",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF2E7D32),
                    modifier = Modifier
                        .background(Color(0xFF2E7D32).copy(alpha = 0.18f), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove feature") }
        }
        feature.comment?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PhotosSection(
    photos: MutableList<DraftPhotoState>,
    onPickPhotos: () -> Unit,
    onRemove: (DraftPhotoState) -> Unit,
) {
    GroupedSection(header = "Photos") {
        TextButton(onClick = onPickPhotos, enabled = photos.size < Composer.maxPhotos) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
            Spacer(Modifier.size(4.dp))
            Text("Add photos")
        }
        for (i in photos.indices) {
            val photo = photos[i]
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    AsyncImage(
                        model = photo.data,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    IconButton(
                        onClick = { onRemove(photo) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    ) { Icon(Icons.Filled.Delete, contentDescription = "Remove photo", tint = Color.White) }
                }
                val statusText: Pair<String, Color> = when {
                    photo.uploadError != null -> photo.uploadError!! to MaterialTheme.colorScheme.error
                    photo.reference != null -> "Uploaded" to Color(0xFF2E7D32)
                    photo.isUploading -> "Uploading…" to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> "Will upload when online" to Color(0xFFEF6C00)
                }
                Text(statusText.first, style = MaterialTheme.typography.bodySmall, color = statusText.second)
                TextField(
                    value = photo.caption,
                    onValueChange = { new ->
                        val capped = if (new.length > Composer.captionRange.last) new.take(Composer.captionRange.last) else new
                        photos[i] = photo.copy(caption = capped)
                    },
                    placeholder = { Text("Caption") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    colors = borderlessTextFieldColors(),
                )
                Text(
                    "${photo.caption.length} / ${Composer.captionRange.last}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (photo.caption.length in Composer.captionRange) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DraftFooter(visible: Boolean, onSaveForLater: () -> Unit, onDiscard: () -> Unit) {
    if (!visible) return
    GroupedSection(footerText = "Your changes are saved automatically. Save and continue later to come back to this report, or discard it to throw it away.") {
        TextButton(onClick = onSaveForLater, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Save, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Save and continue later", modifier = Modifier.weight(1f))
        }
        TextButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.size(8.dp))
            Text("Discard draft", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SubmitErrorCard(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.size(10.dp))
        Column {
            Text("Couldn't submit report", style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/* ------------------------------------------------------------------ */
/* Pure helpers                                                        */
/* ------------------------------------------------------------------ */

private data class PhotoMetaKey(val id: String, val caption: String, val reference: String?)

/**
 * Treats a draft as meaningful only if it carries at least one piece of
 * user-authored content. Mirrors the composable-local `hasInProgressWork`
 * check but operates on a serialized [Draft]. Server-derived fields like
 * `roadName` and `cachedRoad` don't count — those can land async without
 * user intent and shouldn't make a draft look resumable.
 */
private fun Draft.hasMeaningfulContent(): Boolean =
    description.isNotEmpty() || conditions.isNotEmpty() ||
        createdFeatures.isNotEmpty() || photos.isNotEmpty() ||
        vehicles.isNotEmpty() || verifyMode ||
        featureStatuses.values.any { it != FeatureStatus.unverified }

private fun draftFingerprint(
    description: String,
    reportDate: Long,
    startStep: Float,
    conditions: List<ConditionEntry>,
    verifyMode: Boolean,
    verifyValues: List<Float>,
    featureStatuses: Map<String, FeatureStatus>,
    featureChangeTypes: Map<String, String>,
    featureChangeComments: Map<String, String>,
    featureRemoved: Map<String, Boolean>,
    createdFeatures: List<CreatedFeature>,
    vehicles: List<VehicleCategory>,
    photos: List<PhotoMetaKey>,
    roadName: String,
): Int {
    var h = description.hashCode()
    h = 31 * h + reportDate.hashCode()
    h = 31 * h + startStep.hashCode()
    h = 31 * h + conditions.hashCode()
    h = 31 * h + verifyMode.hashCode()
    h = 31 * h + verifyValues.hashCode()
    h = 31 * h + featureStatuses.hashCode()
    h = 31 * h + featureChangeTypes.hashCode()
    h = 31 * h + featureChangeComments.hashCode()
    h = 31 * h + featureRemoved.hashCode()
    h = 31 * h + createdFeatures.hashCode()
    h = 31 * h + vehicles.hashCode()
    h = 31 * h + photos.hashCode()
    h = 31 * h + roadName.hashCode()
    return h
}

private fun buildDraftSnapshot(
    draftId: String,
    clientSubmissionId: String,
    roadId: String,
    roadName: String,
    draftCreatedAt: Long,
    reportDate: Date,
    description: String,
    startStep: Float,
    conditions: List<ConditionEntry>,
    verifyMode: Boolean,
    verifyValues: List<Float>,
    featureStatuses: Map<String, FeatureStatus>,
    featureChangeTypes: Map<String, String>,
    featureChangeComments: Map<String, String>,
    featureRemoved: Map<String, Boolean>,
    createdFeatures: List<CreatedFeature>,
    vehicles: List<VehicleCategory>,
    photoMetas: List<DraftPhotoMeta>,
    cachedRoad: RoadDetail?,
): Draft = Draft(
    id = draftId,
    clientSubmissionId = clientSubmissionId,
    roadId = roadId,
    roadName = roadName.ifEmpty { null },
    createdAt = draftCreatedAt,
    updatedAt = System.currentTimeMillis(),
    reportDate = reportDate.time,
    description = description,
    startStep = startStep,
    conditions = conditions,
    verifyMode = verifyMode,
    verifyValues = verifyValues,
    featureStatuses = featureStatuses,
    featureChangeTypes = featureChangeTypes,
    featureChangeComments = featureChangeComments,
    featureRemoved = featureRemoved,
    createdFeatures = createdFeatures,
    vehicles = vehicles,
    photos = photoMetas,
    cachedRoad = cachedRoad,
)

private fun computeSliderMarkers(
    hasGeometry: Boolean,
    verifyMode: Boolean,
    verifyValues: List<Float>,
    conditions: List<ConditionEntry>,
    startStep: Float,
    coordinates: List<List<Double>>,
): List<SliderMarker> {
    if (!hasGeometry) return emptyList()
    if (verifyMode) {
        if (verifyValues.size != 2) return emptyList()
        val out = ArrayList<SliderMarker>()
        coordAtStep(coordinates, verifyValues[0].toInt())?.let {
            out.add(SliderMarker("verify-from", it, Color(0xFF007AFF)))
        }
        coordAtStep(coordinates, verifyValues[1].toInt())?.let {
            out.add(SliderMarker("verify-to", it, Color(0xFF007AFF)))
        }
        return out
    }
    if (conditions.isEmpty()) return emptyList()
    val out = ArrayList<SliderMarker>()
    val startColor = conditions.first().condition.color
    coordAtStep(coordinates, startStep.toInt())?.let {
        out.add(SliderMarker("start", it, startColor))
    }
    for (entry in conditions) {
        coordAtStep(coordinates, entry.step.toInt())?.let {
            out.add(SliderMarker(entry.id, it, entry.condition.color))
        }
    }
    return out
}

private fun coordAtStep(coordinates: List<List<Double>>, step: Int): List<Double>? =
    coordinates.getOrNull(step)

private fun syncFromSlider(
    newValues: List<Float>,
    conditions: MutableList<ConditionEntry>,
    startStepSetter: (Float) -> Unit,
) {
    if (newValues.size != conditions.size + 1) return
    startStepSetter(newValues[0])
    for (i in 1 until newValues.size) {
        val idx = i - 1
        if (idx < conditions.size && conditions[idx].step != newValues[i]) {
            conditions[idx] = conditions[idx].copy(step = newValues[i])
        }
    }
}

private fun formatStepDistance(geometry: RoadGeometry, step: Int): String {
    if (step < 0 || step >= geometry.cumulativeDistances.size) return "0"
    return formatDistance(geometry.cumulativeDistances[step])
}

private fun formatDistance(meters: Double): String {
    if (meters < 1000) return "${meters.toInt()} m"
    return String.format(Locale.US, "%.1f km", meters / 1000.0)
}

private fun featureDistanceLabel(geometry: RoadGeometry, position: List<Double>): String? {
    if (position.size < 2) return null
    val idx = geometry.coordinates.indexOfFirst { it == position }
    if (idx < 0 || idx >= geometry.cumulativeDistances.size) return null
    val km = geometry.cumulativeDistances[idx] / 1000.0
    return String.format(Locale.US, "@ %.1f km", km)
}

private fun mergeFeatures(newFeatures: List<RoadFeatureDetail>, out: MutableList<RoadFeatureDetail>) {
    val byId = out.associateBy { it.id }.toMutableMap()
    var added = 0
    for (f in newFeatures) {
        if (f.id !in byId) { byId[f.id] = f; added++ }
    }
    if (added > 0) {
        val sorted = byId.values.sortedBy { it.displayName }
        out.clear()
        out.addAll(sorted)
    }
}

@Suppress("LongParameterList")
private fun applyRoadDetailInto(
    detail: RoadDetail,
    roadName: (String) -> Unit,
    setGeometry: (RoadGeometry) -> Unit,
    setRoadConditions: (List<RoadConditionPoint>) -> Unit,
    mergeFeatures: (List<RoadFeatureDetail>) -> Unit,
    setCachedRoadDetail: (RoadDetail) -> Unit,
    verifyValues: List<Float>,
    updateVerifyValues: (List<Float>) -> Unit,
) {
    roadName(detail.name)
    val newGeometry = RoadGeometry.from(detail.coordinates)
    setGeometry(newGeometry)
    val maxIdx = maxOf(0f, (newGeometry.coordinates.size - 1).toFloat())
    if (verifyValues == listOf(0f, 0f)) {
        updateVerifyValues(listOf(0f, kotlin.math.round(maxIdx / 2f)))
    } else {
        updateVerifyValues(verifyValues.map { minOf(it, maxIdx) })
    }
    mergeFeatures(detail.features)
    setRoadConditions(detail.conditions)
    setCachedRoadDetail(detail)
}

private suspend fun loadFullRoad(
    context: Context,
    roadId: String,
    hasGeometry: () -> Boolean,
    applyRoadDetail: (RoadDetail) -> Unit,
    setRoadLoadError: (String?) -> Unit,
    setShowSignInPrompt: (Boolean) -> Unit,
) {
    val cached = BackroadStatus.shared.cachedRoad(roadId)
    if (cached != null) {
        applyRoadDetail(cached)
        setRoadLoadError(null)
    }
    if (!BackroadStatus.shared.isAuthenticated) {
        setShowSignInPrompt(true)
        return
    }
    try {
        val detail = BackroadStatus.shared.road(context, roadId)
        applyRoadDetail(detail)
        setRoadLoadError(null)
    } catch (t: Throwable) {
        Log.i("Composer", "API road fetch failed: ${t.message}")
        if (isAuthFailure(t) || !BackroadStatus.shared.isAuthenticated) {
            setShowSignInPrompt(true)
            return
        }
        if (!hasGeometry()) {
            setRoadLoadError(friendlyRoadLoadError(t))
        }
    }
}

private fun isAuthFailure(t: Throwable): Boolean {
    if (t is BackroadStatusError.NotAuthenticated) return true
    if (t is BackroadStatusError.OAuthFailed) return true
    if (t is BackroadStatusError.ApiError) return t.status == 401 || t.status == 403
    return false
}

private fun friendlyRoadLoadError(t: Throwable): String {
    if (t is BackroadStatusError.ApiError) {
        return when (t.status) {
            401, 403 -> "You're not authorized to view this road. Try signing in again."
            404 -> "This road couldn't be found."
            in 400..499 -> "The server couldn't return this road. Try again shortly."
            in 500..599 -> "The server ran into a problem loading this road. Try again shortly."
            else -> "Couldn't load this road. Check your connection and try again."
        }
    }
    if (t is BackroadStatusError.NotAuthenticated) return "You're not signed in."
    if (t is BackroadStatusError.SimulatedOffline) return "You're offline."
    return "Couldn't reach the server to load this road. Download the area for offline use, or try again when you're online."
}

private suspend fun uploadDraftPhoto(
    context: Context,
    photoId: String,
    data: ByteArray,
    photos: MutableList<DraftPhotoState>,
) {
    try {
        val uploaded = BackroadStatus.shared.uploadDraftPhoto(context, data)
        val idx = photos.indexOfFirst { it.id == photoId }
        if (idx >= 0) {
            photos[idx] = photos[idx].copy(reference = uploaded.reference, uploadError = null, isUploading = false)
        }
    } catch (t: Throwable) {
        Log.i("Composer", "Eager upload for photo $photoId failed: ${t.message}")
        val idx = photos.indexOfFirst { it.id == photoId }
        if (idx >= 0) {
            photos[idx] = photos[idx].copy(uploadError = t.localizedMessage, isUploading = false)
        }
    }
}

@Suppress("LongParameterList")
private suspend fun submit(
    context: Context,
    verifyMode: Boolean,
    geometry: RoadGeometry,
    verifyValues: List<Float>,
    roadConditions: List<RoadConditionPoint>,
    conditions: List<ConditionEntry>,
    startStep: Float,
    photos: List<DraftPhotoState>,
    pathId: String,
    reportDate: Date,
    description: String,
    vehicles: List<VehicleCategory>,
    discoveredFeatures: List<RoadFeatureDetail>,
    featureStatuses: Map<String, FeatureStatus>,
    featureChangeTypes: Map<String, String>,
    featureChangeComments: Map<String, String>,
    featureRemoved: Map<String, Boolean>,
    createdFeatures: List<CreatedFeature>,
    clientSubmissionId: String,
    roadName: String,
    draftSnapshot: Draft,
    setBusy: (Boolean) -> Unit,
    setError: (String?) -> Unit,
    setSubmittedId: (String) -> Unit,
    setQueuedMessage: (String) -> Unit,
    clearDraft: () -> Unit,
    onDone: () -> Unit,
) {
    setBusy(true)
    setError(null)
    try {
        val ratings = if (verifyMode)
            buildVerifiedConditionRatings(geometry, verifyValues, roadConditions)
        else
            buildConditionRatings(geometry, conditions, startStep)

        val pending = photos.mapNotNull { p ->
            val cap = p.caption.trim()
            if (cap.isEmpty()) null
            else PendingPhoto(id = p.id, data = p.data, caption = cap, reference = p.reference)
        }

        val input = CreateBulletinInput(
            pathId = pathId,
            reportDate = reportDate,
            description = description,
            photos = emptyList(),
            conditionRatings = ratings,
            vehicles = vehicles.map { VehicleInput(category = it.wire) },
            featureUpdates = buildFeatureUpdates(
                discoveredFeatures, featureStatuses, featureChangeTypes,
                featureChangeComments, featureRemoved, createdFeatures,
            ),
            clientSubmissionId = clientSubmissionId,
        )
        val snapshotBytes = runCatching {
            DraftStore.json.encodeToString(Draft.serializer(), draftSnapshot).toByteArray(Charsets.UTF_8)
        }.getOrNull()

        val queue = SubmissionQueue.shared(context)
        val outcome = queue.submit(input, photos = pending, roadName = roadName, draftSnapshot = snapshotBytes)
        when (outcome) {
            is SubmissionOutcome.Completed -> setSubmittedId(outcome.bulletin.id)
            is SubmissionOutcome.Queued -> setQueuedMessage("You're offline. We'll finish submitting as soon as you're back online.")
        }
        clearDraft()
        delay(1400)
        onDone()
    } catch (t: Throwable) {
        Log.i("Composer", "Submit failed non-retryable: ${t.message}")
        setError(friendlySubmitError(t))
    } finally {
        setBusy(false)
    }
}

private fun buildConditionRatings(
    geometry: RoadGeometry,
    conditions: List<ConditionEntry>,
    startStep: Float,
): List<ConditionRatingInput> {
    if (geometry.coordinates.size < 2 || conditions.isEmpty()) return emptyList()
    val ratings = ArrayList<ConditionRatingInput>()
    val firstIsUnknown = conditions.first().condition == Condition.UNKNOWN
    if (startStep > 0f && !firstIsUnknown) {
        coordAtStep(geometry.coordinates, startStep.toInt())?.let {
            ratings.add(ConditionRatingInput(point = it, condition = Condition.UNKNOWN.wire))
        }
    }
    for (entry in conditions) {
        coordAtStep(geometry.coordinates, entry.step.toInt())?.let {
            ratings.add(ConditionRatingInput(point = it, condition = entry.condition.wire))
        }
    }
    return ratings
}

private fun buildVerifiedConditionRatings(
    geometry: RoadGeometry,
    verifyValues: List<Float>,
    roadConditions: List<RoadConditionPoint>,
): List<ConditionRatingInput> {
    if (geometry.coordinates.size < 2 || verifyValues.size != 2 || roadConditions.isEmpty()) return emptyList()
    val fromIdx = verifyValues[0].toInt()
    val atIdx = verifyValues[1].toInt()
    if (fromIdx < 0 || atIdx < fromIdx || atIdx >= geometry.coordinates.size) return emptyList()
    val verifyFromPoint = geometry.coordinates[fromIdx]
    val verifyAtPoint = geometry.coordinates[atIdx]
    val ratings = ArrayList<ConditionRatingInput>()
    if (fromIdx > 0) {
        ratings.add(ConditionRatingInput(point = verifyFromPoint, condition = Condition.UNKNOWN.wire))
    }
    var lastConditionPointIndex = 0
    for (cp in roadConditions) {
        val cpIdx = geometry.coordinates.indexOfFirst { it == cp.point }
        if (cpIdx < 0) continue
        if (cpIdx in fromIdx..atIdx) {
            lastConditionPointIndex = cpIdx
            ratings.add(ConditionRatingInput(point = cp.point, condition = cp.condition))
        }
        if (atIdx > lastConditionPointIndex && atIdx < cpIdx) {
            ratings.add(ConditionRatingInput(point = verifyAtPoint, condition = cp.condition))
            lastConditionPointIndex = cpIdx
        }
    }
    return dedupeAdjacent(ratings)
}

private fun dedupeAdjacent(ratings: List<ConditionRatingInput>): List<ConditionRatingInput> {
    val out = ArrayList<ConditionRatingInput>()
    for (r in ratings) {
        if (out.lastOrNull()?.condition != r.condition) out.add(r)
    }
    return out
}

private fun buildFeatureUpdates(
    discoveredFeatures: List<RoadFeatureDetail>,
    featureStatuses: Map<String, FeatureStatus>,
    featureChangeTypes: Map<String, String>,
    featureChangeComments: Map<String, String>,
    featureRemoved: Map<String, Boolean>,
    createdFeatures: List<CreatedFeature>,
): List<FeatureUpdateInput> {
    val out = ArrayList<FeatureUpdateInput>()
    for (feature in discoveredFeatures) {
        val status = featureStatuses[feature.id] ?: FeatureStatus.unverified
        when (status) {
            FeatureStatus.unverified -> {}
            FeatureStatus.verified -> out.add(
                FeatureUpdateInput(
                    featureId = feature.id,
                    finalType = feature.type,
                    removed = false,
                    created = false,
                ),
            )
            FeatureStatus.changed -> {
                val finalType = if (FeatureTransition.options(feature.type) != null)
                    featureChangeTypes[feature.id] ?: feature.type
                else feature.type
                val trimmed = featureChangeComments[feature.id].orEmpty().trim()
                val removed = featureRemoved[feature.id] ?: false
                out.add(
                    FeatureUpdateInput(
                        featureId = feature.id,
                        finalType = finalType,
                        comment = trimmed.ifEmpty { null },
                        removed = removed,
                        created = false,
                    ),
                )
            }
        }
    }
    for (feature in createdFeatures) {
        out.add(
            FeatureUpdateInput(
                featureId = null,
                finalType = feature.type.wire,
                comment = feature.comment,
                removed = false,
                created = true,
                position = feature.position,
            ),
        )
    }
    return out
}

@Suppress("LongParameterList")
private fun applyDraft(
    draft: Draft,
    draftStore: DraftStore,
    assignRoadName: (String) -> Unit,
    assignCached: (RoadDetail?) -> Unit,
    applyRoadDetail: (RoadDetail) -> Unit,
    assignDraftId: (String) -> Unit,
    assignDraftCreated: (Long) -> Unit,
    assignSubmissionId: (String) -> Unit,
    assignReportDate: (Long) -> Unit,
    assignDescription: (String) -> Unit,
    assignStartStep: (Float) -> Unit,
    conditions: MutableList<ConditionEntry>,
    assignVerifyMode: (Boolean) -> Unit,
    assignVerifyValues: (List<Float>) -> Unit,
    featureStatuses: androidx.compose.runtime.snapshots.SnapshotStateMap<String, FeatureStatus>,
    featureChangeTypes: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    featureChangeComments: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    featureRemoved: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    createdFeaturesOut: MutableList<CreatedFeature>,
    vehiclesOut: MutableList<VehicleCategory>,
    assignSliderValues: (List<Float>) -> Unit,
    photosOut: MutableList<DraftPhotoState>,
) {
    assignDraftId(draft.id)
    assignDraftCreated(draft.createdAt)
    assignSubmissionId(draft.clientSubmissionId)
    draft.roadName?.let { assignRoadName(it) }
    assignReportDate(draft.reportDate)
    assignDescription(draft.description)
    assignStartStep(draft.startStep)
    conditions.clear(); conditions.addAll(draft.conditions)
    assignVerifyMode(draft.verifyMode)
    assignVerifyValues(draft.verifyValues)
    featureStatuses.clear(); featureStatuses.putAll(draft.featureStatuses)
    featureChangeTypes.clear(); featureChangeTypes.putAll(draft.featureChangeTypes)
    featureChangeComments.clear(); featureChangeComments.putAll(draft.featureChangeComments)
    featureRemoved.clear(); featureRemoved.putAll(draft.featureRemoved)
    createdFeaturesOut.clear(); createdFeaturesOut.addAll(draft.createdFeatures)
    vehiclesOut.clear(); vehiclesOut.addAll(draft.vehicles)
    assignSliderValues(listOf(draft.startStep) + draft.conditions.map { it.step })
    photosOut.clear()
    for (meta in draft.photos) {
        val data = draftStore.readPhoto(meta.id)
        if (data == null) {
            Log.i("Composer", "Draft photo ${meta.id} bytes missing on disk, dropping row")
            continue
        }
        photosOut.add(DraftPhotoState(id = meta.id, data = data, caption = meta.caption, reference = meta.reference))
    }
    assignCached(draft.cachedRoad)
    draft.cachedRoad?.let { applyRoadDetail(it) }
}

private fun handleCancelTap(
    onRevert: (() -> Unit)?,
    draftActive: Boolean,
    hasInProgressWork: () -> Boolean,
    onShowDialog: () -> Unit,
    onDiscardAndDismiss: () -> Unit,
    onPlainDismiss: () -> Unit,
) {
    if (onRevert != null) {
        onRevert()
        onDiscardAndDismiss()
        return
    }
    if (draftActive && hasInProgressWork()) {
        onShowDialog()
    } else onPlainDismiss()
}
