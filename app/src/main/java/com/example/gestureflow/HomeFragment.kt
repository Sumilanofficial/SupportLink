package com.example.gestureflow

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
import com.airbnb.lottie.LottieAnimationView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HomeFragment : Fragment() {

    private lateinit var previewView: PreviewView
    private lateinit var selectedWordText: TextView
    private lateinit var sectionContainer: ConstraintLayout
    private lateinit var backButton: Button
    private lateinit var cameraExecutor: ExecutorService
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var lottieAnimationView: LottieAnimationView
    private lateinit var lottieAnimationView2: LottieAnimationView

    // Menu options shown on home
    private val words = listOf("Open Instagram", "Open Text To Voice", "Open Messages", "Open Calls", "Read News", "Quick Dial")

    // Icons for each option
    private val icons = listOf(
        R.drawable.baseline_camera_alt_24,
        R.drawable.baseline_record_voice_over_24,
        R.drawable.baseline_message_24,
        R.drawable.baseline_call_24,
        R.drawable.baseline_newspaper_24,
        R.drawable.baseline_support_agent_24
    )

    // Colors (currently all white)
    private val colors = listOf(
        R.color.white, R.color.white, R.color.white,
        R.color.white, R.color.white, R.color.white
    )

    // Stack of previous states for recursive selection
    private var previousStates: MutableList<List<String>> = mutableListOf()

    // Blink detection variables
    private var lastBlinkTime: Long = 0
    private var leftBlinkCount = 0
    private var rightBlinkCount = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        // Bind views
        previewView = view.findViewById(R.id.camera_preview)
        selectedWordText = view.findViewById(R.id.selectedWord)
        sectionContainer = view.findViewById(R.id.sectionContainer)
        backButton = view.findViewById(R.id.backButton)
        lottieAnimationView = view.findViewById(R.id.lottieAnimationView)
        lottieAnimationView2 = view.findViewById(R.id.lottieAnimationView2)

        // Reset to home menu when back is clicked
        backButton.setOnClickListener {
            resetToInitialState()
        }

        // Start camera and show first menu
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
        displaySections(words)

        return view
    }

    // Play selection click sound
    private fun playSelectionSound() {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(requireContext(), R.raw.click)
        mediaPlayer?.start()
    }

    // Display menu split into left/right sections
    private fun displaySections(wordList: List<String>) {
        sectionContainer.removeAllViews()

        // If only one option remains → selection is complete
        if (wordList.size == 1) {
            selectedWordText.text = "Selected: ${wordList.first()}"
            return
        }

        previousStates.add(wordList)

        val leftWords = wordList.subList(0, wordList.size / 2)
        val rightWords = wordList.subList(wordList.size / 2, wordList.size)

        val sectionsLayout = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
        }

        sectionsLayout.addView(createSection(leftWords))

        // Divider between left and right
        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                setMargins(8, 8, 8, 8)
            }
            setBackgroundColor(android.graphics.Color.GRAY)
        }
        sectionsLayout.addView(divider)

        sectionsLayout.addView(createSection(rightWords))

        sectionContainer.addView(sectionsLayout)
    }

    // Create a vertical section of buttons
    private fun createSection(subWords: List<String>): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            orientation = LinearLayout.VERTICAL
            setPadding(16, 20, 16, 16)

            subWords.forEach { word ->
                val originalIndex = words.indexOf(word)

                val button = Button(context).apply {
                    text = word
                    textSize = 14f
                    setTextColor(android.graphics.Color.BLACK)

                    // Assign color and icon if available
                    if (originalIndex != -1) {
                        val colorRes = colors[originalIndex % colors.size]
                        backgroundTintList = ContextCompat.getColorStateList(context, colorRes)

                        val drawable = ContextCompat.getDrawable(context, icons[originalIndex % icons.size])
                        setCompoundDrawablesWithIntrinsicBounds(null, drawable, null, null)
                    }

                    background = ContextCompat.getDrawable(context, R.drawable.rounded_button)

                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        250
                    ).apply {
                        setMargins(8, 8, 8, 10)
                    }

                    setPadding(10, 40, 10, 10)
                    maxLines = 2
                    isAllCaps = false
                }
                addView(button)
            }
        }
    }

    // Start CameraX + ML Kit Face Detection
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
                    it.setAnalyzer(cameraExecutor, this::processImageProxy)
                }
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, imageAnalysis)
                previewView.visibility = View.INVISIBLE // hides camera feed
            } catch (exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // Convert camera frames into ML Kit Face Detection
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
            val detector = com.google.mlkit.vision.face.FaceDetection.getClient(options)
            detector.process(image)
                .addOnSuccessListener { faces -> processFaces(faces) }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    // Process detected faces → detect left/right eye blinks
    private fun processFaces(faces: List<Face>) {
        if (faces.isNotEmpty()) {
            val face = faces[0]
            val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1.0f
            val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1.0f
            val currentTime = System.currentTimeMillis()

            when {
                // Left double blink → select right
                leftEyeOpenProb < 0.3 && rightEyeOpenProb > 0.7 -> {
                    if (currentTime - lastBlinkTime < 700) {
                        leftBlinkCount++
                        if (leftBlinkCount == 2) {
                            selectRightSection()
                            leftBlinkCount = 0
                        }
                    } else {
                        leftBlinkCount = 1
                    }
                    lastBlinkTime = currentTime
                }
                // Right double blink → select left
                rightEyeOpenProb < 0.3 && leftEyeOpenProb > 0.7 -> {
                    if (currentTime - lastBlinkTime < 700) {
                        rightBlinkCount++
                        if (rightBlinkCount == 2) {
                            selectLeftSection()
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

    // Select left half of options
    private fun selectLeftSection() {
        previousStates.lastOrNull()?.let { currentList ->
            if (currentList.size > 1) {
                val leftHalf = currentList.subList(0, currentList.size / 2)
                playSelectionSound()
                displaySections(leftHalf)
                lottieAnimationView.playAnimation()
                if (leftHalf.size == 1) {
                    navigateToFragment(leftHalf.first())
                }
            }
        }
    }

    // Select right half of options
    private fun selectRightSection() {
        previousStates.lastOrNull()?.let { currentList ->
            if (currentList.size > 1) {
                val rightHalf = currentList.subList(currentList.size / 2, currentList.size)
                playSelectionSound()
                displaySections(rightHalf)
                lottieAnimationView2.playAnimation()
                if (rightHalf.size == 1) {
                    navigateToFragment(rightHalf.first())
                }
            }
        }
    }

    // Reset menu to original state
    private fun resetToInitialState() {
        previousStates.clear()
        displaySections(words)
    }

    // Navigation to corresponding fragments
    private fun navigateToFragment(selectedWord: String) {
        when (selectedWord) {
            "Open Instagram" -> findNavController().navigate(R.id.instaFeedFragment)
            "Open Text To Voice" -> findNavController().navigate(R.id.textToVoice2Fragment)
            "Open Messages" -> findNavController().navigate(R.id.messageFragment)
            "Open Calls" -> findNavController().navigate(R.id.callFragment)
            "Read News"-> findNavController().navigate(R.id.newsFragment)
            "Quick Dial" -> findNavController().navigate(R.id.quickDialFragment)
            else -> Log.e("Navigation", "No valid selection found for: $selectedWord")
        }
    }
}
