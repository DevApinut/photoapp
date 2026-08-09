package com.fieldphoto.app

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.Camera
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.fieldphoto.app.data.LocationEntity
import com.fieldphoto.app.data.PhotoRepository
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
@Composable
fun CameraPage(repo: PhotoRepository, place: LocationEntity, done: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(100)
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build())
            .build()
    }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var zoomText by remember { mutableStateOf("1.0×") }
    var stampMode by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        boundCamera?.let { camera ->
                            val state = camera.cameraInfo.zoomState.value ?: return@let
                            val next = (state.zoomRatio * detector.scaleFactor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
                            camera.cameraControl.setZoomRatio(next)
                            zoomText = "%.1f×".format(next)
                        }
                        return true
                    }
                })
                setOnTouchListener { _, event ->
                    scaleDetector.onTouchEvent(event)
                    if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                        boundCamera?.cameraControl?.startFocusAndMetering(
                            FocusMeteringAction.Builder(meteringPointFactory.createPoint(event.x, event.y))
                                .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                        )
                        performClick()
                    }
                    true
                }
            } },
            modifier = Modifier.fillMaxSize(),
            update = { previewView ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    runCatching {
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                        provider.unbindAll()
                        boundCamera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                    }.onFailure { error = it.message }
                }, ContextCompat.getMainExecutor(context))
            }
        )
        FloatingActionButton(
            onClick = {
                saving = true
                scope.launch {
                    runCatching {
                        val location = repo.dao.location(place.id)
                        val job = repo.dao.job(location.jobId)
                        val client = repo.dao.client(job.clientId)
                        val capturedAt = OffsetDateTime.now()
                        val (uri, relative, filename) = repo.media.newDestination(client.name, job.name, location.name, capturedAt)
                        val descriptor = context.contentResolver.openFileDescriptor(uri, "w")
                            ?: kotlin.error("Cannot open destination photo")
                        val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
                        val options = ImageCapture.OutputFileOptions.Builder(outputStream).build()
                        imageCapture.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                outputStream.close()
                                fun persist(latitude: Double?, longitude: Double?, accuracy: Float?) {
                                    scope.launch {
                                        runCatching { repo.registerCaptured(place.id, uri, relative, filename, capturedAt, latitude, longitude, accuracy, stampMode) }
                                            .onSuccess { done() }
                                            .onFailure { repo.media.cancel(uri); error = it.message; saving = false }
                                    }
                                }
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                    LocationServices.getFusedLocationProviderClient(context).lastLocation
                                        .addOnCompleteListener { task -> task.result?.let { persist(it.latitude, it.longitude, it.accuracy) } ?: persist(null, null, null) }
                                } else persist(null, null, null)
                            }
                            override fun onError(exception: ImageCaptureException) {
                                repo.media.cancel(uri); outputStream.close(); error = exception.message; saving = false
                            }
                        })
                    }.onFailure { error = it.message; saving = false }
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(28.dp),
            containerColor = MaterialTheme.colorScheme.primary,
        ) { if (saving) CircularProgressIndicator(Modifier.size(28.dp)) else Icon(Icons.Default.Camera, "ถ่ายรูป") }
        Column(Modifier.align(Alignment.TopCenter).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)) {
                Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !stampMode, onClick = { stampMode = false }, label = { Text("ต้นฉบับ") })
                    FilterChip(selected = stampMode, onClick = { stampMode = true }, label = { Text("Time stamp + GPS") })
                }
            }
            Spacer(Modifier.height(6.dp))
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)) {
                Text("$zoomText  •  จีบนิ้วเพื่อซูม  •  แตะเพื่อโฟกัส", Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        error?.let { Snackbar(Modifier.align(Alignment.TopCenter).padding(12.dp)) { Text(it) } }
    }
}
