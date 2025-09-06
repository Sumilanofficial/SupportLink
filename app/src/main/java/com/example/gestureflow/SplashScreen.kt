package com.example.gestureflow

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SplashScreen : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enables full edge-to-edge content display (status bar & nav bar transparent)
        enableEdgeToEdge()

        // Set the layout for the splash screen
        setContentView(R.layout.activity_splash_screen)

        // Adjust padding for system bars (status + navigation bar) to prevent overlap
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Use a Handler to delay the transition for 2 seconds (2000 ms)
        Handler(Looper.getMainLooper()).postDelayed({

            // Create an intent to move from SplashScreen → MainActivity
            val intent = Intent(this, MainActivity::class.java)

            // Apply custom fade-in and fade-out animation during activity transition
            val options = ActivityOptionsCompat.makeCustomAnimation(
                this,
                R.anim.fade_in,
                R.anim.fade_out
            )

            // Start MainActivity with the transition animation
            startActivity(intent, options.toBundle())

            // Finish SplashScreen so user cannot return to it by pressing back
            finish()

        }, 2000) // Delay = 2 seconds
    }
}
