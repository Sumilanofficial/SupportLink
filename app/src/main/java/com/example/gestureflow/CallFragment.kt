package com.example.gestureflow

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
class CallFragment : Fragment() {

    private lateinit var previewView: PreviewView
    private lateinit var selectedText: TextView
    private lateinit var keyboardContainer: ConstraintLayout
    private lateinit var cameraExecutor: ExecutorService
    private var lastSelectedSide: String = ""

    private var receiverNumber: String? = null

    private val keyboardLayout = listOf(
        "6239062592",
        "1299",
        "12343445",
        "7657814570"
    )

    private var mediaPlayer: MediaPlayer? = null

    private val colors = listOf(
        R.color.white,  // Replace with actual color resources
        R.color.white,
        R.color.white,
        R.color.white,
        R.color.white,
        R.color.white
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
        val view = inflater.inflate(R.layout.fragment_fixed_message, container, false)
        previewView = view.findViewById(R.id.camera_preview)
        selectedText = view.findViewById(R.id.selectedKey)
        keyboardContainer = view.findViewById(R.id.keyboardContainer)
        cameraExecutor = Executors.newSingleThreadExecutor()
        receiverNumber = arguments?.getString("phoneNumber")
        arguments?.putString("phoneNumber", "")
        startCamera()
        displayKeyboard(keyboardLayout)
        return view
    }
    private fun displayKeyboard(keys: List<String>) {
        keyboardContainer.removeAllViews()

        if (keys.size == 1) {
            when (keys.first()) {
                "⌫" -> typedText = typedText.dropLast(1)  // Handle backspace
                "Enter" -> {
                    sendTextMessage(typedText) // Send the SMS when Enter is selected
                    typedText = "" // Clear typedText after sending
                }
                else -> {
                    typedText += keys.first() // Append the selected key to the typed message
                    makeCall(typedText) // Automatically send the message when final selection is made
                    typedText = "" // Reset typedText
                }
            }
            selectedText.text = "Selected: $typedText"
            previousStates.clear()
            displayKeyboard(keyboardLayout)
            return
        }

        previousStates.add(keys)
        val leftKeys = keys.filterIndexed { index, _ -> index % 2 == 0 }
        val rightKeys = keys.filterIndexed { index, _ -> index % 2 != 0 }
        keyboardContainer.addView(createKeySections(leftKeys, rightKeys))
    }

    private fun makeCall(number: String) {
        if (number.isNotEmpty()) {
            val intent = Intent(Intent.ACTION_CALL)
            intent.data = Uri.parse("tel:$number")
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED) {
                startActivity(intent)
            } else {
                Toast.makeText(requireContext(), "Call permission required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Function to send the typed message via SMS
    private fun sendTextMessage(message: String) {
        if (message.isNotEmpty()) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED) {
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

    // Function to start the camera and detect eye gestures
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

    // Function to process the image for detecting eye gestures
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

    // Function to process the detected faces and track blinks
    @RequiresApi(Build.VERSION_CODES.P)
    private fun processFaces(faces: List<Face>) {
        val face = faces[0]
        val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1.0f
        val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1.0f
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastSelectionTime < postSelectionDelay) return

        Log.d("EyeDetection", "Left Eye: $leftEyeOpenProb, Right Eye: $rightEyeOpenProb")

        val isLeftBlink = leftEyeOpenProb < blinkThreshold
        val isRightBlink = rightEyeOpenProb < blinkThreshold

        if (isLeftBlink && isRightBlink) {
            if (!isBothEyesClosed) {
                lastEyeCloseTime = currentTime
                isBothEyesClosed = true
            } else if (currentTime - lastEyeCloseTime >= 500) { // 4 seconds
           playbackSound()
                endCall()
                Log.d("EyeDetection", "Both eyes closed for 5 seconds, navigating to Home Fragment")
                findNavController().navigate(R.id.homeFragment2)
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
    @RequiresApi(Build.VERSION_CODES.P)
    private fun endCall() {
        val telecomManager = requireContext().getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ANSWER_PHONE_CALLS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        telecomManager.endCall()
    }

    // Function to handle the blink and select keys from the appropriate side
    private fun handleBlink(currentTime: Long, selectionAction: () -> Unit) {
        if (currentTime - lastBlinkTime >= postSelectionDelay) {
            selectionAction()
            lastSelectionTime = currentTime
        }
        lastBlinkTime = currentTime
    }

    // Function to select keys from the left side
    private fun selectLeftKeys() {
        playSelectionSound()
        previousStates.lastOrNull()?.let { leftKeys ->
            displayKeyboard(leftKeys.filterIndexed { index, _ -> index % 2 == 0 })
            lastSelectedSide = "left"
        }
    }

    // Function to select keys from the right side
    private fun selectRightKeys() {
        playSelectionSound()
        previousStates.lastOrNull()?.let { rightKeys ->
            displayKeyboard(rightKeys.filterIndexed { index, _ -> index % 2 != 0 })
            lastSelectedSide = "right"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
    }
}
