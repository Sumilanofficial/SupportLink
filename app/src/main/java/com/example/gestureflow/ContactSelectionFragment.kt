package com.example.gestureflow

import android.annotation.SuppressLint
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.gestureflow.databinding.FragmentContactSelectionBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ContactSelectionFragment : Fragment() {

    // --- View Binding ---
    private var _binding: FragmentContactSelectionBinding? = null
    private val binding get() = _binding!!

    // --- Background threads & ML Kit ---
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var faceDetector: FaceDetector

    // --- Contact & Keyboard Layouts ---
    private val mainLayout = listOf( // Main options shown first
        "Dad (623...)", "Mom (987...)", "Type Number", "Emergency (1299)"
    )

    // Mapping contact button text → actual phone number
    private val contactMap = mapOf(
        "My (623...)" to "6239062592",
        "Mom (987...)" to "12",
        "Emergency (1299)" to "1299"
    )

    // Number keyboard layout for manual typing
    private val numberKeyboardLayout = listOf(
        "1", "2", "3", "4", "5", "6", "7", "8", "9",
        "*", "0", "#", "⌫", "Next"
    )

    private var previousStates: MutableList<List<String>> = mutableListOf() // Keeps track of key splits
    private var typedNumber: String = ""  // Stores the typed number
    private var isTypingMode = false      // Flag for typing vs contact selection

    // --- Blink Gesture Detection ---
    private val postSelectionDelay = 300L  // Delay between blinks (ms)
    private val blinkThreshold = 0.1f      // Probability threshold for blink detection
    private var lastBlinkTime = 0L
    private var lastSelectionTime = 0L
    private var lastEyeCloseTime = 0L
    private var isBothEyesClosed = false   // Detect long both-eye closure (exit/back)

    private var mediaPlayer: MediaPlayer? = null // For audio feedback

    // --- Lifecycle Methods ---
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentContactSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupFaceDetector()                      // Initialize ML Kit detector
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()                            // Start CameraX
        displayKeyboard(mainLayout)              // Show main contact options
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        faceDetector.close()
        mediaPlayer?.release()
        _binding = null // Prevent memory leaks
    }

    // --- ML Kit Face Detector Setup ---
    private fun setupFaceDetector() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // Eye open/close probs
            .build()
        faceDetector = com.google.mlkit.vision.face.FaceDetection.getClient(options)
    }

    // --- Dynamic Keyboard Display ---
    private fun displayKeyboard(keys: List<String>) {
        Handler(Looper.getMainLooper()).post {
            if (_binding == null) return@post
            binding.keyboardContainerContact.removeAllViews()

            // If only one key left → final selection
            if (keys.size == 1) {
                handleFinalSelection(keys.first())
                return@post
            }

            // Store current state
            previousStates.add(keys)

            // Split into left/right halves
            val leftKeys = keys.take(keys.size / 2)
            val rightKeys = keys.drop(keys.size / 2)

            // Add left section
            binding.keyboardContainerContact.addView(createKeySection(leftKeys))

            // Divider
            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.DKGRAY)
            }
            binding.keyboardContainerContact.addView(divider)

            // Add right section
            binding.keyboardContainerContact.addView(createKeySection(rightKeys))
        }
    }

    // Handle final key after selection narrowed down
    private fun handleFinalSelection(key: String) {
        if (isTypingMode) {
            when (key) {
                "⌫" -> if (typedNumber.isNotEmpty()) typedNumber = typedNumber.dropLast(1)
                "Next" -> navigateToKeyboard(typedNumber)
                else -> typedNumber += key
            }
            updateTypedText()
            previousStates.clear()
            displayKeyboard(numberKeyboardLayout) // Reload number keypad
        } else {
            when (key) {
                "Type Number" -> { // Switch to typing mode
                    isTypingMode = true
                    updateTypedText()
                    previousStates.clear()
                    displayKeyboard(numberKeyboardLayout)
                }
                else -> { // Direct contact selection
                    val numberToMessage = contactMap[key]
                    navigateToKeyboard(numberToMessage)
                }
            }
        }
    }

    // Create vertical column of keys (buttons)
    private fun createKeySection(keys: List<String>): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            orientation = LinearLayout.VERTICAL

            keys.forEach { key ->
                val button = Button(context).apply {
                    text = key
                    textSize = 24f
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    background = ContextCompat.getDrawable(context, R.drawable.rounded_button)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    ).apply { setMargins(16, 8, 16, 8) }
                }
                addView(button)
            }
        }
    }

    // --- Blink Selection Handlers ---
    private fun selectLeftKeys() {
        playbackSound(R.raw.type)
        val currentKeys = previousStates.lastOrNull() ?: return
        displayKeyboard(currentKeys.take(currentKeys.size / 2))
    }

    private fun selectRightKeys() {
        playbackSound(R.raw.type)
        val currentKeys = previousStates.lastOrNull() ?: return
        displayKeyboard(currentKeys.drop(currentKeys.size / 2))
    }

    // Update the text above keyboard (status / typed number)
    private fun updateTypedText() {
        val textToShow = when {
            isTypingMode && typedNumber.isNotEmpty() -> typedNumber
            isTypingMode -> "Type Number..."
            else -> "Select Contact or Type Number"
        }
        binding.selectedNumberText.text = textToShow
    }

    // --- Navigation to Next Fragment ---
    private fun navigateToKeyboard(phoneNumber: String?) {
        if (phoneNumber.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "Please select or type a number", Toast.LENGTH_SHORT).show()
            return
        }
        val bundle = Bundle().apply { putString("phoneNumber", phoneNumber) }
        findNavController().navigate(R.id.action_contactSelectionFragment_to_keyboardFragment, bundle)
    }

    // --- CameraX Setup ---
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreviewContact.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder().build().also {
                it.setAnalyzer(cameraExecutor, this::processImageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e("CameraX", "Error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // Analyze each camera frame
    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (_binding == null) { imageProxy.close(); return }
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

    // Process detected faces & check for blinks
    private fun processFaces(faces: List<Face>) {
        val face = faces.firstOrNull() ?: return
        val currentTime = System.currentTimeMillis()

        // Prevent rapid multiple selections
        if (currentTime - lastSelectionTime < postSelectionDelay) return

        val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1.0f
        val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1.0f
        val isLeftBlink = leftEyeOpenProb < blinkThreshold
        val isRightBlink = rightEyeOpenProb < blinkThreshold

        // --- Both eyes closed (long press = back) ---
        if (isLeftBlink && isRightBlink) {
            if (!isBothEyesClosed) {
                lastEyeCloseTime = currentTime
                isBothEyesClosed = true
            } else if (currentTime - lastEyeCloseTime >= 2000) { // 2 sec hold
                playbackSound(R.raw.backbutton)
                findNavController().navigate(R.id.action_contactSelectionFragment_to_messageFragment)
            }
            return
        } else {
            isBothEyesClosed = false
            lastEyeCloseTime = 0
        }

        // --- Single blinks ---
        if (isLeftBlink) handleBlink(currentTime, ::selectRightKeys) // Left blink → right side
        if (isRightBlink) handleBlink(currentTime, ::selectLeftKeys) // Right blink → left side
    }

    // Debounce blink actions
    private fun handleBlink(currentTime: Long, selectionAction: () -> Unit) {
        if (currentTime - lastBlinkTime >= postSelectionDelay) {
            selectionAction()
            lastSelectionTime = currentTime
        }
        lastBlinkTime = currentTime
    }

    // --- Audio Feedback ---
    private fun playbackSound(soundResId: Int) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(requireContext(), soundResId)?.apply {
                setOnCompletionListener { it.release() }
                start()
            }
        } catch (e: Exception) {
            Log.e("Sound", "Error playing sound", e)
        }
    }
}
