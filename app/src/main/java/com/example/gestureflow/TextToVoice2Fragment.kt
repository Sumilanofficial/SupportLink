package com.example.gestureflow

import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.Manifest
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
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Fragment for eye-blink based keyboard with Text-to-Speech and SMS functionality
class TextToVoice2Fragment : Fragment(), TextToSpeech.OnInitListener {

    // UI elements
    private lateinit var previewView: PreviewView
    private lateinit var selectedText: TextView
    private lateinit var keyboardContainer: ConstraintLayout

    // Background executor for CameraX image processing
    private lateinit var cameraExecutor: ExecutorService

    // Keeps track of last selected side (left/right)
    private var lastSelectedSide: String = ""

    // Button background colors (all white for now)
    private val colors = listOf(
        R.color.white, R.color.white, R.color.white,
        R.color.white, R.color.white, R.color.white
    )

    // Media player for selection & navigation sounds
    private var mediaPlayer: MediaPlayer? = null

    // Phone number passed from previous screen for SMS
    private var receiverNumber: String? = null

    // Keyboard text options
    private val keyboardLayout = listOf(
        "Give me Breakfast",
        "Thank You",
        "Give me Medicine",
        "Help",
        "How are You?",
        "Good Morning"
    )

    // Stores history of divided keyboard states
    private val previousStates: MutableList<List<String>> = mutableListOf()

    // Stores the typed text from selections
    private var typedText: String = ""

    // Blink detection timing
    private val postSelectionDelay = 300L // ms
    private val blinkThreshold = 0.1f // probability for detecting eye closed
    private var lastBlinkTime = 0L
    private var lastSelectionTime = 0L
    private var blinkCount = 0

    // Text-to-Speech instance
    private var textToSpeech: TextToSpeech? = null

