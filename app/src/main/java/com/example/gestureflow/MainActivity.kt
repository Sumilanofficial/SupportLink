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
    private lateinit var navController: NavController
    private var binding: ActivityMainBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding?.root)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as NavHostFragment
        navController = navHostFragment.navController

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {

                R.id.homeFragment2 -> binding?.bottomNavigation?.visibility = View.GONE
                R.id.instagramFragment -> binding?.bottomNavigation?.visibility = View.VISIBLE
                R.id.callFragment -> binding?.bottomNavigation?.visibility = View.VISIBLE
                R.id.messageFragment -> binding?.bottomNavigation?.visibility = View.VISIBLE
                else -> binding?.bottomNavigation?.visibility = View.GONE
            }
        }

        binding?.bottomNavigation?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.bottom_home -> navController.navigate(R.id.homeFragment2)
                R.id.bottom_call -> navController.navigate(R.id.callFragment)
                R.id.bottom_instagram -> navController.navigate(R.id.instagramFragment)

            }
            true
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}