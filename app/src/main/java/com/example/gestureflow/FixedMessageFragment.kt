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

class FixedMessageFragment : Fragment() {

    // Camera preview surface
    private lateinit var previewView: PreviewView
    // Text showing the selected/sent message
    private lateinit var selectedText: TextView
    // Layout container for the split keyboard
    private lateinit var keyboardContainer: ConstraintLayout
    // Executor for camera background tasks
    private lateinit var cameraExecutor: ExecutorService
    // Track last side selected (left/right)
    private var lastSelectedSide: String = ""
    // Receiver phone number (passed from ContactSelectionFragment)
    private var receiverNumber: String? = null

    // Media players for sound feedback
    private val clickPlayer by lazy { MediaPlayer.create(requireContext(), R.raw.click) }
    private val backPlayer by lazy { MediaPlayer.create(requireContext(), R.raw.backbutton) }

    // Predefined fixed messages
    private val keyboardLayout  = listOf(
        "Hello! How can I assist you today?",
        "Greetings! Hope you're having a wonderful day!",
        "Hi there! What can I do for you today?",
        "Hey! Welcome, let's make your day better!",
        "Good day! Feel free to ask me anything.",
        "Hello! I'm here to help you with anything you need."
    )

    // Stack to keep track of previous split states
    private val previousStates: MutableList<List<String>> = mutableListOf()
    // Text currently typed/selected
    private var typedText: String = ""

    // Timing configs
    private val postSelectionDelay = 300L   // min gap between selections
    private val blinkThreshold = 0.3f       // threshold for blink detection
    private var lastBlinkTime = 0L          // last blink detected
    private var lastSelectionTime = 0L      // last selection made

    // For detecting both eyes closed
    private var lastEyeCloseTime = 0L
    private var isBothEyesClosed = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate layout
        val view = inflater.inflate(R.layout.fragment_fixed_message, container, false)

        // Get references from layout
        previewView = view.findViewById(R.id.camera_preview)
        selectedText = view.findViewById(R.id.selectedKey)
        keyboardContainer = view.findViewById(R.id.keyboardContainer)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Get phone number from arguments
        receiverNumber = arguments?.getString("phoneNumber")
        arguments?.putString("phoneNumber", "")

        // Start camera + show initial keyboard
        startCamera()
        displayKeyboard(keyboardLayout)

