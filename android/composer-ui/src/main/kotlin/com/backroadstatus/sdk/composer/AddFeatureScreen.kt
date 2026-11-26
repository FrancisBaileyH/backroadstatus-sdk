package com.backroadstatus.sdk.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.backroadstatus.sdk.core.RoadConditionPoint

private const val CURRENT_MARKER_ID = "current"

/**
 * Picker sheet for staging a new road feature. Generic over the host-
 * supplied map view — the same `mapBuilder` the host passes to
 * `BackroadStatusReportScreen` flows through here so the picker map
 * renders via the host's chosen map SDK adapter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFeatureScreen(
    pathId: String,
    mapStyleUrl: String,
    roadCoordinates: List<List<Double>>,
    /** Per-condition-segment anchors for the road. */
    roadConditions: List<RoadConditionPoint>,
    /** Features the user has already added in this composer session. */
    existingFeatures: List<PlacedFeatureMarker>,
    onSave: (CreatedFeature) -> Unit,
    onCancel: () -> Unit,
    mapBuilder: @Composable (ComposerMapBinding) -> Unit,
) {
    val context = LocalContext.current
    val mapDriver = remember { ComposerMapDriver(context) }

    var selectedType by remember { mutableStateOf<CreatableFeatureType?>(null) }
    var markerPosition by remember { mutableStateOf<List<Double>?>(null) }
    var comment by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val allPickerMarkers: List<PlacedFeatureMarker> = remember(selectedType, markerPosition, existingFeatures) {
        val pos = markerPosition
        val type = selectedType
        if (pos != null && type != null) {
            existingFeatures + PlacedFeatureMarker(CURRENT_MARKER_ID, type.wire, pos)
        } else existingFeatures
    }

    // Keep the picker map in sync with road + markers.
    LaunchedEffect(roadCoordinates, roadConditions) {
        mapDriver.updateCoordinates(roadCoordinates, roadConditions)
    }
    LaunchedEffect(allPickerMarkers) {
        mapDriver.updateCreatedFeatureMarkers(allPickerMarkers)
    }

    val canSave = selectedType != null && markerPosition != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Feature") },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                },
                actions = {
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            val type = selectedType ?: return@TextButton
                            val position = markerPosition ?: return@TextButton
                            val trimmed = comment.trim()
                            onSave(
                                CreatedFeature(
                                    type = type,
                                    position = position,
                                    comment = trimmed.ifEmpty { null },
                                ),
                            )
                        },
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // Feature type picker row.
            SectionHeader("Feature Type")
            TypePickerRow(
                selected = selectedType,
                onSelect = { selectedType = it; errorMessage = null },
            )
            SectionFooter(text = selectedType?.displayName.orEmpty())

            // Picker map.
            SectionHeader("Location")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
            ) {
                mapBuilder(
                    ComposerMapBinding(
                        styleUrl = mapStyleUrl,
                        onReady = { provider ->
                            mapDriver.attach(provider, pathId = pathId)
                            mapDriver.updateCoordinates(roadCoordinates, roadConditions)
                            mapDriver.updateCreatedFeatureMarkers(allPickerMarkers)
                            provider.onTap = { coord ->
                                if (selectedType != null) {
                                    val snapped = nearestRoadCoord(roadCoordinates, coord.longitude, coord.latitude)
                                    if (snapped != null) markerPosition = snapped
                                }
                            }
                        },
                    ),
                )
            }
            SectionFooter(
                text = if (selectedType == null)
                    "Pick a feature type above, then tap the map to drop a marker."
                else
                    "Tap the map to drop a marker. It snaps to the nearest point on the road.",
            )

            // Comment.
            SectionHeader("Comment (Optional)")
            TextField(
                value = comment,
                onValueChange = { new -> comment = if (new.length > 200) new.take(200) else new },
                placeholder = { Text("Describe the feature…") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = borderlessTextFieldColors(),
            )
            SectionFooter("Maximum 200 characters.")

            errorMessage?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionFooter(text: String) {
    if (text.isNotEmpty()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 12.dp, end = 16.dp),
        )
    }
}

@Composable
private fun TypePickerRow(
    selected: CreatableFeatureType?,
    onSelect: (CreatableFeatureType) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (type in CreatableFeatureType.entries) {
            val isSelected = selected == type
            val bg = if (isSelected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else MaterialTheme.colorScheme.surfaceVariant
            val fg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            Column(
                modifier = Modifier
                    .width(90.dp)
                    .background(bg, RoundedCornerShape(10.dp))
                    .border(
                        width = 1.5.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(type) }
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FeatureSvgImage(typeName = type.wire, modifier = Modifier.size(28.dp))
                Text(
                    text = type.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = fg,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Snaps a tap point to the nearest road vertex so the stored position
 * matches a coord in `coordinates` — same guarantee the condition
 * slider makes.
 */
private fun nearestRoadCoord(coordinates: List<List<Double>>, lon: Double, lat: Double): List<Double>? {
    if (coordinates.isEmpty()) return null
    var bestIdx = 0
    var bestDist = Double.MAX_VALUE
    for (i in coordinates.indices) {
        val c = coordinates[i]
        val dLon = c[0] - lon
        val dLat = c[1] - lat
        val d = dLon * dLon + dLat * dLat
        if (d < bestDist) {
            bestDist = d
            bestIdx = i
        }
    }
    return coordinates[bestIdx]
}
