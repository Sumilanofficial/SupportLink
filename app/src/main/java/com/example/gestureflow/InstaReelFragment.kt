package com.example.gestureflow

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.gestureflow.databinding.FragmentInstaReelBinding // Import ViewBinding

class InstaReelFragment : Fragment() {

    // --- View Binding ---
    // This replaces findViewById and prevents errors
    private var _binding: FragmentInstaReelBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInstaReelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupWebView()

        // --- Set up the button clicks ---
        binding.buttonScrollUp.setOnClickListener {
            scrollReelUp()
        }

        binding.buttonScrollDown.setOnClickListener {
            scrollReelDown()
        }

        binding.buttonLike.setOnClickListener {
            likeCurrentReel()
        }

        binding.buttonClose.setOnClickListener {
            closeReels()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up the binding and WebView to prevent memory leaks
        val webView = binding.webviewReels
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        _binding = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        binding.webviewReels.webViewClient = WebViewClient()
        val webSettings = binding.webviewReels.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        binding.webviewReels.loadUrl("https://www.instagram.com/reels/")
    }

    // --- Action Functions ---

    private fun scrollReelDown() {
        // Simulates pressing 'PageDown' key to go to the next reel
        val script = "document.dispatchEvent(new KeyboardEvent('keydown', {'bubbles': true, 'keyCode': 34}));"
        binding.webviewReels.evaluateJavascript(script, null)
    }

    private fun scrollReelUp() {
        // Simulates pressing 'PageUp' key to go to the previous reel
        val script = "document.dispatchEvent(new KeyboardEvent('keydown', {'bubbles': true, 'keyCode': 33}));"
        binding.webviewReels.evaluateJavascript(script, null)
    }

    private fun likeCurrentReel() {
        // Simulates a double-click in the middle of the screen to "Like" a reel
        val script = "var x = window.innerWidth / 2; var y = window.innerHeight / 2;" +
                "var element = document.elementFromPoint(x, y);" +
                "var event = new MouseEvent('dblclick', { bubbles: true, cancelable: true });" +
                "element.dispatchEvent(event);"
        binding.webviewReels.evaluateJavascript(script, null)
    }

    private fun closeReels() {
        // Navigates back to the previous screen
        findNavController().navigate(R.id.instaFeedFragment)
    }
}