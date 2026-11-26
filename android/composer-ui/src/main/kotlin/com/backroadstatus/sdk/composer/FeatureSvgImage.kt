package com.backroadstatus.sdk.composer

import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext

/**
 * Renders a bundled feature-type SVG inline inside Compose. Used by the
 * Add Feature picker and the composer's verification checklist so the
 * icons in-app match what gets rasterized onto the map.
 */
@Composable
fun FeatureSvgImage(typeName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var svg by remember(typeName) { mutableStateOf(MarkerRasterizer.svg(context, typeName)) }
    LaunchedEffect(typeName) {
        if (svg == null) svg = MarkerRasterizer.svg(context, typeName)
    }
    val current = svg
    if (current == null) {
        Icon(imageVector = Icons.Filled.Place, contentDescription = typeName, modifier = modifier)
        return
    }
    Canvas(modifier = modifier) {
        drawIntoCanvas { canvas ->
            current.documentWidth = size.width
            current.documentHeight = size.height
            current.renderToCanvas(canvas.nativeCanvas)
        }
    }
}
