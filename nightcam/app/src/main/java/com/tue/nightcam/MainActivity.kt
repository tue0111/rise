package com.tue.nightcam

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tue.nightcam.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.status.text = "NightCam ${BuildConfig.VERSION_NAME}"
    }
}
