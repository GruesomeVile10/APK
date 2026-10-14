package com.example.shizukumonitor

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import java.util.Locale

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayTextView: TextView
    private var metricsService: IMetricsService? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, MetricsUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("metrics_daemon")
            .debuggable(false)
            .version(1)
    }

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                metricsService = IMetricsService.Stub.asInterface(binder)
                startPollingLoop()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            metricsService = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        setupFloatingOverlay()
        Shizuku.bindUserService(userServiceArgs, shizukuConnection)
    }

    private fun startForegroundNotification() {
        val channelId = "overlay_perf_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Performance Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Shizuku Monitor Active")
            .setContentText("Displaying real-time CPU, GPU, and FPS overlay")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1001, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        overlayTextView = TextView(this).apply {
            text = "Connecting to Shizuku..."
            setTextColor(Color.GREEN)
            textSize = 12f
            setBackgroundColor(Color.argb(180, 15, 15, 15))
            setPadding(24, 16, 24, 16)
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = 120
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        overlayTextView.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayTextView, layoutParams)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayTextView, layoutParams)
    }

    private fun startPollingLoop() {
        serviceScope.launch {
            while (isActive) {
                val stats = withContext(Dispatchers.IO) {
                    try {
                        metricsService?.metrics
                    } catch (_: Exception) {
                        null
                    }
                }

                if (stats != null && stats.size == 3) {
                    val cpuText = String.format(Locale.US, "%4.1f%%", stats[0])
                    val gpuText = if (stats[1] >= 0f) String.format(Locale.US, "%4.1f%%", stats[1]) else "N/A"
                    val fpsText = String.format(Locale.US, "%3.0f", stats[2])
                    overlayTextView.text = "FPS: $fpsText\nCPU: $cpuText\nGPU: $gpuText"
                }
                delay(750L)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        try {
            Shizuku.unbindUserService(userServiceArgs, shizukuConnection, true)
        } catch (_: Exception) {}
        if (::overlayTextView.isInitialized) {
            windowManager.removeView(overlayTextView)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