    // For tracking both eyes closed (used for navigation)
    private var lastEyeCloseTime = 0L
    private var isBothEyesClosed = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_text_to_voice2, container, false)

        // Initialize UI elements
        previewView = view.findViewById(R.id.camera_preview)
        selectedText = view.findViewById(R.id.selectedKey)
        keyboardContainer = view.findViewById(R.id.keyboardContainer)

        // Executor for CameraX background tasks
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Get receiver phone number from arguments
        receiverNumber = arguments?.getString("phoneNumber")
        arguments?.putString("phoneNumber", "")

        // Initialize Text-to-Speech
        textToSpeech = TextToSpeech(requireContext()) { status -> onInit(status) }
        textToSpeech?.setEngineByPackageName("com.google.android.tts")

        // Start camera for blink detection
        startCamera()

        // Show the first keyboard layout
        displayKeyboard(keyboardLayout)

        return view
    }

    /**
     * Display keyboard options.
     * Splits keys into left/right groups until one final key remains.
     */
    private fun displayKeyboard(keys: List<String>) {
        keyboardContainer.removeAllViews()

        // If only one key is left → handle selection
        if (keys.size == 1) {
            when (keys.first()) {
                "⌫" -> typedText = typedText.dropLast(1) // Backspace
                "Enter" -> {
                    speakText(typedText) // Speak out typed text
                    typedText = "" // Reset after speaking
                }
                else -> {
                    typedText += keys.first() // Add selection to text
                    speakText(typedText) // Speak text
                    typedText = "" // Reset after speaking
                }
            }
            selectedText.text = "Selected: $typedText"
            previousStates.clear()
            displayKeyboard(keyboardLayout) // Restart keyboard
            return
        }

        // Divide into left and right halves
        previousStates.add(keys)
        val leftKeys = keys.filterIndexed { index, _ -> index % 2 == 0 }
        val rightKeys = keys.filterIndexed { index, _ -> index % 2 != 0 }

        // Show split layout
        keyboardContainer.addView(createKeySections(leftKeys, rightKeys))
    }

    /** Play sound when key is selected */
    private fun playSelectionSound() {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(requireContext(), R.raw.type)
        mediaPlayer?.start()
    }

    /** Play sound for back navigation (both eyes closed) */
    private fun playbackSound() {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(requireContext(), R.raw.backbutton)
        mediaPlayer?.start()
    }

    /** Send SMS message to receiver */
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
                // Request SMS permission if not granted
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.SEND_SMS),
                    1
                )
            }
        }
    }

    /** Create horizontal split of left/right key groups */
    private fun createKeySections(leftKeys: List<String>, rightKeys: List<String>): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL

            addView(createKeySection(leftKeys, ::selectRightKeys))

            // Divider line between left and right
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

    /** Create a vertical column of keys (buttons) */
    private fun createKeySection(keys: List<String>, onClick: () -> Unit): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            orientation = LinearLayout.VERTICAL
            setPadding(16, 20, 16, 16)

            // Create a button for each key
            keys.forEachIndexed { index, key ->
                val button = Button(context).apply {
                    text = key
                    textSize = 14f
                    setTextColor(android.graphics.Color.BLACK)

                    // Assign color and rounded background
                    val colorRes = colors[index % colors.size]
                    backgroundTintList = ContextCompat.getColorStateList(context, colorRes)
                    background = ContextCompat.getDrawable(context, R.drawable.rounded_button)

                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        250
                    ).apply { setMargins(8, 8, 8, 8) }

                    setPadding(10, 30, 10, 10)
                    maxLines = 2
                    isAllCaps = false

                    // On click → navigate to opposite side
                    setOnClickListener { onClick() }
                }
                addView(button)
            }
        }
    }

    /** Start CameraX for face/eye detection */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview setup
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // Image analysis for ML Kit
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

            // Hide preview (we don’t need to show the camera feed)
            previewView.visibility = View.INVISIBLE
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /** Process each camera frame and detect faces */
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

    /** Process detected faces for blink gestures */
    private fun processFaces(faces: List<Face>) {
        val face = faces[0]
        val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1.0f
        val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1.0f
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastSelectionTime < postSelectionDelay) return

        Log.d("EyeDetection", "Left Eye: $leftEyeOpenProb, Right Eye: $rightEyeOpenProb")

        val isLeftBlink = leftEyeOpenProb < blinkThreshold
        val isRightBlink = rightEyeOpenProb < blinkThreshold

        // Case: both eyes closed → navigate home
        if (isLeftBlink && isRightBlink) {
            if (!isBothEyesClosed) {
                lastEyeCloseTime = currentTime
                isBothEyesClosed = true
            } else if (currentTime - lastEyeCloseTime >= 500) { // after 0.5 sec
                playbackSound()
                Log.d("EyeDetection", "Both eyes closed, navigating to Home Fragment")
                findNavController().navigate(R.id.homeFragment2)
            }
            lastBlinkTime = currentTime
            return
        } else {
            isBothEyesClosed = false
            lastEyeCloseTime = 0
        }

        // Handle single eye blink
        if (isLeftBlink) handleBlink(currentTime, ::selectRightKeys)
        if (isRightBlink) handleBlink(currentTime, ::selectLeftKeys)
    }

    /** Handle blink selection with timing control */
    private fun handleBlink(currentTime: Long, selectionAction: () -> Unit) {
        if (currentTime - lastBlinkTime >= postSelectionDelay) {
            selectionAction()
            lastSelectionTime = currentTime
        }
        lastBlinkTime = currentTime
    }

    /** Select left-side keys */
    private fun selectLeftKeys() {
        playSelectionSound()
        previousStates.lastOrNull()?.let { leftKeys ->
            displayKeyboard(leftKeys.filterIndexed { index, _ -> index % 2 == 0 })
            lastSelectedSide = "left"
        }
    }

    /** Select right-side keys */
    private fun selectRightKeys() {
        playSelectionSound()
        previousStates.lastOrNull()?.let { rightKeys ->
            displayKeyboard(rightKeys.filterIndexed { index, _ -> index % 2 != 0 })
            lastSelectedSide = "right"
        }
    }

    /** Speak text using Text-to-Speech */
    fun speakText(text: String) {
        if (text.isNotEmpty()) {
            val result = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_ID")
            if (result == TextToSpeech.ERROR) {
                Log.e("TTS", "Failed to speak text")
                Toast.makeText(requireContext(), "Failed to speak text", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Speaking: $text", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(requireContext(), "No text to speak", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Stop and release Text-to-Speech
        textToSpeech?.stop()
        textToSpeech?.shutdown()

        // Stop camera executor
        cameraExecutor.shutdown()
    }

    /** Callback after TTS initialization */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Language is not supported or missing data")
            } else {
                Log.d("TTS", "TextToSpeech initialized successfully")
                speakText("") // Test speech
            }
        } else {
            Log.e("TTS", "TextToSpeech initialization failed")
        }
    }
}
