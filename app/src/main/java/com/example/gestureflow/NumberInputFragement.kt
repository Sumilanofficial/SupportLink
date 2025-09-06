package com.example.gestureflow

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.telephony.SmsManager
import androidx.core.app.ActivityCompat
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

class NumberInputFragement : Fragment() {

    // UI elements
    private lateinit var previewView: PreviewView
    private lateinit var selectedText: TextView
    private lateinit var keyboardContainer: ConstraintLayout

    // Background camera executor
    private lateinit var cameraExecutor: ExecutorService

    // Track last selection state
    private var lastSelectedSide: String = ""

    // Media player for sound feedback
    private var mediaPlayer: MediaPlayer? = null

    // Default receiver number (can be changed dynamically)
    private val receiverNumber = "6239062592"

    // Keyboard layout definition
    private val keyboardLayout = listOf(
        "1", "2", "3", "⌫",
        "4", "Enter", "5", "6",
        "7", "8", "9", "0",
        "⌫", "Enter", "6239062592"
    )

    // Button background colors
    private val colors = listOf(
        R.color.white,
        R.color.white,
        R.color.white,
        R.color.white,
        R.color.white,
        R.color.white
    )

    // To track previous states during navigation
    private val previousStates: MutableList<List<String>> = mutableListOf()

    // Currently typed number
    private var typedText: String = ""

    // Blink detection configs
    private val postSelectionDelay = 300L // Delay to prevent fast multiple selections
    private val blinkThreshold = 0.1f // Probability threshold for blink

    private var lastBlinkTime = 0L
    private var blinkCount = 0
    private var lastSelectionTime = 0L

    // Track both eyes closed for navigation
    private var lastEyeCloseTime = 0L
    private var isBothEyesClosed = false

