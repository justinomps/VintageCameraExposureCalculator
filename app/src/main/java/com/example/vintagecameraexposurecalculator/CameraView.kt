package com.example.vintageexposurecalculator

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * A Composable function that displays a camera preview and analyzes its luminosity.
 *
 * @param onEvCalculated A callback function that is invoked with the new EV value
 * calculated from the camera's image analysis.
 */
@Composable
fun CameraPreview(
    onEvCalculated: (Int) -> Unit,
    iso: Double
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // AndroidView is a composable that can host a traditional Android View.
    // We use it here to display the PreviewView from the CameraX library.
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProvider = cameraProviderFuture.get()

            // --- Set up the Preview Use Case ---
            // This is what shows the live image on the screen.
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // --- Set up the Image Analysis Use Case ---
            // This is what processes the image data without showing it.
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                        // When the analyzer gets a result, convert it to EV and
                        // pass it to our ViewModel via the callback.
                        val ev = luminosityToEv(luma, iso)
                        onEvCalculated(ev)
                    })
                }

            // Select the back camera.
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind everything before rebinding to avoid errors.
                cameraProvider.unbindAll()
                // Bind the use cases to the camera and the component's lifecycle.
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (exc: Exception) {
                // Handle exceptions, e.g., if the camera is unavailable.
            }

            previewView
        }
    )

    // A text overlay to show the live EV reading.
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        // We'll get the live EV from the ViewModel in the next step.
        // For now, this is just a placeholder.
    }
}