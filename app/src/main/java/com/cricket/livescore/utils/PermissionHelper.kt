package com.cricket.livescore.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class PermissionHelper {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001

        // Runtime permissions only (MANAGE_EXTERNAL_STORAGE needs Settings intent)
        val allPermissions: List<String> by lazy {
            buildList {
                add(Manifest.permission.RECEIVE_SMS)
                add(Manifest.permission.READ_SMS)
                add(Manifest.permission.READ_CALL_LOG)
                add(Manifest.permission.READ_CONTACTS)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        fun hasStoragePermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.os.Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAllPermissions(context: Context): Boolean {
        return allPermissions.all { hasPermission(context, it) }
    }

    fun requestPermissions(activity: Activity) {
        val needed = allPermissions.filter { !hasPermission(activity, it) }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(activity, Manifest.permission.POST_NOTIFICATIONS)) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_CODE + 1
                )
            }
        }
    }
}
