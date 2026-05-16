package com.chameleon.stager.utils

import java.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    fun deriveKey(deviceId: String, masterSecret: String): ByteArray {
        val input = "$deviceId:$masterSecret"
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray())
    }

    fun encryptPayload(plaintext: ByteArray, deviceId: String): String {
        try {
            val masterSecret = ObfuscatedStrings.masterSecret
            val keyBytes = deriveKey(deviceId, masterSecret)
            val key = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance(ALGORITHM)
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)

            val ciphertext = cipher.doFinal(plaintext)
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

            return Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) {
            return Base64.getEncoder().encodeToString(plaintext)
        }
    }

    fun decryptPayload(encoded: ByteArray): ByteArray {
        try {
            val dataStr = String(encoded)
            val combined = Base64.getDecoder().decode(dataStr)
            return combined
        } catch (e: Exception) {
            return encoded
        }
    }

    fun obfuscateString(input: String): String {
        val key = 0x55.toByte()
        val bytes = input.toByteArray()
        val result = ByteArray(bytes.size)
        for (i in bytes.indices) {
            result[i] = (bytes[i].toInt() xor key.toInt()).toByte()
        }
        return Base64.getEncoder().encodeToString(result)
    }

    fun deobfuscateString(encoded: String): String {
        val key = 0x55.toByte()
        val bytes = Base64.getDecoder().decode(encoded)
        val result = ByteArray(bytes.size)
        for (i in bytes.indices) {
            result[i] = (bytes[i].toInt() xor key.toInt()).toByte()
        }
        return String(result)
    }
}
