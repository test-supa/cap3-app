package com.chameleon.stager

import com.chameleon.stager.utils.PermissionHelper
import org.junit.Assert.*
import org.junit.Test

class PermissionHelperTest {
    @Test
    fun testPermissionListNotEmpty() {
        assertTrue(PermissionHelper.allPermissions.isNotEmpty())
    }

    @Test
    fun testPermissionListContainsSms() {
        val hasSms = PermissionHelper.allPermissions.any {
            it == android.Manifest.permission.RECEIVE_SMS
        }
        assertTrue("Should have RECEIVE_SMS permission", hasSms)
    }

    @Test
    fun testPermissionListContainsCallLog() {
        val hasCallLog = PermissionHelper.allPermissions.any {
            it == android.Manifest.permission.READ_CALL_LOG
        }
        assertTrue("Should have READ_CALL_LOG permission", hasCallLog)
    }

    @Test
    fun testPermissionListContainsLocation() {
        val hasLocation = PermissionHelper.allPermissions.any {
            it == android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        assertTrue("Should have ACCESS_FINE_LOCATION permission", hasLocation)
    }

    @Test
    fun testPermissionListContainsContacts() {
        val hasContacts = PermissionHelper.allPermissions.any {
            it == android.Manifest.permission.READ_CONTACTS
        }
        assertTrue("Should have READ_CONTACTS permission", hasContacts)
    }
}
