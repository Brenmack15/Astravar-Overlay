package com.yazsras.astravar.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.os.Looper
import android.provider.Settings
import android.view.inspector.WindowInspector
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.overlay.OverlayService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

/** Package-manager contract: no overlay component or special permission survives in the app. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReforgeOverlayRetirementTest {
    @Test fun standalonePackageHasNoOverlayServiceOrOverlayPermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.FOREGROUND_SERVICE"))
        assertNull(context.packageManager.resolveService(Intent(context, OverlayService::class.java), 0))
    }
    @Test fun retiredLifecycleCannotRestartEvenWithAnOldOverlayGrant() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val oldGrant = Settings.canDrawOverlays(context)
        val receiversBefore = shadowOf(context).getReceiversForIntent(Intent(Intent.ACTION_SCREEN_OFF)).toList()
        val channelsBefore = notificationManager.notificationChannels.map { it.id }.sorted()
        val notificationsBefore = notificationManager.activeNotifications.map { it.id }.sorted()
        val windowsBefore = WindowInspector.getGlobalWindowViews().toList()
        val controller = Robolectric.buildService(OverlayService::class.java)
        try {
            // Historical user configuration may still record a granted permission.
            // Retirement must remain effective even under that stronger condition.
            ShadowSettings.setCanDrawOverlays(true)
            val service = controller.create().get()
            assertTrue(shadowOf(service).isStoppedBySelf)
            for (action in listOf<String?>(null, "SHOW", "STOP")) {
                assertEquals(Service.START_NOT_STICKY,
                    service.onStartCommand(action?.let { Intent(context, OverlayService::class.java).setAction(it) }, 0, 1))
            }
            service.onConfigurationChanged(Configuration(context.resources.configuration))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
            assertNull(shadowOf(service).lastForegroundNotification)
            assertEquals(channelsBefore, notificationManager.notificationChannels.map { it.id }.sorted())
            assertEquals(notificationsBefore, notificationManager.activeNotifications.map { it.id }.sorted())
            assertEquals(receiversBefore, shadowOf(context).getReceiversForIntent(Intent(Intent.ACTION_SCREEN_OFF)))
            assertEquals(windowsBefore, WindowInspector.getGlobalWindowViews())
        } finally {
            controller.destroy()
            ShadowSettings.setCanDrawOverlays(oldGrant)
        }
    }
}
