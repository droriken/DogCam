package com.kendrori.dogcam

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.hardware.camera2.CameraCharacteristics
import androidx.exifinterface.media.ExifInterface
import android.media.AudioAttributes
import android.media.MediaActionSound
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.platform.ComposeView
import java.util.concurrent.TimeUnit
import androidx.core.content.ContextCompat
import com.kendrori.dogcam.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.OrientationEventListener
import android.view.Surface
import android.view.animation.RotateAnimation
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private lateinit var cameraExecutor: ExecutorService
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var camera: Camera? = null
    private var backCameraZoomRatio = 1.0f
    private var frontCameraZoomRatio = 1.0f
    
    private lateinit var objectDetector: ObjectDetector
    private lateinit var imageLabeler: ImageLabeler
    
    private var isDogInFrame = false
    @Volatile
    private var isDogMode = true
    private var lastPhotoUri: Uri? = null

    private lateinit var soundPool: SoundPool
    private var shutterSoundId: Int = 0
    private var meowSoundId: Int = 0

    // Smoothing logic to prevent flickering
    private val detectionHistory = mutableListOf<Boolean>()
    private val HISTORY_SIZE = 8 
    private val DETECTION_THRESHOLD = 5 
    private val TAG = "DogCam"

    private var currentRotation = 0f
    private lateinit var orientationEventListener: OrientationEventListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Show startup banner
        showStartupBanner()

        val objOptions = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        objectDetector = ObjectDetection.getClient(objOptions)

        // Pre-configure labelers for both species with a lower threshold to capture secondary hits
        val labelOptions = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.3f)
            .build()
        imageLabeler = ImageLabeling.getClient(labelOptions)

        binding.shutterButton.setOnClickListener { 
            if (isDogInFrame) {
                takePhoto()
            } else {
                flashNoDogWarning()
            }
        }
        binding.flipCameraButton.setOnClickListener { flipCamera() }
        setupThumbnailActions()

        var isOverlayVisible = false
        binding.overlayView.setOverlayVisible(isOverlayVisible)
        binding.toggleOverlayButton.setImageResource(R.drawable.ic_visibility_off)
        
        binding.toggleOverlayButton.setOnClickListener {
            isOverlayVisible = !isOverlayVisible
            binding.overlayView.setOverlayVisible(isOverlayVisible)
            binding.toggleOverlayButton.setImageResource(
                if (isOverlayVisible) R.drawable.ic_visibility else R.drawable.ic_visibility_off
            )
        }

        binding.aboutButton.setOnClickListener {
            showAboutDialog()
        }

        binding.petTypeButton.setOnClickListener {
            this.isDogMode = !this.isDogMode
            binding.petTypeButton.setImageResource(
                if (this.isDogMode) R.drawable.ic_dog else R.drawable.ic_cat
            )
            // Clear history when switching modes to avoid false triggers
            detectionHistory.clear()
            updateUI(false, animate = true)
        }

        setupZoomSlider()

        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val zoomState = camera?.cameraInfo?.zoomState?.value ?: return false
                val currentZoomRatio = zoomState.zoomRatio
                val delta = detector.scaleFactor
                
                val maxAllowed = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    1.0f.coerceAtLeast(zoomState.minZoomRatio)
                } else {
                    zoomState.maxZoomRatio
                }
                
                val targetZoom = (currentZoomRatio * delta).coerceIn(zoomState.minZoomRatio, maxAllowed)
                
                camera?.cameraControl?.setZoomRatio(targetZoom)
                
                if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    backCameraZoomRatio = targetZoom
                } else {
                    frontCameraZoomRatio = targetZoom
                }
                return true
            }
        })

        binding.viewFinder.setOnTouchListener { view, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                // Haptic feedback
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                    vibratorManager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                }
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))

                // Visual focus ring animation
                binding.focusRing.apply {
                    x = event.x - width / 2
                    y = event.y - height / 2
                    visibility = View.VISIBLE
                    alpha = 1f
                    scaleX = 1.2f
                    scaleY = 1.2f
                    animate()
                        .alpha(0f)
                        .scaleX(0.8f)
                        .scaleY(0.8f)
                        .setDuration(500)
                        .withEndAction { visibility = View.INVISIBLE }
                        .start()
                }

                // Focus logic
                val factory = binding.viewFinder.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                camera?.cameraControl?.startFocusAndMetering(action)
                
                // Manual detection trigger
                isManualTriggerRequested = true
                
                view.performClick()
            }
            true
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupOrientationListener()

        checkPermissions()
        updateUI(false, animate = false)

        // Preload shutter sound
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM) // Using ALARM can sometimes bypass silent/vibrate depending on OS
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(audioAttributes)
            .build()
        shutterSoundId = soundPool.load(this, R.raw.camera_shutter, 1)
        meowSoundId = soundPool.load(this, R.raw.meow_funny, 1)
    }

    private fun setupZoomSlider() {
        binding.zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val zoomState = camera?.cameraInfo?.zoomState?.value ?: return
                    val minZoom = zoomState.minZoomRatio
                    val maxZoom = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        1.0f.coerceAtLeast(minZoom)
                    } else {
                        zoomState.maxZoomRatio
                    }
                    
                    val targetZoom = if (progress <= 50) {
                        minZoom + (progress / 50f) * (1.0f - minZoom).coerceAtLeast(0f)
                    } else {
                        1.0f + ((progress - 50) / 50f) * (maxZoom - 1.0f).coerceAtLeast(0f)
                    }
                    camera?.cameraControl?.setZoomRatio(targetZoom)

                    if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        backCameraZoomRatio = targetZoom
                    } else {
                        frontCameraZoomRatio = targetZoom
                    }

                    // Vibrate slightly at 1.0x notch (progress 50)
                    if (progress == 50) {
                        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                            vibratorManager.defaultVibrator
                        } else {
                            @Suppress("DEPRECATION")
                            getSystemService(VIBRATOR_SERVICE) as Vibrator
                        }
                        vibrator.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun showAboutDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_about, null)
        val composeView = dialogView.findViewById<ComposeView>(R.id.aboutIllustrationCompose)
        
        composeView.setContent {
            DogCamIllustration()
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("🐾(P)AWESOME") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showStartupBanner() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_welcome, null)
        val composeView = dialogView.findViewById<ComposeView>(R.id.dogIllustrationCompose)
        composeView.setContent {
            DogCamIllustration()
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(R.string.got_it) { dialog: DialogInterface, _: Int -> 
                dialog.dismiss() 
            }
            .setCancelable(false)
            .show()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            if (results.all { it.value }) {
                startCamera()
                loadLastPhoto()
            } else {
                Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
            }
        }
        
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
            loadLastPhoto()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private var isManualTriggerRequested = false

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            // Set up ViewPort to synchronize all use cases' crop regions
            val viewPort = binding.viewFinder.viewPort

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(binding.viewFinder.display.rotation)
                .build()

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetRotation(binding.viewFinder.display.rotation)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (isManualTriggerRequested) {
                            // When manual trigger is requested, we can optionally bypass some smoothing 
                            // or use a more intensive detection path if needed.
                            // For now, we'll just reset the history to force an immediate re-evaluation.
                            detectionHistory.clear()
                            isManualTriggerRequested = false
                        }
                        processImageProxy(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            try {
                cameraProvider.unbindAll()
                
                val useCaseGroup = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(imageCapture!!)
                    .addUseCase(imageAnalyzer!!)
                    .apply {
                        if (viewPort != null) setViewPort(viewPort)
                    }
                    .build()

                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, useCaseGroup
                )

                // Initialize limits and restore saved zoom
                camera?.cameraInfo?.let { info ->
                    val state = info.zoomState.value
                    if (state != null) {
                        val maxAllowed = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                            1.0f.coerceAtLeast(state.minZoomRatio)
                        } else {
                            state.maxZoomRatio
                        }
                        val savedZoom = if (lensFacing == CameraSelector.LENS_FACING_BACK) backCameraZoomRatio else frontCameraZoomRatio
                        camera?.cameraControl?.setZoomRatio(savedZoom.coerceIn(state.minZoomRatio, maxAllowed))
                    }
                }

                // Keep slider in sync with camera zoom state (e.g. if user pinches)
                camera?.cameraInfo?.zoomState?.observe(this) { state ->
                    val minZoom = state.minZoomRatio
                    val maxZoom = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        1.0f.coerceAtLeast(minZoom)
                    } else {
                        state.maxZoomRatio
                    }
                    
                    val progress = if (state.zoomRatio <= 1.0f) {
                        if (1.0f > minZoom) {
                            ((state.zoomRatio - minZoom) / (1.0f - minZoom) * 50).toInt()
                        } else {
                            50
                        }
                    } else {
                        if (maxZoom > 1.0f) {
                            (50 + (state.zoomRatio - 1.0f) / (maxZoom - 1.0f) * 50).toInt()
                        } else {
                            50
                        }
                    }
                    if (binding.zoomSlider.progress != progress) {
                        binding.zoomSlider.progress = progress.coerceIn(0, 100)
                    }
                    
                    binding.zoomSlider.isEnabled = (maxZoom > minZoom)
                }
                
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)
            
            objectDetector.process(image)
                .addOnSuccessListener { objects ->
                    imageLabeler.process(image)
                        .addOnSuccessListener { labels ->
                            analyzeDetections(objects, labels, imageProxy.width, imageProxy.height, rotationDegrees, imageProxy.cropRect)
                        }
                        .addOnFailureListener {
                            analyzeDetections(objects, emptyList(), imageProxy.width, imageProxy.height, rotationDegrees, imageProxy.cropRect)
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                }
                .addOnFailureListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun analyzeDetections(
        objects: List<DetectedObject>, 
        labels: List<ImageLabel>,
        width: Int, 
        height: Int, 
        rotation: Int,
        cropRect: android.graphics.Rect
    ) {
        val dogKeywords = listOf("dog", "canine", "puppy", "hound", "terrier", "retriever")
        val catKeywords = listOf("cat", "feline", "kitten", "tabby")
        
        val currentModeIsDog = this.isDogMode

        // Find best label for each species
        val bestDogLabel = labels.filter { label ->
            dogKeywords.any { kw -> label.text.contains(kw, ignoreCase = true) }
        }.maxByOrNull { it.confidence }

        val bestCatLabel = labels.filter { label ->
            catKeywords.any { kw -> label.text.contains(kw, ignoreCase = true) }
        }.maxByOrNull { it.confidence }

            // Core logic: Is the target species detected and dominant?
            val petFound = if (currentModeIsDog) {
                bestDogLabel != null && bestDogLabel.confidence >= 0.7f &&
                        (bestCatLabel == null || bestDogLabel.confidence > bestCatLabel.confidence)
            } else {
                bestCatLabel != null && bestCatLabel.confidence >= 0.7f &&
                        (bestDogLabel == null || bestCatLabel.confidence > bestDogLabel.confidence)
            }

            // Object detection objects are updated based on general image labeling results
            val finalTargetKeywords = if (currentModeIsDog) dogKeywords else catKeywords

            val analyzedObjects = objects.map { obj ->
                // Check if this specific object box contains a target species
                val hasTargetLabel = obj.labels.any { objLabel ->
                    finalTargetKeywords.any { kw -> objLabel.text.contains(kw, ignoreCase = true) } ||
                            objLabel.text.contains("Pet", ignoreCase = true) ||
                            objLabel.text.contains("Animal", ignoreCase = true)
                }

                // Create a synthetic property or handle via a wrapper if needed, 
                // but for now, we'll let OverlayView decide based on the passed state.
                obj
            }

            detectionHistory.add(petFound)
        if (detectionHistory.size > HISTORY_SIZE) {
            detectionHistory.removeAt(0)
        }
        
        val smoothedPetDetected = detectionHistory.count { it } >= DETECTION_THRESHOLD

        runOnUiThread {
            if (smoothedPetDetected != isDogInFrame) {
                isDogInFrame = smoothedPetDetected
                updateUI(isDogInFrame, animate = true)
            }

            val petType = if (currentModeIsDog) "Dog" else "Cat"
            val displayLabel = if (isDogInFrame) {
                petType
            } else {
                val rawLabel = labels.firstOrNull()?.text ?: ""
                when {
                    rawLabel.isEmpty() -> ""
                    rawLabel.equals("pet", ignoreCase = true) || 
                    rawLabel.equals("animal", ignoreCase = true) -> petType
                    else -> rawLabel.lowercase().replaceFirstChar { 
                        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() 
                    }
                }
            }

            // Only show bounding boxes for objects with labels that contain any detection info
            val filteredObjects = objects.filter { obj ->
                obj.labels.isNotEmpty() || obj.trackingId != null
            }

            val targetKeywords = if (currentModeIsDog) dogKeywords else catKeywords

            binding.overlayView.setResults(
                filteredObjects,
                width,
                height,
                rotation,
                cropRect,
                displayLabel,
                lensFacing == CameraSelector.LENS_FACING_FRONT,
                currentRotation,
                targetKeywords,
                isDogInFrame // Pass the confirmed detection state
            )
        }
    }

    private fun updateUI(dogDetected: Boolean, animate: Boolean) {
        val targetAlpha = if (dogDetected) 1.0f else 0.4f
        val targetScale = if (dogDetected) 1.1f else 0.9f
        
        val petName = if (isDogMode) "DOG" else "CAT"
        binding.noDogLabel.text = "NO $petName DETECTED"
        binding.dogDetectedLabel.text = "✅ $petName DETECTED"
        binding.flashNoDogLabel.text = "NO $petName\n\nNO PHOTO"
        
        binding.noDogLabel.visibility = if (dogDetected) View.GONE else View.VISIBLE
        binding.dogDetectedLabel.visibility = if (dogDetected) View.VISIBLE else View.GONE
        
        // Shutter button color logic via isSelected
        binding.shutterButton.isSelected = dogDetected
        // Shutter button is always enabled so we can show the warning flash
        binding.shutterButton.isEnabled = true 

        if (animate) {
            binding.shutterButton.animate()
                .alpha(targetAlpha)
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(300)
                .start()
        } else {
            binding.shutterButton.alpha = targetAlpha
            binding.shutterButton.scaleX = targetScale
            binding.shutterButton.scaleY = targetScale
        }
    }

    private fun flashNoDogWarning() {
        // Vibrate to provide tactile feedback
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))

        binding.flashNoDogLabel.visibility = View.VISIBLE
        binding.flashNoDogLabel.alpha = 1f
        binding.flashNoDogLabel.animate()
            .alpha(0f)
            .setStartDelay(750)
            .setDuration(500)
            .withEndAction {
                binding.flashNoDogLabel.visibility = View.GONE
            }
            .start()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP,
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (isDogInFrame) {
                    takePhoto()
                } else {
                    flashNoDogWarning()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun takePhoto() {
        if (!isDogInFrame) return
        
        val imageCapture = imageCapture ?: return

        // Dynamic shutter sound based on mode
        val soundId = if (isDogMode) shutterSoundId else meowSoundId
        if (soundId != 0) {
            // Use volume 1.0f and a high priority to ensure it plays even in vibrate mode
            // Note: SoundPool usually respects system settings, but we'll try to use a stream that might bypass them
            soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
        }

        // Flash effect
        binding.shutterFlash.visibility = View.VISIBLE
        binding.shutterFlash.alpha = 1f
        binding.shutterFlash.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                binding.shutterFlash.visibility = View.GONE
            }
            .start()

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DogCam")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri
                    if (savedUri != null) {
                        lastPhotoUri = savedUri
                        val message = if (isDogMode) getString(R.string.dog_captured) else getString(R.string.cat_captured)
                        Toast.makeText(baseContext, message, Toast.LENGTH_SHORT).show()
                        updateThumbnail(savedUri)
                    }
                }
            }
        )
    }

    private fun updateThumbnail(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            var bitmap = BitmapFactory.decodeStream(inputStream) ?: return
            
            // 1. Fix orientation
            bitmap = fixBitmapOrientation(uri, bitmap)
            
            // 2. Apply Watermark
            val watermarked = addWatermark(bitmap)
            
            // 3. Save watermarked version back to storage
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                watermarked.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            }

            // 4. Update the UI thumbnail
            runOnUiThread {
                binding.photoThumbnail.setImageBitmap(watermarked)
                binding.photoThumbnail.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Thumbnail/Watermark update failed", e)
        }
    }

    private fun addWatermark(source: Bitmap): Bitmap {
        val result = source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        
        // Settings for scaling and margins
        val iconSize = (source.width * 0.12f).toInt() // Slightly smaller to leave room for text
        val margin = (source.width * 0.03f).toInt()
        
        // 1. Draw "DogCam" text
        val textPaint = Paint().apply {
            color = Color.WHITE
            alpha = 255 // Completely opaque
            textSize = source.width * 0.04f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            // Stronger, professional drop shadow
            setShadowLayer(6f, 3f, 3f, Color.BLACK)
        }
        
        val text = "DogCam"
        val textX = source.width - margin - (iconSize / 2f)
        val textY = source.height - margin.toFloat()
        canvas.drawText(text, textX, textY, textPaint)

        // 2. Draw app icon above the text
        val iconDrawable = ContextCompat.getDrawable(this, R.mipmap.ic_dogcam_icon)
        if (iconDrawable != null) {
            val textHeight = textPaint.fontMetrics.bottom - textPaint.fontMetrics.top
            val iconBottom = (textY - textHeight).toInt()
            val iconTop = iconBottom - iconSize
            val iconLeft = (textX - (iconSize / 2f)).toInt()
            val iconRight = (textX + (iconSize / 2f)).toInt()
            
            iconDrawable.setBounds(iconLeft, iconTop, iconRight, iconBottom)
            iconDrawable.alpha = 255 // Completely opaque
            iconDrawable.draw(canvas)
        }
        
        return result
    }

    private fun setupThumbnailActions() {
        binding.photoThumbnail.setOnClickListener {
            lastPhotoUri?.let { uri ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/jpeg")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.no_gallery, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fixBitmapOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        val inputStream = contentResolver.openInputStream(uri) ?: return bitmap
        val exifInterface = ExifInterface(inputStream)
        val orientation = exifInterface.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun loadLastPhoto() {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATA
        )
        // More robust selection for the DogCam folder
        val selection = "${MediaStore.Images.Media.DATA} LIKE ?"
        val selectionArgs = arrayOf("%/DogCam/%")
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val id = cursor.getLong(idColumn)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    lastPhotoUri = contentUri
                    updateThumbnail(contentUri)
                } else {
                    // If no DogCam photo, try ANY recent photo just so it's not blank
                    loadAnyRecentPhoto()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load last photo", e)
        }
    }

    private fun loadAnyRecentPhoto() {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                lastPhotoUri = contentUri
                updateThumbnail(contentUri)
            }
        }
    }

    private fun openGallery() {
        val uriToOpen = lastPhotoUri ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val mimeType = contentResolver.getType(uriToOpen) ?: "image/*"

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriToOpen, mimeType)
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback to generic image intent if the specific one fails
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uriToOpen, "image/*")
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(fallbackIntent)
            } catch (ex: Exception) {
                Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun flipCamera() {
        binding.viewFinder.animate()
            .rotationYBy(180f)
            .setDuration(300)
            .withEndAction {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
                detectionHistory.clear()
                startCamera()
                binding.viewFinder.rotationY = 0f
            }
            .start()
    }

    private fun setupOrientationListener() {
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val rotation = when (orientation) {
                    in 45 until 135 -> 270f
                    in 135 until 225 -> 180f
                    in 225 until 315 -> 90f
                    else -> 0f
                }

                if (rotation != currentRotation) {
                    rotateUIElements(rotation)
                    currentRotation = rotation
                    
                    // Update CameraX target rotation for metadata
                    val targetRotation = when (rotation) {
                        90f -> Surface.ROTATION_90
                        180f -> Surface.ROTATION_180
                        270f -> Surface.ROTATION_270
                        else -> Surface.ROTATION_0
                    }
                    imageCapture?.targetRotation = targetRotation
                    imageAnalyzer?.targetRotation = targetRotation
                }
            }
        }
    }

    private fun rotateUIElements(rotation: Float) {
        val viewsToRotate = listOf(
            binding.petTypeButton,
            binding.flipCameraButton,
            binding.toggleOverlayButton,
            binding.aboutButton,
            binding.photoThumbnail,
            binding.flashNoDogLabel
        )

        viewsToRotate.forEach { view ->
            view.animate()
                .rotation(rotation)
                .setDuration(300)
                .start()
        }
    }

    override fun onStart() {
        super.onStart()
        orientationEventListener.enable()
    }

    override fun onStop() {
        super.onStop()
        orientationEventListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        objectDetector.close()
        imageLabeler.close()
        soundPool.release()
    }

    companion object {
        private const val TAG = "DogCam"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}
