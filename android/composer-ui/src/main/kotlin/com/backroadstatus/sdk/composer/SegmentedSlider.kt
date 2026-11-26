package com.backroadstatus.sdk.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * A multi-handle slider with per-segment coloring. Mirrors the
 * BackroadStatus web condition slider: thick colored track between
 * consecutive handles, handle borders tinted by the condition of their
 * segment, optional tooltip labels above each handle.
 *
 * [segmentColors]`[i]` colors the track between handles `i` and `i+1`.
 * Count should be `max(0, values.size - 1)`; if short, segments default
 * to a neutral tint.
 */
@Composable
fun SegmentedSliderView(
    values: List<Float>,
    onValuesChange: (List<Float>) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    segmentColors: List<Color> = emptyList(),
    snap: Float = 0f,
    /**
     * Minimum distance (in the slider's value space) required between
     * adjacent handles. Prevents segments from collapsing to zero width.
     */
    minimumGap: Float = 0f,
    labels: List<String>? = null,
    trackHeight: Dp = 10.dp,
    handleDiameter: Dp = 22.dp,
    modifier: Modifier = Modifier,
) {
    val labelRowHeight = if (labels == null) 0.dp else 20.dp
    val totalHeight = handleDiameter + labelRowHeight + 6.dp

    var size by remember { mutableStateOf(IntSize.Zero) }
    val widthPx = size.width.toFloat()
    val handlePx = with(androidx.compose.ui.platform.LocalDensity.current) { handleDiameter.toPx() }
    val usableWidth = (widthPx - handlePx).coerceAtLeast(0f)

    fun valueToX(v: Float): Float {
        if (valueRange.endInclusive <= valueRange.start || usableWidth <= 0f) return 0f
        val t = (v - valueRange.start) / (valueRange.endInclusive - valueRange.start)
        return t * usableWidth
    }

    fun xToValue(x: Float): Float {
        if (usableWidth <= 0f) return valueRange.start
        val clamped = x.coerceIn(0f, usableWidth)
        val t = clamped / usableWidth
        return valueRange.start + t * (valueRange.endInclusive - valueRange.start)
    }

    fun snapped(v: Float): Float = if (snap > 0f) ((v / snap)).let { kotlin.math.round(it) * snap } else v

    fun apply(raw: Float, index: Int) {
        var v = snapped(raw).coerceIn(valueRange.start, valueRange.endInclusive)
        if (index > 0) v = maxOf(v, values[index - 1] + minimumGap)
        if (index < values.size - 1) v = minOf(v, values[index + 1] - minimumGap)
        if (values[index] != v) {
            val updated = values.toMutableList()
            updated[index] = v
            onValuesChange(updated)
        }
    }

    fun segmentColor(i: Int): Color {
        if (i < 0 || i >= segmentColors.size) return Color.LightGray
        return segmentColors[i]
    }

    fun borderColor(handle: Int): Color {
        if (segmentColors.isEmpty()) return Color.LightGray
        if (handle == 0) return segmentColors[0]
        return segmentColors[minOf(handle - 1, segmentColors.size - 1)]
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)
            .onSizeChanged { size = it },
    ) {
        // Labels row.
        if (labels != null && labels.size == values.size) {
            Box(modifier = Modifier.fillMaxWidth().height(labelRowHeight)) {
                values.forEachIndexed { i, v ->
                    val centerPx = handlePx / 2f + valueToX(v)
                    var labelWidthPx by remember { mutableStateOf(0) }
                    Text(
                        text = labels[i],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    x = (centerPx - labelWidthPx / 2f).toInt(),
                                    y = 0,
                                )
                            }
                            .onSizeChanged { labelWidthPx = it.width }
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }

        // Track.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = handleDiameter / 2)
                .offset(y = labelRowHeight + 6.dp)
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            // Colored segments.
            for (i in 0 until (values.size - 1).coerceAtLeast(0)) {
                val fromPx = valueToX(values[i])
                val toPx = valueToX(values[i + 1])
                val widthPxSeg = (toPx - fromPx).coerceAtLeast(0f)
                Box(
                    modifier = Modifier
                        .offset { androidx.compose.ui.unit.IntOffset(fromPx.toInt(), 0) }
                        .size(
                            width = with(androidx.compose.ui.platform.LocalDensity.current) {
                                widthPxSeg.toDp()
                            },
                            height = trackHeight,
                        )
                        .background(segmentColor(i)),
                )
            }
        }

        // Handles.
        val currentValues by rememberUpdatedState(values)
        values.forEachIndexed { i, _ ->
            val handleBorder = borderColor(i)
            val xPx = valueToX(values[i])
            Box(
                modifier = Modifier
                    .offset(y = labelRowHeight + 6.dp)
                    .offset {
                        androidx.compose.ui.unit.IntOffset(
                            x = xPx.toInt(),
                            y = with(this) { ((trackHeight - handleDiameter) / 2).toPx() }.toInt(),
                        )
                    }
                    .size(handleDiameter)
                    .shadow(1.5.dp, CircleShape)
                    .clip(CircleShape)
                    .background(handleBorder)
                    .pointerInput(i, usableWidth) {
                        var anchorValue = 0f
                        var accumPx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = {
                                anchorValue = currentValues[i]
                                accumPx = 0f
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            accumPx += dragAmount
                            val span = valueRange.endInclusive - valueRange.start
                            val delta = if (usableWidth > 0f) accumPx / usableWidth * span else 0f
                            apply(anchorValue + delta, i)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(handleDiameter - 6.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }
        }
    }
}

