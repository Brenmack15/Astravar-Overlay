package com.yazsras.astravar.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.overlay.OverlayService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Package-manager contract: no overlay component or special permission survives in the app. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ReforgeOverlayRetirementTest {
    @Test fun standalonePackageHasNoOverlayServiceOrOverlayPermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertNull(context.packageManager.resolveService(Intent(context, OverlayService::class.java), 0))
    }
}
