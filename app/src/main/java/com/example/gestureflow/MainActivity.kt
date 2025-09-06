package com.example.gestureflow

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.example.gestureflow.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    // Navigation controller to handle fragment navigation
    private lateinit var navController: NavController

    // View binding for accessing layout views
    private var binding: ActivityMainBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable full edge-to-edge layout (status bar + nav bar handling)
        enableEdgeToEdge()

        // Inflate the layout using view binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding?.root)

        // Find the NavHostFragment (the container holding our fragments)
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as NavHostFragment
        navController = navHostFragment.navController

        // Apply system bar insets (status bar, navigation bar) to the root layout
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Listen for navigation changes to control bottom navigation visibility
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                // Hide bottom navigation in home fragment
                R.id.homeFragment2 -> binding?.bottomNavigation?.visibility = View.GONE

                // Show bottom navigation in these fragments
                R.id.instagramFragment -> binding?.bottomNavigation?.visibility = View.VISIBLE
                R.id.callFragment -> binding?.bottomNavigation?.visibility = View.VISIBLE
                R.id.messageFragment -> binding?.bottomNavigation?.visibility = View.VISIBLE

                // Default: hide bottom navigation
                else -> binding?.bottomNavigation?.visibility = View.GONE
            }
        }

        // Handle bottom navigation item clicks
        binding?.bottomNavigation?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                // Navigate to home fragment
                R.id.bottom_home -> navController.navigate(R.id.homeFragment2)

                // Navigate to call fragment
                R.id.bottom_call -> navController.navigate(R.id.callFragment)

                // Navigate to Instagram fragment
                R.id.bottom_instagram -> navController.navigate(R.id.instagramFragment)
            }
            true
        }
    }

    // Handle the up button (back navigation in the action bar)
    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
