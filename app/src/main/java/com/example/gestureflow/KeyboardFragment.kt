package com.example.gestureflow

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class KeyboardFragment : Fragment() {

    // --- UI Components ---
    private lateinit var previewView: PreviewView
    private lateinit var selectedText: TextView
    private lateinit var keyboardContainer: ConstraintLayout

    // --- Camera + Face Detection ---
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var faceDetector: FaceDetector

    // --- Other variables ---
    private var receiverNumber: String? = null   // Number to send SMS
    private var mediaPlayer: MediaPlayer? = null

    // Button background colors cycling
    private val colors = listOf(
        R.color.white, R.color.white, R.color.white,
        R.color.white, R.color.white, R.color.white
    )

    // Keyboard Layout
    private val keyboardLayout = listOf(
        "A", "B", "C","D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
        "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        " ", "⌫", "Enter"
    )

    // Keep track of progressive keyboard states (binary selection)
    private val previousStates: MutableList<List<String>> = mutableListOf()
    private var typedText: String = ""   // Final typed text

    // --- Gesture Detection State ---
    private val postSelectionDelay = 300L  // Delay after selection
    private val blinkThreshold = 0.1f      // Not used directly, but kept for tuning
    private var lastBlinkTime = 0L
    private var lastSelectionTime = 0L
    private var leftBlinkCount = 0
    private var rightBlinkCount = 0


    // Inflate the fragment layout
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_keyboard, container, false)

        // Get receiver phone number from arguments
        arguments?.let {
            receiverNumber = it.getString("phoneNumber")
        }

        // Bind UI
        previewView = view.findViewById(R.id.camera_preview)
        selectedText = view.findViewById(R.id.selectedKey)
        keyboardContainer = view.findViewById(R.id.keyboardContainer)

        // Initialize Camera + MLKit
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupFaceDetector()
        startCamera()

        // Show keyboard layout
        displayKeyboard(keyboardLayout)

        return view
    }

    // Release resources
    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        faceDetector.close()
        mediaPlayer?.release()
    }

    // Setup MLKit Face Detector
    private fun setupFaceDetector() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
        faceDetector = com.google.mlkit.vision.face.FaceDetection.getClient(options)
    }

    // Display keyboard keys (recursive narrowing)
    private fun displayKeyboard(keys: List<String>) {
        keyboardContainer.removeAllViews()

        // If only 1 key left, finalize selection
        if (keys.size == 1) {
            when (val key = keys.first()) {
                "⌫" -> if (typedText.isNotEmpty()) typedText = typedText.dropLast(1)
                "Enter" -> {
                    sendTextMessage(typedText)
                    typedText = ""
                }
                " " -> typedText += " "
                else -> typedText += key
            }

            // Update UI
            selectedText.text = typedText

            // Reset and reload full keyboard
            previousStates.clear()
            displayKeyboard(keyboardLayout)
            return
        }

        // Save current state and split into halves
        previousStates.add(keys)
        val leftKeys = keys.take(keys.size / 2)
        val rightKeys = keys.drop(keys.size / 2)

        // Display two sections
        keyboardContainer.addView(createKeySections(leftKeys, rightKeys))
    }

    // Send SMS
    private fun sendTextMessage(message: String) {
        if (receiverNumber.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "Recipient number is missing", Toast.LENGTH_SHORT).show()
            return
        }
        if (message.isNotEmpty()) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    SmsManager.getDefault().sendTextMessage(receiverNumber, null, message, null, null)
                    Toast.makeText(requireContext(), "Message sent", Toast.LENGTH_SHORT).show()
                    findNavController().navigate(R.id.homeFragment2)
                } catch (e: Exception) {
                    Log.e("SMS", "Failed to send message: ${e.message}")
                }
            } else {
                // Ask permission if not granted
                ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.SEND_SMS), 1)
            }
        }
    }

    // Create two-column keyboard layout
    private fun createKeySections(leftKeys: List<String>, rightKeys: List<String>): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            orientation = LinearLayout.HORIZONTAL

            // Left side -> right blink
            addView(createKeySection(leftKeys, ::selectRightKeys))

            // Divider
            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.MATCH_PARENT).apply { setMargins(8, 8, 8, 8) }
                setBackgroundColor(android.graphics.Color.GRAY)
            }
            addView(divider)

            // Right side -> left blink
            addView(createKeySection(rightKeys, ::selectLeftKeys))
        }
    }

    // Create a vertical section of keys
    private fun createKeySection(keys: List<String>, onClick: () -> Unit): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            orientation = LinearLayout.VERTICAL

            keys.forEachIndexed { index, key ->
                val button = Button(context).apply {
                    text = key
                    textSize = 14f
                    setTextColor(android.graphics.Color.BLACK)
                    backgroundTintList = ContextCompat.getColorStateList(context, colors[index % colors.size])
                    background = ContextCompat.getDrawable(context, R.drawable.rounded_button)

                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    ).apply { setMargins(8, 8, 8, 8) }

                    isAllCaps = false
                    setOnClickListener { onClick() }
                }
                addView(button)
            }
        }
    }

    // Start CameraX + Analyzer
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(cameraExecutor, this::processImageProxy) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis)

                // Hide preview (only used for detection)
                previewView.visibility = View.INVISIBLE
            } catch (exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // Analyze each frame
    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (view == null) { imageProxy.close(); return }
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            faceDetector.process(image)
                .addOnSuccessListener { faces -> if (faces.isNotEmpty()) processFaces(faces) }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    // Process detected faces + blink gestures
    private fun processFaces(faces: List<Face>) {
        val face = faces.firstOrNull() ?: return
        val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1.0f
        val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1.0f
        val currentTime = System.currentTimeMillis()

        // Avoid too frequent selections
        if (currentTime - lastSelectionTime < postSelectionDelay) return

        val isLeftBlink = leftEyeOpenProb < 0.3
        val isRightBlink = rightEyeOpenProb < 0.3

        // Both eyes closed → exit to Home
        if (isLeftBlink && isRightBlink) {
            playbackSound(R.raw.backbutton)
            findNavController().navigate(R.id.homeFragment2)
            return
        }

        // Double left blink → select right half
        if (isLeftBlink) {
            if (currentTime - lastBlinkTime < 700) {
                leftBlinkCount++
                if (leftBlinkCount == 2) {
                    selectRightKeys()
                    leftBlinkCount = 0
                }
            } else {
                leftBlinkCount = 1
            }
            lastBlinkTime = currentTime
        }
        // Double right blink → select left half
        else if (isRightBlink) {
            if (currentTime - lastBlinkTime < 700) {
                rightBlinkCount++
                if (rightBlinkCount == 2) {
                    selectLeftKeys()
                    rightBlinkCount = 0
                }
            } else {
                rightBlinkCount = 1
            }
            lastBlinkTime = currentTime
        }
    }

    // --- Selection Helpers ---
    private fun selectLeftKeys() {
        playSelectionSound()
        previousStates.lastOrNull()?.let { leftKeys ->
            displayKeyboard(leftKeys.take(leftKeys.size / 2))
        }
    }

    private fun selectRightKeys() {
        playSelectionSound()
        previousStates.lastOrNull()?.let { rightKeys ->
            displayKeyboard(rightKeys.drop(rightKeys.size / 2))
        }
    }

    // Play key press sound
    private fun playSelectionSound() {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(requireContext(), R.raw.type)
        mediaPlayer?.start()
    }

    // Play other sounds (like back button)
    private fun playbackSound(soundResId: Int) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(requireContext(), soundResId)
        mediaPlayer?.start()
    }
}
