package com.pocketcctv.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.pocketcctv.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.cardCamera.setOnClickListener { startActivity(Intent(this, CameraActivity::class.java)) }
        b.cardMonitor.setOnClickListener { startActivity(Intent(this, MonitorActivity::class.java)) }
    }
}