    // Inflate layout and initialize UI
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_number_input_fragement, container, false)

        // Bind UI elements
        previewView = view.findViewById(R.id.camera_preview)
        selectedText = view.findViewById(R.id.selectedKey)
        keyboardContainer = view.findViewById(R.id.keyboardContainer)

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Start camera + show keyboard
        startCamera()
        displayKeyboard(keyboardLayout)

        return view
    }

    /** Play typing selection sound */
    private fun playSelectionSound() {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(requireContext(), R.raw.type)
        mediaPlayer?.start()
    }

    /** Play back button sound */
    private fun playbackSound() {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(requireContext(), R.raw.backbutton)
        mediaPlayer?.start()
    }

    /** Display keyboard keys on screen */
    private fun displayKeyboard(keys: List<String>) {
        keyboardContainer.removeAllViews()

        // If only one key left → process it
        if (keys.size == 1) {
            when (keys.first()) {
                "⌫" -> typedText = typedText.dropLast(1) // Delete last character
                "Enter" -> moveToFixedMessageFragment() // Navigate to next fragment
                else -> typedText += keys.first()
            }
            selectedText.text = "Selected: $typedText"
            previousStates.clear()
            displayKeyboard(keyboardLayout)
            return
        }

        // Add current state to stack
        previousStates.add(keys)

        // Split into left/right groups
        val leftKeys = keys.filterIndexed { index, _ -> index % 2 == 0 }
        val rightKeys = keys.filterIndexed { index, _ -> index % 2 != 0 }

        // Show two halves
        keyboardContainer.addView(createKeySections(leftKeys, rightKeys))
    }

    /** Navigate to home fragment after both eyes closed */
    private fun moveToNextFragment() {
        playbackSound()
        val navController = findNavController()
        navController.navigate(R.id.homeFragment2)
    }

    /** Send SMS message */
    private fun sendTextMessage(message: String) {
        if (message.isNotEmpty()) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    val smsManager = SmsManager.getDefault()
                    smsManager.sendTextMessage(receiverNumber, null, message, null, null)
                    Log.d("SMS", "Message sent successfully")
                    Toast.makeText(requireContext(), "Message sent", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("SMS", "Failed to send message: ${e.message}")
                }
            } else {
                // Request SMS permission
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.SEND_SMS),
                    1
                )
            }
        }
    }

    /** Create horizontal split for keyboard (left vs right) */
    private fun createKeySections(leftKeys: List<String>, rightKeys: List<String>): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL

            addView(createKeySection(leftKeys, ::selectRightKeys))

            // Divider line
            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    setMargins(8, 8, 8, 8)
                }
                setBackgroundColor(android.graphics.Color.GRAY)
            }
            addView(divider)

            addView(createKeySection(rightKeys, ::selectLeftKeys))
        }
    }

    /** Create vertical section of buttons */
    private fun createKeySection(keys: List<String>, onClick: () -> Unit): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            orientation = LinearLayout.VERTICAL
            setPadding(16, 20, 16, 16)

            // Add each key as a button
            keys.forEachIndexed { index, key ->
                val button = Button(context).apply {
                    text = key
                    textSize = 14f
                    setTextColor(android.graphics.Color.BLACK)

                    // Button color
                    val colorRes = colors[index % colors.size]
                    backgroundTintList = ContextCompat.getColorStateList(context, colorRes)
                    background = ContextCompat.getDrawable(context, R.drawable.rounded_button)

                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        120
                    ).apply {
                        setMargins(8, 8, 8, 8)
                    }
                    setPadding(10, 30, 10, 10)
                    maxLines = 2
                    isAllCaps = false

                    setOnClickListener { onClick() }
                }
                addView(button)
            }
        }
    }

    /** Navigate to FixedMessageFragment with entered number */
    private fun moveToFixedMessageFragment() {
        val phoneNumber = typedText.trim()
        if (phoneNumber.isNotEmpty()) {
            val action = NumberInputFragementDirections
                .actionNumberInputFragmentToFixedMessageFragment(phoneNumber)
            findNavController().navigate(action)
        } else {
            Toast.makeText(requireContext(), "Please enter a phone number", Toast.LENGTH_SHORT).show()
        }
    }

    /** Start camera + ML Kit face detection */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetResolution(android.util.Size(320, 240))
                .build()
                .also { it.setAnalyzer(cameraExecutor) { processImageProxy(it) } }

            // Bind to lifecycle
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                imageAnalysis
            )

            // Hide preview if not needed
            previewView.visibility = View.INVISIBLE
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /** Process camera frame */
    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        imageProxy.image?.let { mediaImage ->
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            val detector = com.google.mlkit.vision.face.FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .enableTracking()
                    .build()
            )

            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) processFaces(faces)
                }
                .addOnCompleteListener { imageProxy.close() }
        } ?: imageProxy.close()
    }

    /** Handle blink + eye close detection */
    private fun processFaces(faces: List<Face>) {
        val face = faces[0]
        val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1.0f
        val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1.0f
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastSelectionTime < postSelectionDelay) return

        Log.d("EyeDetection", "Left Eye: $leftEyeOpenProb, Right Eye: $rightEyeOpenProb")

        val isLeftBlink = leftEyeOpenProb < blinkThreshold
        val isRightBlink = rightEyeOpenProb < blinkThreshold

        // If both eyes closed → navigate home after 0.5s
        if (isLeftBlink && isRightBlink) {
            if (!isBothEyesClosed) {
                lastEyeCloseTime = currentTime
                isBothEyesClosed = true
            } else if (currentTime - lastEyeCloseTime >= 500) {
                moveToNextFragment()
            }
            lastBlinkTime = currentTime
            return
        } else {
            isBothEyesClosed = false
            lastEyeCloseTime = 0
        }

        // Handle single-eye blink actions
        if (isLeftBlink) handleBlink(currentTime, ::selectRightKeys)
        if (isRightBlink) handleBlink(currentTime, ::selectLeftKeys)
    }

    /** Manage blink timing */
    private fun handleBlink(currentTime: Long, selectionAction: () -> Unit) {
        if (currentTime - lastBlinkTime >= postSelectionDelay) {
            selectionAction()
            lastSelectionTime = currentTime
        }
        lastBlinkTime = currentTime
    }

    /** Select left half of keys */
    private fun selectLeftKeys() {
        playSelectionSound()
        previousStates.lastOrNull()?.let { leftKeys ->
            displayKeyboard(leftKeys.filterIndexed { index, _ -> index % 2 == 0 })
            lastSelectedSide = "left"
        }
    }

    /** Select right half of keys */
    private fun selectRightKeys() {
        playSelectionSound()
        previousStates.lastOrNull()?.let { rightKeys ->
            displayKeyboard(rightKeys.filterIndexed { index, _ -> index % 2 != 0 })
            lastSelectedSide = "right"
        }
    }

    /** Release resources */
    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
    }
}
