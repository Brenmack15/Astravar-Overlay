package com.yazsras.astravar.data

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.BuildConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class CampaignDeviceBuildTest {
    @Test fun hashesInstalledBaseFileAndRefreshesOnlyWhenItsIdentityChanges() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("fictional-base", ".apk", app.cacheDir)
        val installed = ApplicationInfo(app.applicationInfo).apply {
            sourceDir = file.path
            splitSourceDirs = arrayOf("fixture-config.apk")
        }
        val context = object : ContextWrapper(app) {
            override fun getApplicationInfo(): ApplicationInfo = installed
        }
        try {
            file.writeText("abc")
            val report = requireNotNull(CampaignDeviceBuild.report(context))
            assertEquals(app.packageName, report.getValue("packageName").jsonPrimitive.content)
            assertEquals(BuildConfig.VERSION_CODE, report.getValue("versionCode").jsonPrimitive.int)
            assertEquals(1, report.getValue("splitApkCount").jsonPrimitive.int)
            assertEquals(35, report.getValue("androidApi").jsonPrimitive.int)
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", report.getValue("baseApkSha256").jsonPrimitive.content)
            assertEquals(report, CampaignDeviceBuild.report(context))
            file.writeText("a")
            assertEquals("ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb",
                CampaignDeviceBuild.report(context)!!.getValue("baseApkSha256").jsonPrimitive.content)
            assertTrue(file.delete())
            assertNull(CampaignDeviceBuild.report(context))
        } finally { file.delete() }
    }
}
