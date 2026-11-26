package com.backroadstatus.sdk.maplibre

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.backroadstatus.sdk.composer.ComposerMapBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * Compose wrapper that hosts a MapLibre [MapView], wraps it in a
 * [MapLibreMapProvider], and surfaces the provider to the composer via
 * [ComposerMapBinding.onReady]. Drop this into
 * `BackroadStatusReportScreen`'s `mapBuilder` when the host app uses
 * MapLibre.
 *
 * ```kotlin
 * BackroadStatusReportScreen(roadId = ..., mapStyleUrl = ...) { binding ->
 *     MapLibreComposerMap(binding = binding)
 * }
 * ```
 */
@Composable
fun MapLibreComposerMap(binding: ComposerMapBinding, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    MapLibre.getInstance(context.applicationContext)

    val mapView = remember { MapView(context) }

    MapLifecycle(mapView, lifecycleOwner)

    LaunchedEffect(mapView) {
        val map = mapView.awaitMap()
        map.setMinZoomPreference(4.0)
        map.setMaxZoomPreference(18.0)
        val provider = MapLibreMapProvider(mapView, map)
        binding.onReady(provider)
        val prepared = withContext(Dispatchers.IO) {
            StylePreparation.prepare(context, binding.styleUrl)
        }
        provider.loadStyle(prepared)
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.fillMaxSize(),
    )
}

private suspend fun MapView.awaitMap(): MapLibreMap {
    val d = CompletableDeferred<MapLibreMap>()
    getMapAsync { d.complete(it) }
    return d.await()
}

@Composable
private fun MapLifecycle(mapView: MapView, owner: LifecycleOwner) {
    val callback = remember(mapView) {
        object : DefaultLifecycleObserver {
            override fun onCreate(o: LifecycleOwner) { mapView.onCreate(null) }
            override fun onStart(o: LifecycleOwner) { mapView.onStart() }
            override fun onResume(o: LifecycleOwner) { mapView.onResume() }
            override fun onPause(o: LifecycleOwner) { mapView.onPause() }
            override fun onStop(o: LifecycleOwner) { mapView.onStop() }
            override fun onDestroy(o: LifecycleOwner) { mapView.onDestroy() }
        }
    }
    androidx.compose.runtime.DisposableEffect(owner.lifecycle, callback) {
        // Replay any missed lifecycle state so late-binding hosts don't deadlock.
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) mapView.onCreate(null)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        owner.lifecycle.addObserver(callback)
        onDispose { owner.lifecycle.removeObserver(callback) }
    }
}
