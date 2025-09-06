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

class MessageFragment : Fragment() {

    // Camera preview UI
    private lateinit var previewView: PreviewView
    private lateinit var selectedActionText: TextView
    private lateinit var sectionContainer: ConstraintLayout
    private lateinit var cameraExecutor: ExecutorService

    // Words/Actions available
    private var actions = listOf("Fixed Messages", "Type Message")

    // Stack of previous word lists (used to split choices step by step)
    private var previousStates: MutableList<List<String>> = mutableListOf()

    // For playing sounds
    private var mediaPlayer: MediaPlayer? = null

    // Blink detection state
    private var lastBlinkTime: Long = 0
    private var leftBlinkCount = 0
    private var rightBlinkCount = 0
    private var eyesClosedTime: Long = 0 // Track duration of both eyes closed

    // Colors for buttons
    private val colors = listOf(
        R.color.white, R.color.white, R.color.white,
        R.color.white, R.color.white, R.color.white
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_message, container, false)

        // Initialize UI
        previewView = view.findViewById(R.id.camera_preview)
        selectedActionText = view.findViewById(R.id.selectedWord)
        sectionContainer = view.findViewById(R.id.sectionContainer)

        // Start camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Start camera preview + analysis
        startCamera()

        // Show first set of options
        displaySections(actions)

        return view
    }

    // 🔊 Play sound when selecting an action
    private fun playSelectionSound() {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(requireContext(), R.raw.click)
        mediaPlayer?.start()
    }

    // 🔊 Play sound when going back/home
    private fun playbackSound() {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(requireContext(), R.raw.backbutton)
        mediaPlayer?.start()
    }

    // 📌 Display current list of words (split into left and right)
    private fun displaySections(wordList: List<String>) {
        sectionContainer.removeAllViews()

        // If only one word remains, show it as final selection
        if (wordList.size == 1) {
            selectedActionText.text = "Selected Word: ${wordList.first()}"
            return
        }

        // Add this state to history (for back navigation if needed)
        previousStates.add(wordList)

        // Split into 2 halves
        val leftWords = wordList.subList(0, wordList.size / 2)
        val rightWords = wordList.subList(wordList.size / 2, wordList.size)

        // Horizontal layout containing left + right sections
        val sectionsLayout = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
        }

        // Add left section
        sectionsLayout.addView(createSection(leftWords))

        // Divider between left and right
        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(8, 8, 8, 8)
            }
            setBackgroundColor(android.graphics.Color.GRAY)
        }
        sectionsLayout.addView(divider)

        // Add right section
        sectionsLayout.addView(createSection(rightWords))

        // Add to parent container
        sectionContainer.addView(sectionsLayout)
    }

    // 🔲 Creates a vertical section (column of buttons)
    private fun createSection(words: List<String>): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            orientation = LinearLayout.VERTICAL
            setPadding(16, 20, 16, 16)

            words.forEach { word ->
                // Find correct index for color mapping
                val originalIndex = previousStates.firstOrNull()?.indexOf(word) ?: words.indexOf(word)

                val button = Button(context).apply {
                    text = word
                    textSize = 14f
                    setTextColor(android.graphics.Color.BLACK)

                    // Apply background + tint
                    background = ContextCompat.getDrawable(context, R.drawable.rounded_button)
                    backgroundTintList = ContextCompat.getColorStateList(
                        context, colors[originalIndex % colors.size]
                    )

                    // Layout for button
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        250
                    ).apply { setMargins(8, 8, 8, 10) }

                    setPadding(10, 40, 10, 10)
                    maxLines = 2
                    isAllCaps = false
                }
                addView(button)
            }
        }
    }

    // 📷 Start CameraX with preview + face detection analyzer
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview setup
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Image analysis for MLKit
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

                // Hide preview since user doesn’t need to see camera
                previewView.visibility = View.INVISIBLE
            } catch (exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // 🎯 Process image frames for face/eye detection
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            // Face detection options
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

    // 👀 Handle blink + eye closed detection
    private fun processFaces(faces: List<Face>) {
        if (faces.isNotEmpty()) {
            val face = faces[0]
            val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1.0f
            val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1.0f
            val currentTime = System.currentTimeMillis()

            // 🔴 Both eyes closed -> go back to Home
            if (leftEyeOpenProb < 0.3 && rightEyeOpenProb < 0.3) {
                if (currentTime - eyesClosedTime > 500) {
                    navigateToHomeFragment()
                }
            } else {
                eyesClosedTime = currentTime
            }

            // 👁️ Detect left eye double blink -> choose right side
            if (leftEyeOpenProb < 0.3 && rightEyeOpenProb > 0.7) {
                if (currentTime - lastBlinkTime < 700) {
                    leftBlinkCount++
                    if (leftBlinkCount == 2) {
                        selectRightAction()
                        leftBlinkCount = 0
                    }
                } else leftBlinkCount = 1
                lastBlinkTime = currentTime
            }

            // 👁️ Detect right eye double blink -> choose left side
            if (rightEyeOpenProb < 0.3 && leftEyeOpenProb > 0.7) {
                if (currentTime - lastBlinkTime < 700) {
                    rightBlinkCount++
                    if (rightBlinkCount == 2) {
                        selectLeftAction()
                        rightBlinkCount = 0
                    }
                } else rightBlinkCount = 1
                lastBlinkTime = currentTime
            }
        }
    }

    // ⬅️ Select left half of current list
    private fun selectLeftAction() {
        playSelectionSound()
        previousStates.lastOrNull()?.let { currentList ->
            if (currentList.size > 1) {
                val leftHalf = currentList.subList(0, currentList.size / 2)
                previousStates.add(leftHalf)
                displaySections(leftHalf)

                // If only one word remains, navigate to action
                if (leftHalf.size == 1) navigateToMessagesAction(leftHalf.first())
            }
        }
    }

    // ➡️ Select right half of current list
    private fun selectRightAction() {
        playSelectionSound()
        previousStates.lastOrNull()?.let { currentList ->
            if (currentList.size > 1) {
                val rightHalf = currentList.subList(currentList.size / 2, currentList.size)
                previousStates.add(rightHalf)
                displaySections(rightHalf)

                if (rightHalf.size == 1) navigateToMessagesAction(rightHalf.first())
            }
        }
    }

    // 🏠 Go back to home fragment
    private fun navigateToHomeFragment() {
        playbackSound()
        findNavController().navigate(R.id.homeFragment2)
    }

    // 📲 Navigate to correct message action based on final selection
    private fun navigateToMessagesAction(selectedAction: String) {
        when (selectedAction) {
            "Fixed Messages" -> findNavController().navigate(R.id.numberInputFragement)
            "Type Message" -> findNavController().navigate(R.id.keyboardFragment)
            else -> Log.e("Navigation", "No valid action found")
        }
    }
}
