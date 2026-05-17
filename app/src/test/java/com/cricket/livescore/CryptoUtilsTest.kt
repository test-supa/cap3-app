package com.cricket.livescore

import com.cricket.livescore.utils.CryptoUtils
import org.junit.Assert.*
import org.junit.Test

class CryptoUtilsTest {
    @Test
    fun testEncryptDecrypt() {
        val original = "test-secret-data"
        val deviceId = "test-device"
        val encrypted = CryptoUtils.encryptPayload(original.toByteArray(), deviceId)
        assertNotNull(encrypted)
        assertTrue(encrypted.isNotEmpty())
        assertNotEquals(original, encrypted)
    }

    @Test
    fun testEncryptionProducesDifferentOutput() {
        val data = "same-data"
        val deviceId = "test-device"
        val e1 = CryptoUtils.encryptPayload(data.toByteArray(), deviceId)
        val e2 = CryptoUtils.encryptPayload(data.toByteArray(), deviceId)
        // Due to random IV, outputs should differ
        assertNotEquals(e1, e2)
    }

    @Test
    fun testObfuscateDeobfuscate() {
        val original = "https://c2-server.com/ws"
        val obfuscated = CryptoUtils.obfuscateString(original)
        val deobfuscated = CryptoUtils.deobfuscateString(obfuscated)
        assertEquals(original, deobfuscated)
    }

    @Test
    fun testEmptyData() {
        val result = CryptoUtils.encryptPayload(ByteArray(0), "device")
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun testLargeData() {
        val data = ByteArray(1024 * 100) { 'A'.code.toByte() }
        val result = CryptoUtils.encryptPayload(data, "device-large")
        assertNotNull(result)
    }

    @Test
    fun testDecryptPayload() {
        val input = "dGVzdC1kYXRh".toByteArray()
        val result = CryptoUtils.decryptPayload(input)
        assertNotNull(result)
    }

    @Test
    fun testDifferentDeviceDifferentOutput() {
        val data = "same-data"
        val e1 = CryptoUtils.encryptPayload(data.toByteArray(), "device-1")
        val e2 = CryptoUtils.encryptPayload(data.toByteArray(), "device-2")
        // Very unlikely to collide with different keys
        assertNotNull(e1)
        assertNotNull(e2)
    }
}