        return view
    }

    // Show keyboard (split messages into left/right)
    private fun displayKeyboard(keys: List<String>) {
        keyboardContainer.removeAllViews()

        // If only one option is left → select it
        if (keys.size == 1) {
            when (keys.first()) {
                "⌫" -> typedText = typedText.dropLast(1)
                "Enter" -> {
                    sendTextMessage(typedText)
                    typedText = ""
                }
                else -> {
                    val message = keys.first()
                    typedText = message
                    sendTextMessage(message)
                    typedText = ""
                }
            }

            selectedText.text = "Message Sent!"
            previousStates.clear()
            lastSelectedSide = ""
            displayKeyboard(keyboardLayout)
            return
        }

        // Split multiple options into left/right
        previousStates.add(keys)
        val leftKeys = keys.filterIndexed { index, _ -> index % 2 == 0 }
        val rightKeys = keys.filterIndexed { index, _ -> index % 2 != 0 }

        keyboardContainer.addView(createKeySections(leftKeys, rightKeys))
    }

    // Play click sound
    private fun playSelectionSound() { clickPlayer.start() }

    // Play back button sound
    private fun playbackSound() { backPlayer.start() }

    // Send SMS to receiver
    private fun sendTextMessage(message: String) {
        if (message.isNotEmpty()) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED) {
                try {
                    val smsManager = SmsManager.getDefault()
                    smsManager.sendTextMessage(receiverNumber, null, message, null, null)
                    Log.d("SMS", "Message sent successfully")
                    Toast.makeText(requireContext(), "Message Sent", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("SMS", "Failed to send message: ${e.message}")
                }
            } else {
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.SEND_SMS),
                    1
                )
            }
        }
    }

    // Handle SMS permission result
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(requireContext(), "Permission Granted. Please try again.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "SMS Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Create horizontal layout with left/right key sections
    private fun createKeySections(leftKeys: List<String>, rightKeys: List<String>): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL

            addView(createKeySection(leftKeys, ::selectRightKeys))

            // Divider
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

    // Create vertical section for each key group
    private fun createKeySection(keys: List<String>, onClick: () -> Unit): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            orientation = LinearLayout.VERTICAL
            setPadding(16, 20, 16, 16)

            keys.forEach { key ->
                val button = Button(context).apply {
                    text = key
                    textSize = 14f
                    setTextColor(android.graphics.Color.BLACK)
                    background = ContextCompat.getDrawable(context, R.drawable.rounded_button)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        250
                    ).apply { setMargins(8, 8, 8, 8) }
                    setPadding(30, 30, 30, 30)
                    maxLines = 2
                    isAllCaps = false
                    setOnClickListener { onClick() }
                }
                addView(button)
            }
        }
    }

    // Start CameraX + ML Kit for face detection
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
                .build().also { it.setAnalyzer(cameraExecutor) { processImageProxy(it) } }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                imageAnalysis
            )

            previewView.visibility = View.INVISIBLE
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // Process camera frame
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
                .addOnSuccessListener { faces -> if (faces.isNotEmpty()) processFaces(faces) }
                .addOnCompleteListener { imageProxy.close() }
        } ?: imageProxy.close()
    }

    // Process detected faces → handle blinks
    private fun processFaces(faces: List<Face>) {
        val face = faces[0]
        val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1.0f
        val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1.0f
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastSelectionTime < postSelectionDelay) return

        val isLeftBlink = leftEyeOpenProb < blinkThreshold
        val isRightBlink = rightEyeOpenProb < blinkThreshold

        // Both eyes closed
        if (isLeftBlink && isRightBlink) {
            if (!isBothEyesClosed) {
                lastEyeCloseTime = currentTime
                isBothEyesClosed = true
            } else {
                val duration = currentTime - lastEyeCloseTime

                // Close fragment for ~0.4s
                if (duration in 400..500) {
                    Log.d("EyeDetection", "Both eyes closed ~0.4s → closing fragment")
                    playbackSound()
                    findNavController().popBackStack()
                    isBothEyesClosed = false
                    return
                }

                // Long close → go home
                if (duration >= 4000) {
                    Log.d("EyeDetection", "Both eyes closed 4s → navigating home")
                    playbackSound()
                    findNavController().navigate(R.id.homeFragment2)
                    isBothEyesClosed = false
                    return
                }
            }
            lastBlinkTime = currentTime
            return
        } else {
            isBothEyesClosed = false
            lastEyeCloseTime = 0
        }

        if (isLeftBlink) handleBlink(currentTime, ::selectRightKeys)
        if (isRightBlink) handleBlink(currentTime, ::selectLeftKeys)
    }

    // Handle blink → select keys
    private fun handleBlink(currentTime: Long, selectionAction: () -> Unit) {
        if (currentTime - lastBlinkTime >= postSelectionDelay) {
            selectionAction()
            lastSelectionTime = currentTime
        }
        lastBlinkTime = currentTime
    }

    // Select left/right keys
    private fun selectLeftKeys() {
        playSelectionSound()
        previousStates.lastOrNull()?.let { keys ->
            displayKeyboard(keys.filterIndexed { index, _ -> index % 2 == 0 })
            lastSelectedSide = "left"
        }
    }

    private fun selectRightKeys() {
        playSelectionSound()
        previousStates.lastOrNull()?.let { keys ->
            displayKeyboard(keys.filterIndexed { index, _ -> index % 2 != 0 })
            lastSelectedSide = "right"
        }
    }

    // Clean up
    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        clickPlayer.release()
        backPlayer.release()
    }
}
