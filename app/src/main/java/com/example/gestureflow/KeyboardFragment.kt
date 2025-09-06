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

class KeyboardFragment : Fragment() {

    private lateinit var previewView: PreviewView
    private lateinit var selectedText: TextView
    private lateinit var keyboardContainer: ConstraintLayout
    private lateinit var cameraExecutor: ExecutorService
    private var lastSelectedSide: String = ""
    private var mediaPlayer: MediaPlayer? = null
    private val receiverNumber = "6239062592" // Replace with the actual number
    private val colors = listOf(
        R.color.white,  // Replace with actual color resources
        R.color.white,
        R.color.white,
        R.color.white,
        R.color.white,
        R.color.white
    )
    private val keyboardLayout = listOf(
        "A", "B", "C","⌫", "D","Enter", "E", "F", "G", "H", "I", "J", "K", "L", "M",
        "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        "⌫", "Enter"
    )


    private val previousStates: MutableList<List<String>> = mutableListOf()
    private var typedText: String = ""

    private val postSelectionDelay = 300L
    private val blinkThreshold = 0.1f

    private var lastBlinkTime = 0L
    private var blinkCount = 0
    private var lastSelectionTime = 0L

    // For tracking both eyes closed for 4 seconds
    private var lastEyeCloseTime = 0L
    private var isBothEyesClosed = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_keyboard, container, false)
        previewView = view.findViewById(R.id.camera_preview)
        selectedText = view.findViewById(R.id.selectedKey)
        keyboardContainer = view.findViewById(R.id.keyboardContainer)
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
        displayKeyboard(keyboardLayout)
        return view
    }

    private fun displayKeyboard(keys: List<String>) {
        keyboardContainer.removeAllViews()

        if (keys.size == 1) {
            when (keys.first()) {
                "⌫" -> typedText = typedText.dropLast(1)
                "Enter" -> sendTextMessage(typedText) // Send the SMS

                else -> typedText += keys.first()
            }
            selectedText.text = "Message: $typedText"
            previousStates.clear()
            displayKeyboard(keyboardLayout)
            return
        }

        previousStates.add(keys)
        val leftKeys = keys.filterIndexed { index, _ -> index % 2 == 0 }
        val rightKeys = keys.filterIndexed { index, _ -> index % 2 != 0 }
        keyboardContainer.addView(createKeySections(leftKeys, rightKeys))
    }

    // Function to move to the next fragment
    private fun moveToNextFragment() {
        playbackSound()
        val navController = findNavController()
        navController.navigate(R.id.homeFragment2) // Navigate to the Home Fragment
    }

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
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.SEND_SMS),
                    1
                )
            }
        }
    }
    private fun playSelectionSound() {
        mediaPlayer?.release() // Release previous instance if exists
        mediaPlayer = MediaPlayer.create(requireContext(), R.raw.type) // Load sound file
        mediaPlayer?.start() // Play sound
    }
    private fun playbackSound() {
        mediaPlayer?.release() // Release previous instance if exists
        mediaPlayer = MediaPlayer.create(requireContext(), R.raw.backbutton) // Load sound file
        mediaPlayer?.start() // Play sound
    }


    private fun createKeySections(leftKeys: List<String>, rightKeys: List<String>): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL

            addView(createKeySection(leftKeys, ::selectRightKeys))

            // Divider between left and right sections
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

    private fun createKeySection(keys: List<String>, onClick: () -> Unit): LinearLayout {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            orientation = LinearLayout.VERTICAL
            setPadding(16, 20, 16, 16) // Add padding

            keys.forEachIndexed { index, key -> // Using forEachIndexed for better indexing
                val button = Button(context).apply {
                    text = key
                    textSize = 14f
                    setTextColor(android.graphics.Color.BLACK)

                    // Ensure the color index is within bounds
                    val colorRes = colors[index % colors.size]
                    backgroundTintList = ContextCompat.getColorStateList(context, colorRes)
                    background = ContextCompat.getDrawable(context, R.drawable.rounded_button) // Apply rounded design

                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        120// Adjusted button height
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

    private fun processFaces(faces: List<Face>) {
        val face = faces[0]
        val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1.0f
        val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1.0f
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastSelectionTime < postSelectionDelay) return

        Log.d("EyeDetection", "Left Eye: $leftEyeOpenProb, Right Eye: $rightEyeOpenProb")

        // If both eyes are closed simultaneously, ignore the detection
        val isLeftBlink = leftEyeOpenProb < blinkThreshold
        val isRightBlink = rightEyeOpenProb < blinkThreshold

        if (isLeftBlink && isRightBlink) {
            if (!isBothEyesClosed) {
                lastEyeCloseTime = currentTime
                isBothEyesClosed = true
            } else if (currentTime - lastEyeCloseTime >= 500) { // 4 seconds
                moveToNextFragment() // Navigate to the Home Fragment after 4 seconds
            }
            lastBlinkTime = currentTime // Update time to prevent false triggers
            return
        } else {
            isBothEyesClosed = false
            lastEyeCloseTime = 0
        }

        if (isLeftBlink) handleBlink(currentTime, ::selectRightKeys)
        if (isRightBlink) handleBlink(currentTime, ::selectLeftKeys)
    }

    private fun handleBlink(currentTime: Long, selectionAction: () -> Unit) {
        if (currentTime - lastBlinkTime >= postSelectionDelay) {
            selectionAction()
            lastSelectionTime = currentTime
        }
        lastBlinkTime = currentTime
    }

    private fun selectLeftKeys() {
        playSelectionSound()
        // Set a flag that indicates the keys are selected from the left side
        previousStates.lastOrNull()?.let { leftKeys ->
            displayKeyboard(leftKeys.filterIndexed { index, _ -> index % 2 == 0 })
            // Remember the selected side (left in this case)
            lastSelectedSide = "left"
        }
    }

    private fun selectRightKeys() {
        playSelectionSound()
        // Set a flag that indicates the keys are selected from the right side
        previousStates.lastOrNull()?.let { rightKeys ->
            displayKeyboard(rightKeys.filterIndexed { index, _ -> index % 2 != 0 })
            // Remember the selected side (right in this case)
            lastSelectedSide = "right"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
    }
}
