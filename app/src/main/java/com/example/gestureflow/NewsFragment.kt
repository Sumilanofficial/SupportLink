package com.example.gestureflow

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class NewsFragment : Fragment() {

    // CameraX preview widget
    private lateinit var previewView: PreviewView

    // TextView to show the currently selected action
    private lateinit var selectedActionText: TextView

    // Layout container where dynamic sections will be displayed
    private lateinit var sectionContainer: ConstraintLayout

    // Executor for running camera tasks on a background thread
    private lateinit var cameraExecutor: ExecutorService

    // Available actions (choices for the user)
    private var actions = listOf("Google News", "BBC")

    // Stack to keep track of previous states (used for navigation by blinks)
    private var previousStates: MutableList<List<String>> = mutableListOf()

    // Blink detection variables
    private var lastBlinkTime: Long = 0
    private var leftBlinkCount = 0
    private var rightBlinkCount = 0
    private var eyesClosedTime: Long = 0 // Track how long both eyes remain closed

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_news, container, false)

        // Initialize views
        previewView = view.findViewById(R.id.camera_preview)
        selectedActionText = view.findViewById(R.id.selectedWord)
        sectionContainer = view.findViewById(R.id.sectionContainer)

        // Start background thread for camera
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Start CameraX
        startCamera()

        // Display the initial actions
        displaySections(actions)

        return view
    }

    /**
     * Splits actions into two sections (left + right) and displays them as buttons
     */
    private fun displaySections(actionList: List<String>) {
        sectionContainer.removeAllViews()

        // If only one action left → show it as selected
        if (actionList.size == 1) {
            selectedActionText.text = "Selected Action: ${actionList.first()}"
            return
        }

        // Save current state
        previousStates.add(actionList)

        // Divide actions into left and right halves
        val leftActions = actionList.subList(0, actionList.size / 2)
        val rightActions = actionList.subList(actionList.size / 2, actionList.size)

        // Create horizontal layout for both sections
        val sectionsLayout = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
        }

        // Add left and right sections
        sectionsLayout.addView(createSection(leftActions))
        sectionsLayout.addView(createSection(rightActions))

        // Add layout to container
        sectionContainer.addView(sectionsLayout)
    }

    /**
     * Creates a vertical section of buttons for the given actions
     */
    private fun createSection(actions: List<String>): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
            setPadding(16, 16, 16, 16)

            actions.forEach { action ->
                val button = Button(context).apply {
                    text = action
                    textSize = 12f // Smaller text
                    setTextColor(android.graphics.Color.BLACK)
                    background = ContextCompat.getDrawable(context, R.drawable.rounded_button)

                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        150 // Button height
                    ).apply {
                        setMargins(8, 8, 8, 8)
                    }

                    setPadding(10, 10, 10, 10)
                    maxLines = 2
                    isAllCaps = false

                    // Click fallback (manual selection if needed)
                    setOnClickListener {
                        displaySections(actions)
                    }
                }
                addView(button)
            }
        }
    }

    /**
     * Start the CameraX preview + face detection
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Camera preview
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Analyzer for face detection
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // Unbind previous use cases
                cameraProvider.unbindAll()
                // Bind preview + analysis
                cameraProvider.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, imageAnalysis)
                previewView.visibility = View.INVISIBLE // Hide camera preview from UI
            } catch (exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /**
     * Convert camera frame to ML Kit InputImage and run face detection
     */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            // Configure face detector
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()

            val detector = com.google.mlkit.vision.face.FaceDetection.getClient(options)

            // Run detection
            detector.process(image)
                .addOnSuccessListener { faces ->
                    processFaces(faces)
                    imageProxy.close()
                }
                .addOnFailureListener { e ->
                    Log.e("FaceDetection", "Face detection failed", e)
                    imageProxy.close()
                }
        }
    }

    /**
     * Handles detected faces and blink logic
     */
    private fun processFaces(faces: List<Face>) {
        if (faces.isNotEmpty()) {
            val face = faces[0]
            val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1.0f
            val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1.0f
            val currentTime = System.currentTimeMillis()

            // Detect if both eyes are closed → go back to home
            when {
                leftEyeOpenProb < 0.3 && rightEyeOpenProb < 0.3 -> {
                    if (currentTime - eyesClosedTime > 1500) {
                        navigateToHomeFragment()
                    }
                }
                else -> {
                    eyesClosedTime = currentTime // Reset when eyes are open
                }
            }

            // Left eye blink → selects right half
            when {
                leftEyeOpenProb < 0.3 && rightEyeOpenProb > 0.7 -> {
                    if (currentTime - lastBlinkTime < 700) {
                        leftBlinkCount++
                        if (leftBlinkCount == 2) {
                            selectRightAction()
                            leftBlinkCount = 0
                        }
                    } else {
                        leftBlinkCount = 1
                    }
                    lastBlinkTime = currentTime
                }

                // Right eye blink → selects left half
                rightEyeOpenProb < 0.3 && leftEyeOpenProb > 0.7 -> {
                    if (currentTime - lastBlinkTime < 700) {
                        rightBlinkCount++
                        if (rightBlinkCount == 2) {
                            selectLeftAction()
                            rightBlinkCount = 0
                        }
                    } else {
                        rightBlinkCount = 1
                    }
                    lastBlinkTime = currentTime
                }
            }
        }
    }

    /**
     * Select left section (right eye double blink)
     */
    private fun selectLeftAction() {
        previousStates.lastOrNull()?.let { currentList ->
            if (currentList.size > 1) {
                val leftHalf = currentList.subList(0, currentList.size / 2)
                previousStates.add(leftHalf)
                displaySections(leftHalf)

                // If only one action left, navigate
                if (leftHalf.size == 1) {
                    navigateToInstagramAction(leftHalf.first())
                }
            }
        }
    }

    /**
     * Select right section (left eye double blink)
     */
    private fun selectRightAction() {
        previousStates.lastOrNull()?.let { currentList ->
            if (currentList.size > 1) {
                val rightHalf = currentList.subList(currentList.size / 2, currentList.size)
                previousStates.add(rightHalf)
                displaySections(rightHalf)

                // If only one action left, navigate
                if (rightHalf.size == 1) {
                    navigateToInstagramAction(rightHalf.first())
                }
            }
        }
    }

    /**
     * Navigate back to HomeFragment (eyes closed for 1.5s)
     */
    private fun navigateToHomeFragment() {
        findNavController().navigate(R.id.homeFragment2)
    }

    /**
     * Navigate based on final selection
     */
    private fun navigateToInstagramAction(selectedAction: String) {
        when (selectedAction) {
            "Google News" -> findNavController().navigate(R.id.instaReelFragment)
            "BBC" -> findNavController().navigate(R.id.instaReelFragment)
            else -> Log.e("Navigation", "No valid action found")
        }
    }
}
