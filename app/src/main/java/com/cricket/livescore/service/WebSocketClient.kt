package com.cricket.livescore.service

import android.util.Log
import com.cricket.livescore.utils.ObfuscatedStrings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class WebSocketClient(private val url: String) {
    companion object {
        const val TAG = "WSClient"
        private const val CONNECT_TIMEOUT_MS = 10000
    }

    private var socket: Socket? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private var isConnected = false
    private var onMessageCallback: ((String) -> Unit)? = null
    private var onConnectedCallback: (() -> Unit)? = null
    private var onFailureCallback: ((Exception) -> Unit)? = null

    fun connect(onMessage: (String) -> Unit, onConnected: () -> Unit, onFailure: (Exception) -> Unit = {}) {
        onMessageCallback = onMessage
        onConnectedCallback = onConnected
        onFailureCallback = onFailure
        Thread { doConnect() }.start()
    }

    private fun doConnect() {
        try {
            val isTls = url.startsWith("wss://")
            val host = url.replace("ws://", "").replace("wss://", "")
                .substringBefore("/").substringBefore(":")
            val portStr = url.substringAfter(":").substringAfter("//")
                .substringBefore("/").substringAfter(":", "")
            val port = portStr.toIntOrNull() ?: if (isTls) 443 else 80
            val path = ObfuscatedStrings.wsPath

            socket = if (isTls) {
                createTlsSocket(host, port)
            } else {
                Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
            }

            socket?.let { sock ->
                sock.soTimeout = 30000
                writer = OutputStreamWriter(sock.getOutputStream(), "UTF-8")
                reader = BufferedReader(InputStreamReader(sock.getInputStream(), "UTF-8"))

                performHandshake(host, port, path)
                isConnected = true
                onConnectedCallback?.invoke()

                listenForMessages()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            isConnected = false
            onFailureCallback?.invoke(e)
        }
    }

    private fun createTlsSocket(host: String, port: Int): Socket {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        return sslContext.socketFactory.createSocket(plain, host, port, true)
    }

    private fun performHandshake(host: String, port: Int, path: String) {
        val key = generateWebSocketKey()
        val handshake = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: $host:$port\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }

        writer?.write(handshake)
        writer?.flush()

        // Read HTTP response
        var responseLine = reader?.readLine()
        val responseCode = responseLine?.substringAfter(" ")?.substringBefore(" ")
        if (responseCode != "101") {
            throw Exception("WebSocket handshake failed: $responseLine")
        }

        // Read remaining headers
        while (reader?.readLine()?.isNotEmpty() == true) { }
    }

    private fun listenForMessages() {
        try {
            val buffer = ByteArray(1024 * 64)
            while (isConnected) {
                val firstByte = socket?.getInputStream()?.read() ?: break
                if (firstByte == -1) break

                val secondByte = socket?.getInputStream()?.read() ?: break
                val opcode = firstByte and 0x0F
                val masked = (secondByte and 0x80) != 0
                var payloadLength = (secondByte and 0x7F).toLong()

                when {
                    payloadLength == 126L -> {
                        payloadLength = readShort()
                    }
                    payloadLength == 127L -> {
                        payloadLength = readLong()
                    }
                }

                // Read mask key if present
                val maskKey = if (masked) {
                    ByteArray(4).also { socket?.getInputStream()?.read(it) }
                } else null

                // Read payload
                var read = 0
                val payload = ByteArray(payloadLength.toInt())
                while (read < payloadLength) {
                    val n = socket?.getInputStream()?.read(payload, read, payload.size - read) ?: -1
                    if (n == -1) break
                    read += n
                }

                // Unmask
                if (maskKey != null) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                    }
                }

                when (opcode) {
                    0x1 -> { // Text frame
                        val message = String(payload)
                        onMessageCallback?.invoke(message)
                    }
                    0x8 -> { // Close frame
                        isConnected = false
                    }
                    0x9 -> { // Ping
                        sendPong()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Listen error", e)
        } finally {
            isConnected = false
        }
    }

    fun send(message: String) {
        try {
            val payload = message.toByteArray(Charsets.UTF_8)
            val frame = createFrame(0x1, payload)
            socket?.getOutputStream()?.write(frame)
            socket?.getOutputStream()?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
        }
    }

    private fun createFrame(opcode: Int, payload: ByteArray): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        buffer.write(0x80 or opcode)

        when {
            payload.size < 126 -> {
                buffer.write(payload.size)
            }
            payload.size < 65536 -> {
                buffer.write(126)
                buffer.write((payload.size shr 8) and 0xFF)
                buffer.write(payload.size and 0xFF)
            }
            else -> {
                buffer.write(127)
                for (i in 7 downTo 0) {
                    buffer.write(((payload.size.toLong() shr (i * 8)) and 0xFF).toInt())
                }
            }
        }

        buffer.write(payload)
        return buffer.toByteArray()
    }

    private fun sendPong() {
        try {
            socket?.getOutputStream()?.write(byteArrayOf(0x8A.toByte(), 0x00))
            socket?.getOutputStream()?.flush()
        } catch (e: Exception) { }
    }

    private fun readShort(): Long {
        val b1 = socket?.getInputStream()?.read() ?: return 0
        val b2 = socket?.getInputStream()?.read() ?: return 0
        return ((b1 shl 8) or b2).toLong()
    }

    private fun readLong(): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (socket?.getInputStream()?.read()?.toLong() ?: 0)
        }
        return result
    }

    private fun generateWebSocketKey(): String {
        val random = ByteArray(16)
        SecureRandom().nextBytes(random)
        return java.util.Base64.getEncoder().encodeToString(random)
    }

    fun disconnect() {
        isConnected = false
        try { sendCloseFrame() } catch (_: Exception) { }
        try { socket?.close() } catch (_: Exception) { }
        try { writer?.close() } catch (_: Exception) { }
        try { reader?.close() } catch (_: Exception) { }
    }

    private fun sendCloseFrame() {
        socket?.getOutputStream()?.write(byteArrayOf(0x88.toByte(), 0x00))
        socket?.getOutputStream()?.flush()
    }
}
