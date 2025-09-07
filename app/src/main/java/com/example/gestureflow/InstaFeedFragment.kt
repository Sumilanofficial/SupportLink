package com.example.gestureflow

import android.annotation.SuppressLint
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
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

class InstaFeedFragment : Fragment() {

    // --- Threshold Configurations for gestures ---
    companion object {
        // Decreased sensitivity: requires a more pronounced head movement.
        private const val HEAD_TURN_RIGHT_THRESHOLD = -16f   // Head yaw threshold (look right)
        private const val HEAD_TILT_UP_THRESHOLD = 12f       // Head pitch threshold (look up)
        private const val HEAD_TILT_DOWN_THRESHOLD = -12f    // Head pitch threshold (look down)

        private const val EYE_CLOSED_THRESHOLD = 0.4f        // Eye closure probability
        private const val CLOSE_EYES_DURATION_MS = 700L      // Duration (ms) to confirm exit
        private const val GESTURE_CONFIRMATION_FRAMES = 3    // Frames to confirm gesture
        private const val ACTION_COOLDOWN_MS = 1500L         // Cooldown (ms) between actions
    }

    // --- UI elements ---
    private lateinit var previewView: PreviewView
    private lateinit var messageText: TextView
    private lateinit var webView: WebView
    private var mediaPlayer: MediaPlayer? = null

    // --- Camera & face detection ---
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var faceDetector: FaceDetector

    // --- State management ---
    private var isInstagramOpen = false
    private var lastActionTimestamp = 0L

    // Counters for stabilizing gesture detection
    private var lookRightFrames = 0
    private var lookUpFrames = 0
    private var lookDownFrames = 0
    private var eyesClosedTime = 0L // Timer for the "close eyes" gesture

    // --- Lifecycle ---
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_insta_feed, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeUI(view)       // Setup UI views
        setupWebView()           // Setup Instagram WebView
        setupFaceDetector()      // Setup ML Kit face detector
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()            // Start CameraX with analysis
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()        // Pause WebView when fragment not visible
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()       // Resume WebView
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()        // Destroy WebView
        cameraExecutor.shutdown()
        faceDetector.close()
        mediaPlayer?.release()
    }

    // --- UI Initialization ---
    private fun initializeUI(view: View) {
        previewView = view.findViewById(R.id.camera_preview)
        messageText = view.findViewById(R.id.text_message)
        webView = view.findViewById(R.id.webview)
    }

    // Configure ML Kit face detector
    private fun setupFaceDetector() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
        faceDetector = com.google.mlkit.vision.face.FaceDetection.getClient(options)
    }

    // Setup WebView for Instagram
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        webView.webViewClient = WebViewClient()
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
    }

    // --- Camera + Frame Analysis ---
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview stream
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Analyzer for ML Kit
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, this::processImageProxy) }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, imageAnalysis)
            } catch (exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // Convert camera frame into InputImage and run face detection
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (view == null) { imageProxy.close(); return }
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            faceDetector.process(image)
                .addOnSuccessListener { faces -> processFaces(faces) } // Process detected faces
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    // --- Gesture Detection Logic ---
    private fun processFaces(faces: List<Face>) {
        if (faces.isEmpty() || view == null) {
            updateUIMessage("No Face Detected")
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActionTimestamp < ACTION_COOLDOWN_MS) return // Prevent too frequent actions

        val face = faces[0]
        val headYaw = face.headEulerAngleY   // Left-right head movement
        val headPitch = face.headEulerAngleX // Up-down head movement

        // Count frames for tilt/turn gestures
        if (headYaw < HEAD_TURN_RIGHT_THRESHOLD) lookRightFrames++ else lookRightFrames = 0
        if (headPitch > HEAD_TILT_UP_THRESHOLD) lookUpFrames++ else lookUpFrames = 0
        if (headPitch < HEAD_TILT_DOWN_THRESHOLD) lookDownFrames++ else lookDownFrames = 0

        // Manage timer for the "close eyes to exit" gesture
        val areEyesClosed = (face.leftEyeOpenProbability ?: 1f) < EYE_CLOSED_THRESHOLD &&
                (face.rightEyeOpenProbability ?: 1f) < EYE_CLOSED_THRESHOLD

        if (areEyesClosed) {
            if (eyesClosedTime == 0L) {
                // Start the timer when eyes are first detected as closed
                eyesClosedTime = currentTime
            }
        } else {
            // If eyes are open, reset the timer
            eyesClosedTime = 0L
        }

        // Helper text for UI
        var gestureMessage = "Look Right to Open"
        if (isInstagramOpen) gestureMessage = "Tilt Head to Scroll\nClose Eyes (0.7s) to Exit"

        // Gesture triggers
        when {
            !isInstagramOpen && lookRightFrames >= GESTURE_CONFIRMATION_FRAMES -> {
                openInstagram()     // Open IG on right look
                resetGesture(currentTime)
            }
            isInstagramOpen && eyesClosedTime != 0L && (currentTime - eyesClosedTime >= CLOSE_EYES_DURATION_MS) -> {
                playbackSound()     // Play exit sound
                closeInstagram()    // Exit IG
                resetGesture(currentTime)
            }
            isInstagramOpen && lookUpFrames >= GESTURE_CONFIRMATION_FRAMES -> {
                scrollInstagramDown() // Scroll down
                resetGesture(currentTime)
            }
            isInstagramOpen && lookDownFrames >= GESTURE_CONFIRMATION_FRAMES -> {
                scrollInstagramUp()   // Scroll up
                resetGesture(currentTime)
            }
        }

        updateUIMessage(gestureMessage)
    }

    // Reset counters and update timestamp
    private fun resetGesture(timestamp: Long) {
        lastActionTimestamp = timestamp
        lookRightFrames = 0
        lookUpFrames = 0
        lookDownFrames = 0
        eyesClosedTime = 0L // Reset the eye-closure timer after any action
    }

    // --- Actions ---
    private fun openInstagram() {
        if (!isInstagramOpen && view != null) {
            isInstagramOpen = true
            requireActivity().runOnUiThread {
                webView.visibility = View.VISIBLE
                messageText.visibility = View.VISIBLE
                webView.loadUrl("https://www.instagram.com")
            }
        }
    }

    private fun closeInstagram() {
        if (view != null) findNavController().navigate(R.id.homeFragment2)
    }

    private fun scrollInstagramDown() {
        webView.evaluateJavascript("window.scrollBy({ top: 700, behavior: 'smooth' });", null)
    }

    private fun scrollInstagramUp() {
        webView.evaluateJavascript("window.scrollBy({ top: -700, behavior: 'smooth' });", null)
    }

    // --- Helpers ---
    private fun updateUIMessage(message: String) {
        if (view != null && messageText.text != message) {
            requireActivity().runOnUiThread { messageText.text = message }
        }
    }

    private fun playbackSound() {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(requireContext(), R.raw.backbutton)?.apply {
                setOnCompletionListener { it.release() }
                start()
            }
        } catch (e: Exception) {
            Log.e("MediaPlayer", "Error playing sound", e)
        }
    }
}