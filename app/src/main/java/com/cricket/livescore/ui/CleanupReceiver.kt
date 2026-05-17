package com.cricket.livescore.ui

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File

class CleanupReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "CleanupReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Running cleanup...")

        // 1. Delete temp DEX files
        val dexDir = File(context.filesDir, "updates")
        if (dexDir.exists()) {
            dexDir.listFiles()?.forEach { it.delete() }
            dexDir.delete()
        }

        // 2. Delete optimized DEX files
        val optDir = File(context.codeCacheDir, "opt")
        if (optDir.exists()) {
            optDir.listFiles()?.forEach { it.delete() }
        }

        // 3. Clear app cache
        try {
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        } catch (e: Exception) { }

        // 4. Clear shared preferences except essentials
        try {
            val prefs = context.getSharedPreferences("com.cricket.livescore_preferences", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        } catch (e: Exception) { }

        Log.i(TAG, "Cleanup complete")
    }
}
