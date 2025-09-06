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
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.content.ContextCompat
import androidx.core.os.HandlerCompat.postDelayed
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
    private lateinit var lottieAnimationView: com.airbnb.lottie.LottieAnimationView
    private lateinit var lottieAnimationView2: com.airbnb.lottie.LottieAnimationView
    private val words = listOf("Open Instagram", "Open Text To Voice", "Open Messages", "Open Calls", "Read News", "Open Whatsapp")

    private val icons = listOf(
        R.drawable.baseline_camera_alt_24,  // Replace with actual drawable resources
        R.drawable.baseline_record_voice_over_24,
        R.drawable.baseline_message_24,
        R.drawable.baseline_call_24,
        R.drawable.baseline_newspaper_24,
        R.drawable.baseline_whatshot_24
    )
    private val colors = listOf(
        R.color.white,  // Replace with actual color resources
        R.color.white,
        R.color.white,
        R.color.white,
        R.color.white,
        R.color.white
    )
    private var previousStates: MutableList<List<String>> = mutableListOf()

    private var lastBlinkTime: Long = 0
    private var leftBlinkCount = 0
    private var rightBlinkCount = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        previewView = view.findViewById(R.id.camera_preview)
        selectedWordText = view.findViewById(R.id.selectedWord)
        sectionContainer = view.findViewById(R.id.sectionContainer)
        backButton = view.findViewById(R.id.backButton)
        lottieAnimationView = view.findViewById(R.id.lottieAnimationView) // Initialize Lottie Animation
 lottieAnimationView2 = view.findViewById(R.id.lottieAnimationView2) // Initialize Lottie Animation

        backButton.setOnClickListener {
            resetToInitialState()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
        displaySections(words)

        return view
    }
    private fun playSelectionSound() {
        mediaPlayer?.release() // Release previous instance if exists
        mediaPlayer = MediaPlayer.create(requireContext(), R.raw.click) // Load sound file
        mediaPlayer?.start() // Play sound
    }
    private fun displaySections(wordList: List<String>) {
        sectionContainer.removeAllViews()

        if (wordList.size == 1) {
            selectedWordText.text = "Selected Word: ${wordList.first()}"
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

        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(8, 8, 8, 8)
            }
            setBackgroundColor(android.graphics.Color.GRAY)
        }
        sectionsLayout.addView(divider)

        sectionsLayout.addView(createSection(rightWords))

        sectionContainer.addView(sectionsLayout)
    }

    private fun createSection(words: List<String>): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            orientation = LinearLayout.VERTICAL
            setPadding(16, 20, 16, 16)

            words.forEach { word ->
                val wordIndex = words.indexOf(word)  // ❌ Incorrect because it only checks within sublist
                val originalIndex = previousStates.firstOrNull()?.indexOf(word) ?: words.indexOf(word)  // ✅ Correct original index

                val button = Button(context).apply {
                    text = word
                    textSize = 14f
                    setTextColor(android.graphics.Color.BLACK)
                    // Ensure the color index is within bounds
                    val colorRes = colors[originalIndex % colors.size]
                    backgroundTintList = ContextCompat.getColorStateList(context, colorRes)

                    background = ContextCompat.getDrawable(context, R.drawable.rounded_button) // Apply rounded design

                    backgroundTintList = ContextCompat.getColorStateList(
                        context, colors[originalIndex % colors.size]
                    )

                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        250
                    ).apply {
                        setMargins(8, 8, 8, 10)
                    }

                    setPadding(10, 40, 10, 10)
                    maxLines = 2
                    isAllCaps = false

                    val drawable = ContextCompat.getDrawable(context, icons[originalIndex % icons.size])
                    drawable?.setBounds(0, 0, 96, 96)
                    setCompoundDrawablesWithIntrinsicBounds(null, drawable, null, null)
                }
                addView(button)
            }
        }
    }




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
                previewView.visibility = View.INVISIBLE
            } catch (exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

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

    private fun processFaces(faces: List<Face>) {
        if (faces.isNotEmpty()) {
            val face = faces[0]
            val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1.0f
            val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1.0f
            val currentTime = System.currentTimeMillis()

            when {
                leftEyeOpenProb < 0.3 && rightEyeOpenProb > 0.7 -> {
                    if (currentTime - lastBlinkTime < 700) {
                        leftBlinkCount++
                        if (leftBlinkCount == 2) {
                            selectRightSection() // Select the left half when the left eye blinks twice
                            leftBlinkCount = 0
                        }
                    } else {
                        leftBlinkCount = 1
                    }
                    lastBlinkTime = currentTime
                }
                rightEyeOpenProb < 0.3 && leftEyeOpenProb > 0.7 -> {
                    if (currentTime - lastBlinkTime < 700) {
                        rightBlinkCount++
                        if (rightBlinkCount == 2) {
                            selectLeftSection() // Select the right half when the right eye blinks twice
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



    private fun selectLeftSection() {
        previousStates.lastOrNull()?.let { currentList ->
            if (currentList.size > 1) {
                val leftHalf = currentList.subList(0, currentList.size / 2)
                previousStates.add(leftHalf)
                playSelectionSound()
                displaySections(leftHalf)

                // Ensure Lottie Animation is initialized before using
                view?.findViewById<LottieAnimationView>(R.id.lottieAnimationView)?.apply {
                    playAnimation()
                    postDelayed({ pauseAnimation() }, 2000)
                }

                // Navigate if only one word is left
                if (leftHalf.size == 1) {
                    navigateToFragment(leftHalf.first())
                }
            }
        }
    }

    private fun selectRightSection() {
        previousStates.lastOrNull()?.let { currentList ->
            if (currentList.size > 1) {
                val rightHalf = currentList.subList(currentList.size / 2, currentList.size)
                previousStates.add(rightHalf)
                playSelectionSound()
                displaySections(rightHalf)

                // Ensure Lottie Animation is initialized before using
                view?.findViewById<LottieAnimationView>(R.id.lottieAnimationView2)?.apply {
                    playAnimation()
                    postDelayed({ pauseAnimation() }, 2000)
                }

                // Navigate if only one word is left
                if (rightHalf.size == 1) {
                    navigateToFragment(rightHalf.first())
                }
            }
        }
    }


    private fun resetToInitialState() {
        previousStates.clear()
        displaySections(words)
        
    }

    private fun navigateToFragment(selectedWord: String) {
        when (selectedWord) {
            "Open Instagram" -> findNavController().navigate(R.id.instaFeedFragment)
            "Open Text To Voice" -> findNavController().navigate(R.id.textToVoice2Fragment)
            "Open Messages" -> findNavController().navigate(R.id.messageFragment)
            "Open Calls" -> findNavController().navigate(R.id.callFragment)
            "Open Whatsapp" -> findNavController().navigate(R.id.whatsappFragment2)
            "Read News"-> findNavController().navigate(R.id.newsFragment)
            else -> Log.e("Navigation", "No valid selection found")
        }
    }
}
