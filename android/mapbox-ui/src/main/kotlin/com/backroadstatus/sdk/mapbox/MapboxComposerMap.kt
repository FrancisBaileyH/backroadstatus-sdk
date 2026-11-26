package com.backroadstatus.sdk.mapbox

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.backroadstatus.sdk.composer.ComposerMapBinding
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Compose wrapper that hosts a Mapbox [MapView], wraps it in a
 * [MapboxMapProvider], and surfaces the provider to the composer via
 * [ComposerMapBinding.onReady].
 *
 * Mapbox requires an access token. Set it before this composable is
 * invoked — either via your app's string resources
 * (`<string name="mapbox_access_token">pk....</string>`) or by calling
 * `MapboxOptions.accessToken = "..."` at app startup.
 */
@Composable
fun MapboxComposerMap(binding: ComposerMapBinding, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context, MapInitOptions(context)) }

    MapLifecycle(mapView, lifecycleOwner)

    LaunchedEffect(mapView) {
        val mapboxMap = mapView.mapboxMap
        val provider = MapboxMapProvider(mapView, mapboxMap)
        binding.onReady(provider)
        val prepared = withContext(Dispatchers.IO) {
            MapboxStylePreparation.prepare(context, binding.styleUrl)
        }
        provider.loadStyle(prepared)
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
private fun MapLifecycle(mapView: MapView, owner: LifecycleOwner) {
    val callback = remember(mapView) {
        object : DefaultLifecycleObserver {
            override fun onStart(o: LifecycleOwner) { mapView.onStart() }
            override fun onStop(o: LifecycleOwner) { mapView.onStop() }
            override fun onDestroy(o: LifecycleOwner) { mapView.onDestroy() }
        }
    }
    DisposableEffect(owner.lifecycle, callback) {
        owner.lifecycle.addObserver(callback)
        onDispose { owner.lifecycle.removeObserver(callback) }
    }
}
