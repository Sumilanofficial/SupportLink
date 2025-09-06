package com.example.gestureflow

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CallFragment : Fragment() {

    // --- UI Elements ---
    private lateinit var previewView: PreviewView // The view that shows the camera feed
    private lateinit var selectedText: TextView // The large text display at the top
    private lateinit var keyboardContainer: LinearLayout // The container where keyboard halves are shown
    private var mediaPlayer: MediaPlayer? = null // To play sound effects

    // --- Asynchronous Processing ---
    private lateinit var cameraExecutor: ExecutorService // A background thread for camera processing
    private lateinit var faceDetector: FaceDetector // The ML Kit object that detects faces

    // --- Keyboard Logic ---
    // The complete set of keys for the dialer
    private val keyboardLayout = listOf(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#", "⌫", "Call"
    )
    // Stores the history of keyboard splits so the user can make selections
    private val previousStates: MutableList<List<String>> = mutableListOf()
    // The phone number the user is currently typing
    private var typedNumber: String = ""

    // --- Gesture Detection State ---
    private val postSelectionDelay = 300L // A brief pause after a blink is detected
    private val blinkThreshold = 0.1f // How "closed" an eye must be to count as a blink
    private var lastBlinkTime = 0L // Timestamp of the last detected blink
    private var lastSelectionTime = 0L // Timestamp of the last selection action
    private var lastEyeCloseTime = 0L // Timestamp when both eyes were first detected as closed
    private var isBothEyesClosed = false // Flag to track if the user is holding their eyes closed

    /**
     * Called when the fragment's view is first created.
     * It inflates the layout and initializes all the components.
     */
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_call, container, false)
        initializeUI(view)
        setupFaceDetector()
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
        displayKeyboard(keyboardLayout) // Display the initial full keyboard
        return view
    }

    /**
     * Called when the fragment's view is being destroyed.
     * It's crucial to release resources here to prevent memory leaks.
     */
    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown() // Stop the background thread
        faceDetector.close() // Release the face detector resources
        mediaPlayer?.release() // Release the media player
    }

    /**
     * Initializes all the UI elements by finding them in the layout file.
     */
    private fun initializeUI(view: View) {
        previewView = view.findViewById(R.id.camera_preview)
        selectedText = view.findViewById(R.id.selectedKey)
        keyboardContainer = view.findViewById(R.id.keyboardContainer)
        updateTypedText() // Set the initial hint text
    }

    /**
     * Configures and creates the ML Kit face detector.
     * It's set to FAST mode for real-time performance.
     */
    private fun setupFaceDetector() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
        faceDetector = com.google.mlkit.vision.face.FaceDetection.getClient(options)
    }

    /**
     * The core of the binary search keyboard. It takes a list of keys,
     * splits it in half, and displays each half on the screen.
     * If only one key is left, it triggers the final selection.
     */
    private fun displayKeyboard(keys: List<String>) {
        keyboardContainer.removeAllViews()

        // Base case: If only one key remains, handle it as the final choice.
        if (keys.size == 1) {
            handleFinalSelection(keys.first())
            return
        }

        // Recursive step: Split the keys and display them.
        previousStates.add(keys)
        val leftKeys = keys.take(keys.size / 2)
        val rightKeys = keys.drop(keys.size / 2)

        keyboardContainer.addView(createKeySection(leftKeys))
        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.DKGRAY)
        }
        keyboardContainer.addView(divider)
        keyboardContainer.addView(createKeySection(rightKeys))
    }

    /**
     * Called when the user has selected a single key from the keyboard.
     * It performs the action associated with that key.
     */
    private fun handleFinalSelection(key: String) {
        when (key) {
            "⌫" -> { // Backspace
                if (typedNumber.isNotEmpty()) {
                    typedNumber = typedNumber.dropLast(1)
                }
            }
            "Call" -> { // Make the phone call
                makeCall(typedNumber)
                typedNumber = ""
            }
            "End Call" -> { // End the current call
                endCall()
            }
            else -> { // Append the digit to the number
                typedNumber += key
            }
        }
        updateTypedText()
        previousStates.clear()
        displayKeyboard(keyboardLayout) // Reset to the full keyboard
    }

    /**
     * Creates a vertical section (either left or right) of the keyboard.
     * Uses layout weights to ensure all buttons fit on the screen.
     */
    private fun createKeySection(keys: List<String>): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            orientation = LinearLayout.VERTICAL
            keys.forEach { key ->
                val button = Button(context).apply {
                    text = key
                    textSize = 28f
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    background = ContextCompat.getDrawable(context, R.drawable.rounded_button)
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    ).apply { setMargins(16, 8, 16, 8) }
                    layoutParams = params
                }
                addView(button)
            }
        }
    }

    /**
     * Triggered by a right-eye blink. Selects the left half of the keyboard.
     */
    private fun selectLeftKeys() {
        playbackSound(R.raw.type)
        val currentKeys = previousStates.lastOrNull() ?: return
        displayKeyboard(currentKeys.take(currentKeys.size / 2))
    }

    /**
     * Triggered by a left-eye blink. Selects the right half of the keyboard.
     */
    private fun selectRightKeys() {
        playbackSound(R.raw.type)
        val currentKeys = previousStates.lastOrNull() ?: return
        displayKeyboard(currentKeys.drop(currentKeys.size / 2))
    }

    /**
     * Updates the large text display with the currently typed number.
     */
    private fun updateTypedText() {
        if (typedNumber.isEmpty()) {
            selectedText.hint = "Blink to type number..."
            selectedText.text = ""
        } else {
            selectedText.text = typedNumber
        }
    }

    // --- Phone Call Actions ---

    /**
     * Starts a phone call with the given number and requests speakerphone.
     */
    private fun makeCall(number: String) {
        if (number.isNotEmpty()) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(requireContext(), "Call Permission Not Granted", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            // Request that the call starts on speakerphone
            intent.putExtra(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
            startActivity(intent)
        }
    }

    /**
     * Ends the current ongoing phone call.
     * Requires the ANSWER_PHONE_CALLS permission.
     */
    @SuppressLint("MissingPermission")
    private fun endCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecomManager = requireContext().getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                telecomManager.endCall()
                Toast.makeText(requireContext(), "Ending call...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Permission to end calls not granted.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(requireContext(), "Feature not available on this Android version.", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Camera and Gesture Detection ---

    /**
     * Initializes the camera and binds it to the fragment's lifecycle.
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, this::processImageProxy) }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis)
            } catch (exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /**
     * This function is called for every frame from the camera.
     * It converts the image and sends it to the face detector.
     */
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

    /**
     * Your original gesture detection logic. It checks for single-eye winks
     * to make selections and checks for a long two-eye closure to exit.
     */
    private fun processFaces(faces: List<Face>) {
        val face = faces.firstOrNull() ?: return
        val currentTime = System.currentTimeMillis()

        // A cooldown to prevent too many actions at once
        if (currentTime - lastSelectionTime < postSelectionDelay) return

        val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1.0f
        val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1.0f
        val isLeftBlink = leftEyeOpenProb < blinkThreshold
        val isRightBlink = rightEyeOpenProb < blinkThreshold

        // Logic for both eyes closed
        if (isLeftBlink && isRightBlink) {
            if (!isBothEyesClosed) {
                lastEyeCloseTime = currentTime
                isBothEyesClosed = true
            } else {
                val duration = currentTime - lastEyeCloseTime

                // 0.4s → close fragment
                if (duration in 400..500) {
                    playbackSound(R.raw.backbutton)
                    findNavController().popBackStack()
                    isBothEyesClosed = false
                    return
                }

                // 2s → navigate home
                if (duration >= 2000) {
                    playbackSound(R.raw.backbutton)
                    findNavController().navigate(R.id.homeFragment2)
                    isBothEyesClosed = false
                    return
                }
            }
            return
        } else {
            isBothEyesClosed = false
            lastEyeCloseTime = 0
        }

        // Logic for selecting keyboard halves with single-eye winks
        if (isLeftBlink) handleBlink(currentTime, ::selectRightKeys)
        if (isRightBlink) handleBlink(currentTime, ::selectLeftKeys)
    }


    /**
     * A helper to handle the timing of blink detections.
     */
    private fun handleBlink(currentTime: Long, selectionAction: () -> Unit) {
        if (currentTime - lastBlinkTime >= postSelectionDelay) {
            selectionAction()
            lastSelectionTime = currentTime
        }
        lastBlinkTime = currentTime
    }

    /**
     * Plays a sound effect for feedback.
     */
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