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

    // --- Configuration Constants ---
    companion object {
        private const val EYE_CLOSED_THRESHOLD = 0.4f // Lower for more sensitivity
        private const val GESTURE_CONFIRMATION_FRAMES = 5 // Require 5 consecutive frames to confirm a gesture
        private const val DOUBLE_BLINK_TIME_MS = 600 // Time in ms to detect a double blink
    }

    // --- UI Elements ---
    private lateinit var previewView: PreviewView
    private lateinit var messageText: TextView
    private lateinit var webView: WebView
    private var mediaPlayer: MediaPlayer? = null

    // --- Asynchronous Processing ---
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var faceDetector: FaceDetector

    // --- State Management ---
    private var isInstagramOpen = false
    private var leftEyeClosedFrames = 0
    private var rightEyeClosedFrames = 0
    private var bothEyesClosedFrames = 0
    private var lastBlinkTime: Long = 0
    private var blinkCount = 0

    // --- Fragment Lifecycle Methods ---

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_insta_feed, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeUI(view)
        setupWebView()
        setupFaceDetector()
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        cameraExecutor.shutdown()
        faceDetector.close()
    }

    // --- Initialization ---

    private fun initializeUI(view: View) {
        previewView = view.findViewById(R.id.camera_preview)
        messageText = view.findViewById(R.id.text_message)
        webView = view.findViewById(R.id.webview)
    }

    private fun setupFaceDetector() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
        faceDetector = com.google.mlkit.vision.face.FaceDetection.getClient(options)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        webView.webViewClient = WebViewClient()
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
    }

    // --- Camera and Face Detection ---

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
            } catch (exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && view != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            faceDetector.process(image)
                .addOnSuccessListener { faces ->
                    processFaces(faces)
                    imageProxy.close()
                }
                .addOnFailureListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    // --- Gesture Logic ---

    private fun processFaces(faces: List<Face>) {
        if (faces.isEmpty() || view == null) {
            updateUIMessage("No Face Detected")
            return
        }

        val face = faces[0]
        val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1.0f
        val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1.0f

        val isLeftEyeClosed = leftEyeOpenProb < EYE_CLOSED_THRESHOLD
        val isRightEyeClosed = rightEyeOpenProb < EYE_CLOSED_THRESHOLD

        // --- State Counters ---
        if (isLeftEyeClosed && !isRightEyeClosed) leftEyeClosedFrames++ else leftEyeClosedFrames = 0
        if (isLeftEyeClosed && isRightEyeClosed) bothEyesClosedFrames++ else bothEyesClosedFrames = 0

        // --- Gesture Triggers ---
        if (leftEyeClosedFrames >= GESTURE_CONFIRMATION_FRAMES) {
            openInstagram()
            leftEyeClosedFrames = 0 // Reset after action
        }

        if (bothEyesClosedFrames >= GESTURE_CONFIRMATION_FRAMES) {
            handleBothEyesClosed()
            bothEyesClosedFrames = 0 // Reset after action
        }

        detectDoubleBlink(isLeftEyeClosed && isRightEyeClosed)
        updateUIMessage("Face Detected")
    }

    private fun handleBothEyesClosed() {
        if (isInstagramOpen) {
            playbackSound()
            closeInstagram()
        }
    }

    private fun detectDoubleBlink(isBlinking: Boolean) {
        val currentTime = System.currentTimeMillis()
        if (isBlinking) {
            if (currentTime - lastBlinkTime > DOUBLE_BLINK_TIME_MS) {
                blinkCount = 1
            } else {
                blinkCount++
            }
            lastBlinkTime = currentTime
        }

        if (blinkCount >= 2) {
            scrollInstagram()
            blinkCount = 0
        }
    }

    // --- UI and Navigation Actions ---

    private fun openInstagram() {
        if (!isInstagramOpen && view != null) {
            isInstagramOpen = true
            requireActivity().runOnUiThread {
                webView.visibility = View.VISIBLE
                previewView.visibility = View.GONE
                webView.loadUrl("https://www.instagram.com")
            }
        }
    }

    private fun closeInstagram() {
        if (view != null) {
            findNavController().navigate(R.id.homeFragment2)
        }
    }

    private fun scrollInstagram() {
        if (isInstagramOpen && view != null) {
            requireActivity().runOnUiThread {
                webView.evaluateJavascript("window.scrollBy({ top: 500, behavior: 'smooth' });", null)
            }
        }
    }

    private fun updateUIMessage(message: String) {
        if (view != null) {
            requireActivity().runOnUiThread {
                messageText.text = message
            }
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