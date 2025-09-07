package com.example.gestureflow

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
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
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.gestureflow.databinding.FragmentQuickDialBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QuickDialFragment : Fragment() {

    private var _binding: FragmentQuickDialBinding? = null
    private val binding get() = _binding!!

    // --- Camera and Face Detection ---
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var faceDetector: FaceDetector

    // --- Contacts (options shown on screen) ---
    private val contacts = listOf("Dad (623...)", "Emergency (1299)", "Friend (123...)", "Home")
    private val contactMap = mapOf(
        "Dad (623...)" to "6239062592",
        "Emergency (1299)" to "1299",
        "Friend (123...)" to "12343445",
        "Home" to "3425" // Example number
    )
    private var previousStates: MutableList<List<String>> = mutableListOf()

    // --- Eye Blink Detection Settings ---
    private val postSelectionDelay = 300L   // delay to avoid multiple fast selections
    private val blinkThreshold = 0.1f       // threshold for detecting a single eye wink
    private var lastBlinkTime = 0L
    private var lastSelectionTime = 0L
    private var eyesClosedTime = 0L         // timer for back navigation

    private var mediaPlayer: MediaPlayer? = null

    // Inflate layout using ViewBinding
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuickDialBinding.inflate(inflater, container, false)
        return binding.root
    }

    // Setup things after view is created
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupFaceDetector()       // initialize MLKit face detector
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()             // start camera feed for eye detection
        displayKeyboard(contacts) // show initial list of contacts
    }

    // Clean up resources
    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        faceDetector.close()
        mediaPlayer?.release()
        _binding = null
    }

    // --- Configure ML Kit Face Detector ---
    private fun setupFaceDetector() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
        faceDetector = com.google.mlkit.vision.face.FaceDetection.getClient(options)
    }

    // --- Show contacts on screen as a split keyboard ---
    private fun displayKeyboard(keys: List<String>) {
        binding.keyboardContainerQuickDial.removeAllViews()

        // If only one option left, it means it's selected
        if (keys.size == 1) {
            handleFinalSelection(keys.first())
            return
        }

        // Save current state for backtracking
        previousStates.add(keys)

        // Divide into left & right halves
        val leftKeys = keys.take(keys.size / 2)
        val rightKeys = keys.drop(keys.size / 2)

        // Add buttons for each side + divider
        binding.keyboardContainerQuickDial.addView(createKeySection(leftKeys))
        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(4, LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.DKGRAY)
        }
        binding.keyboardContainerQuickDial.addView(divider)
        binding.keyboardContainerQuickDial.addView(createKeySection(rightKeys))
    }

    // Final action when a single contact is selected
    private fun handleFinalSelection(key: String) {
        val numberToCall = contactMap[key]
        binding.selectedContactText.text = "Calling: $key"
        makeCall(numberToCall)

        // Reset keyboard after 2 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            previousStates.clear()
            binding.selectedContactText.text = "Blink to Select Contact"
            displayKeyboard(contacts)
        }, 2000)
    }

    // Create vertical button section for keys
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

    // Blink left → choose left half
    private fun selectLeftKeys() {
        playbackSound(R.raw.type)
        val currentKeys = previousStates.lastOrNull() ?: return
        displayKeyboard(currentKeys.take(currentKeys.size / 2))
    }

    // Blink right → choose right half
    private fun selectRightKeys() {
        playbackSound(R.raw.type)
        val currentKeys = previousStates.lastOrNull() ?: return
        displayKeyboard(currentKeys.drop(currentKeys.size / 2))
    }

    // --- Make a call ---
    private fun makeCall(phoneNumber: String?) {
        if (phoneNumber.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "Phone number is not set", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), "Call Permission Not Granted", Toast.LENGTH_SHORT).show()
            return
        }
        // Direct call
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")))
    }

    // --- Setup CameraX ---
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(binding.cameraPreviewQuickDial.surfaceProvider) }

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

    // --- Process frames with ML Kit ---
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

    // --- Detect eye blinks & trigger selection ---
    private fun processFaces(faces: List<Face>) {
        val face = faces.firstOrNull() ?: return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSelectionTime < postSelectionDelay) return

        val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1.0f
        val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1.0f

        // --- Back Navigation Logic ---
        // Check if both eyes are closed to navigate back. This takes priority.
        val bothEyesClosedThreshold = 0.3f
        if (leftEyeOpenProb < bothEyesClosedThreshold && rightEyeOpenProb < bothEyesClosedThreshold) {
            if (eyesClosedTime == 0L) {
                // If this is the first frame with closed eyes, start the timer
                eyesClosedTime = currentTime
            } else if (currentTime - eyesClosedTime >= 2000L) { // Trigger after 2 seconds
                playbackSound(R.raw.backbutton)
                // Ensure navigation happens on the main thread
                activity?.runOnUiThread {
                    findNavController().navigate(R.id.homeFragment2)
                }
                eyesClosedTime = 0L // Reset timer to prevent re-triggering
            }
            // IMPORTANT: Do not process single blinks if both eyes are closed
            return
        } else {
            // If one or both eyes are open, reset the back navigation timer
            eyesClosedTime = 0L
        }

        // --- Selection Logic ---
        val isLeftBlink = leftEyeOpenProb < blinkThreshold
        val isRightBlink = rightEyeOpenProb < blinkThreshold

        // Left blink → pick right side
        if (isLeftBlink) handleBlink(currentTime, ::selectRightKeys)

        // Right blink → pick left side
        if (isRightBlink) handleBlink(currentTime, ::selectLeftKeys)
    }


    // Prevent multiple triggers by adding delay
    private fun handleBlink(currentTime: Long, selectionAction: () -> Unit) {
        if (currentTime - lastBlinkTime >= postSelectionDelay) {
            selectionAction()
            lastSelectionTime = currentTime
        }
        lastBlinkTime = currentTime
    }

    // Play sound feedback for selection
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