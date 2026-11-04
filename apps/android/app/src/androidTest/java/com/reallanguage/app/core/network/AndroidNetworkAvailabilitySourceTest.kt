package com.reallanguage.app.core.network

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Android registration, read-only. Does not change Wi-Fi, VPN or any account. */
class AndroidNetworkAvailabilitySourceTest {
    @Test fun installedPermissionAllowsARealImmediatePlatformObservationAndSafeRelease() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE))
        val events = CopyOnWriteArrayList<Boolean>()
        val source = AndroidNetworkAvailabilitySource(context)
        val subscription = source.subscribe(events::add)
        try { assertTrue("Registration must deliver the current availability immediately", events.isNotEmpty()) }
        finally { subscription.close(); subscription.close() }
    }
}
