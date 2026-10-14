package com.example.shizukumonitor

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private val shizukuPermissionCode = 101

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == shizukuPermissionCode && grantResult == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Shizuku Permission Granted!", Toast.LENGTH_SHORT).show()
            checkAndStartOverlay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        val startButton = Button(this).apply {
            text = "Start Performance Overlay"
            setOnClickListener { checkAndStartOverlay() }
        }
        val stopButton = Button(this).apply {
            text = "Stop Overlay"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            addView(startButton)
            addView(stopButton)
        }
        setContentView(layout)
    }

    private fun checkAndStartOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Grant Overlay Permission first", Toast.LENGTH_LONG).show()
            return
        }

        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku is not running! Start Shizuku first.", Toast.LENGTH_LONG).show()
            return
        }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(shizukuPermissionCode)
            return
        }

        val serviceIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }
}
