
package com.example.gestureflow
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class InstaReelFragment : Fragment() {

    // UI Elements
    private lateinit var previewView: PreviewView
    private lateinit var messageText: TextView
    private lateinit var webView: WebView

    // Camera Executor for background processing
    private lateinit var cameraExecutor: ExecutorService

    // Variables for eye blink detection
    private var lastEyeCloseTime: Long = 0
    private var lastBlinkTime: Long = 0
    private var blinkCount = 0
    private var isInstagramOpen = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_insta_reel, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize UI Elements
        previewView = view.findViewById(R.id.camera_preview)
        messageText = view.findViewById(R.id.text_message)
        webView = view.findViewById(R.id.webview)

        // Set up WebView for Instagram
        setupWebView()

        // Initialize Camera
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
    }

    // Set up WebView for Instagram
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.webViewClient = WebViewClient()
        webView.settings.javaScriptEnabled = true
    }

    // Start the Camera and Set Up Face Detection
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

    // Process Image from Camera
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

    // Process Detected Faces
    private fun processFaces(faces: List<Face>) {
        if (faces.isNotEmpty()) {
            val face = faces[0]

            // Get Eye Open Probabilities
            val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1.0f
            val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1.0f

            val isLeftEyeClosed = leftEyeOpenProb < 0.2 && rightEyeOpenProb > 0.8
            val isBothEyesClosed = leftEyeOpenProb < 0.2 && rightEyeOpenProb < 0.2
            val isBlink = leftEyeOpenProb < 0.2 && rightEyeOpenProb < 0.2

            val currentTime = System.currentTimeMillis()

            // If Left Eye is Closed, Open Instagram

            openInstagram()


            // If Both Eyes are Closed for 4 Seconds, Close Instagram
            if (isBothEyesClosed) {
                if (lastEyeCloseTime == 0L) {
                    lastEyeCloseTime = currentTime
                } else if (currentTime - lastEyeCloseTime >= 500) {
                    closeInstagram()
                }
            } else {
                lastEyeCloseTime = 0
            }

            // Detect Double Blink to Scroll
            if (isBlink) {
                if (currentTime - lastBlinkTime < 600) {
                    blinkCount++
                    if (blinkCount == 2) {
                        if (isInstagramOpen) {
                            scrollInstagram()
                        }
                        blinkCount = 0
                    }
                } else {
                    blinkCount = 1
                }
                lastBlinkTime = currentTime
            }

            // Update UI Message
            requireActivity().runOnUiThread {
                messageText.text = when {
                    isLeftEyeClosed -> "Left Eye Blinked: Opening Instagram..."
                    isBothEyesClosed -> "Both Eyes Closed: Closing Instagram..."
                    isBlink -> "Double Blink: Scrolling..."
                    else -> "Face Detected!"
                }
            }
        } else {
            requireActivity().runOnUiThread {
                messageText.text = "No Face Detected"
            }
        }
    }

    // Open Instagram
    private fun openInstagram() {
        if (!isInstagramOpen) {
            requireActivity().runOnUiThread {
                webView.visibility = View.VISIBLE
                previewView.visibility = View.GONE
                webView.loadUrl("https://news.google.com")
                isInstagramOpen = true
            }
        }
    }

    // Close Instagram
    private fun closeInstagram() {
        findNavController().navigate(R.id.homeFragment2)
    }

    // Smooth Scroll Instagram
    private fun scrollInstagram() {
        requireActivity().runOnUiThread {
            webView.evaluateJavascript("window.scrollBy({ top: 500, behavior: 'smooth' });", null)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
    }
}
