package com.example.gestureflow

import android.annotation.SuppressLint
import android.media.MediaPlayer
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

class InstagramFragment : Fragment() {

    // Camera & UI
    private lateinit var previewView: PreviewView
    private lateinit var selectedActionText: TextView
    private lateinit var sectionContainer: ConstraintLayout
    private lateinit var cameraExecutor: ExecutorService

    // App logic
    private var actions = listOf("View Feed", "View Reels")
    private var previousStates: MutableList<List<String>> = mutableListOf()
    private var mediaPlayer: MediaPlayer? = null

    // Blink detection
    private var lastBlinkTime: Long = 0
    private var leftBlinkCount = 0
    private var rightBlinkCount = 0
    private var eyesClosedTime: Long = 0 // Track how long both eyes are closed

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_instagram, container, false)

        // Initialize views
        previewView = view.findViewById(R.id.camera_preview)
        selectedActionText = view.findViewById(R.id.selectedWord)
        sectionContainer = view.findViewById(R.id.sectionContainer)

        // Start camera and load first actions
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
        displaySections(actions)

        return view
    }

    /**
     * Play sound when navigating back/home
     */
    private fun playbackSound() {
        mediaPlayer?.release() // Release previous player if still active
        mediaPlayer = MediaPlayer.create(requireContext(), R.raw.backbutton)
        mediaPlayer?.start()
    }

    /**
     * Show action options as sections (splits list into left/right).
     */
    private fun displaySections(actionList: List<String>) {
        sectionContainer.removeAllViews() // Clear previous UI

        // If only one option left → select it
        if (actionList.size == 1) {
            selectedActionText.text = "Selected Action: ${actionList.first()}"
            return
        }

        // Store this state for blink navigation
        previousStates.add(actionList)

        // Split into left/right halves
        val leftActions = actionList.subList(0, actionList.size / 2)
        val rightActions = actionList.subList(actionList.size / 2, actionList.size)

        // Horizontal layout containing two sections
        val sectionsLayout = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
        }

        sectionsLayout.addView(createSection(leftActions))
        sectionsLayout.addView(createSection(rightActions))

        sectionContainer.addView(sectionsLayout)
    }

    /**
     * Create a vertical section with buttons for each action.
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
                    textSize = 12f
                    setTextColor(android.graphics.Color.BLACK)
                    background = ContextCompat.getDrawable(context, R.drawable.rounded_button)

                    // Adjust button size and margins
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        150
                    ).apply {
                        setMargins(8, 8, 8, 8)
                    }

                    setPadding(10, 10, 10, 10)
                    maxLines = 2
                    isAllCaps = false

                    // Manual click navigation (fallback)
                    setOnClickListener {
                        displaySections(actions)
                    }
                }
                addView(button)
            }
        }
    }

    /**
     * Start CameraX with ML Kit face detection.
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

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
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, imageAnalysis)
                previewView.visibility = View.INVISIBLE // Hide preview since we don’t need it
            } catch (exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /**
     * Process image frames for face detection.
     */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()

            val detector = com.google.mlkit.vision.face.FaceDetection.getClient(options)

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
     * Handle detected faces: check blinks and navigation.
     */
    private fun processFaces(faces: List<Face>) {
        if (faces.isNotEmpty()) {
            val face = faces[0]
            val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1.0f
            val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1.0f
            val currentTime = System.currentTimeMillis()

            // Both eyes closed → go Home after timeout
            when {
                leftEyeOpenProb < 0.3 && rightEyeOpenProb < 0.3 -> {
                    if (currentTime - eyesClosedTime > 4000) { // 4 seconds
                        navigateToHomeFragment()
                    }
                }
                else -> {
                    eyesClosedTime = currentTime // Reset timer if eyes are open
                }
            }

            // Left blink = select right section
            if (leftEyeOpenProb < 0.3 && rightEyeOpenProb > 0.7) {
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

            // Right blink = select left section
            if (rightEyeOpenProb < 0.3 && leftEyeOpenProb > 0.7) {
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

    /**
     * Select left half of current actions.
     */
    private fun selectLeftAction() {
        previousStates.lastOrNull()?.let { currentList ->
            if (currentList.size > 1) {
                val leftHalf = currentList.subList(0, currentList.size / 2)
                previousStates.add(leftHalf)
                displaySections(leftHalf)

                if (leftHalf.size == 1) {
                    navigateToInstagramAction(leftHalf.first())
                }
            }
        }
    }

    /**
     * Select right half of current actions.
     */
    private fun selectRightAction() {
        previousStates.lastOrNull()?.let { currentList ->
            if (currentList.size > 1) {
                val rightHalf = currentList.subList(currentList.size / 2, currentList.size)
                previousStates.add(rightHalf)
                displaySections(rightHalf)

                if (rightHalf.size == 1) {
                    navigateToInstagramAction(rightHalf.first())
                }
            }
        }
    }

    /**
     * Navigate to home when both eyes closed long enough.
     */
    private fun navigateToHomeFragment() {
        playbackSound()
        findNavController().navigate(R.id.homeFragment2)
    }

    /**
     * Navigate based on selected action.
     */
    private fun navigateToInstagramAction(selectedAction: String) {
        when (selectedAction) {
            "View Feed" -> findNavController().navigate(R.id.instaFeedFragment)
            "View Reels" -> findNavController().navigate(R.id.instaReelFragment)
            else -> Log.e("Navigation", "No valid action found")
        }
    }
}
