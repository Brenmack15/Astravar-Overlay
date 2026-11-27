package com.yazsras.astravar.data

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yazsras.astravar.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class AscensionNotificationNativeTest {
    @Test fun actualSmallEmblemInStatusBarAndShadeAcrossLightAndDarkThemes() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        assertEquals("Isolated Android 17 emulator required",37,Build.VERSION.SDK_INT)
        assertEquals("1",device.executeShellCommand("getprop ro.kernel.qemu").trim())
        assertEquals("com.yazsras.astravar.v2uidebug",context.packageName)
        fun screenshot():Bitmap {
            val raw=instrumentation.uiAutomation.takeScreenshot() ?: error("SystemUI screenshot unavailable")
            if(raw.config!=Bitmap.Config.HARDWARE)return raw
            return try {requireNotNull(raw.copy(Bitmap.Config.ARGB_8888,false))}finally{raw.recycle()}
        }
        val output=File(requireNotNull(context.getExternalFilesDir(null)),"ascension-notification").apply { mkdirs() }
        val control=CampaignSyncPreferences(context)
        val oldNotification=control.notificationsEnabled
        val oldStatus=control.savedStatus
        val oldNight=device.executeShellCommand("cmd uimode night").substringAfter(':').trim().substringBefore(' ')
        val manager=context.getSystemService(NotificationManager::class.java)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        try {
            control.notificationsEnabled=true
            control.savedStatus="""{"status":"Sync scheduled"}"""
            for((theme,night,expected) in listOf(Triple("light","no",Configuration.UI_MODE_NIGHT_NO),Triple("dark","yes",Configuration.UI_MODE_NIGHT_YES))){
                device.executeShellCommand("cmd statusbar collapse")
                device.executeShellCommand("cmd uimode night $night")
                onSystemBarsSurface(device,theme,expected) {
                    waitUntil {
                        val frame=screenshot()
                        try {
                            val pixel=frame.getPixel(frame.width/2,frame.height/4)
                            if (theme=="light") Color.red(pixel)>210 else Color.red(pixel)<45
                        } finally { frame.recycle() }
                    }
                    manager.cancel(CampaignSyncNotifications.ID)
                    CampaignSyncNotifications.refresh(context)
                    waitUntil {
                        manager.activeNotifications.singleOrNull { it.id==CampaignSyncNotifications.ID }?.notification?.smallIcon?.resId==R.drawable.ic_astravar_notification
                    }
                    device.waitForIdle()
                    val title=manager.activeNotifications.single { it.id==CampaignSyncNotifications.ID }.notification.extras.getCharSequence("android.title").toString()
                    // The bar may animate a new icon after the posting API returns.
                    var statusFound=false
                    val deadline=SystemClock.uptimeMillis()+8000
                    do {
                        val shot=screenshot()
                        try {
                            write(shot,File(output,"api37-$theme-status.png"))
                            val hId=context.resources.getIdentifier("status_bar_height","dimen","android")
                            val h=if(hId!=0)context.resources.getDimensionPixelSize(hId) else (32*context.resources.displayMetrics.density).toInt()
                            statusFound=verifyShape(context,shot,Rect(0,0,shot.width*3/4,h.coerceAtMost(shot.height)),
                                File(output,"api37-$theme-status-icon.png"))
                        }finally {shot.recycle()}
                        if(!statusFound)SystemClock.sleep(200)
                    }while(!statusFound && SystemClock.uptimeMillis()<deadline)
                    assertTrue("SystemUI status bar must display the faceted notification silhouette in $theme",statusFound)
                    device.openNotification()
                    val selector=By.pkg("com.android.systemui").text(title)
                    if(!device.wait(Until.hasObject(selector),5000))device.executeShellCommand("cmd statusbar expand-notifications")
                    assertTrue("SystemUI must show the actual posted notification title",device.wait(Until.hasObject(selector),8000))
                    device.waitForIdle()
                    val bounds=device.findObject(selector).visibleBounds
                    val shot=screenshot()
                    try{
                        write(shot,File(output,"api37-$theme-shade.png"))
                        val dp=context.resources.displayMetrics.density
                        val area=Rect((bounds.left-94*dp).toInt().coerceAtLeast(0),(bounds.top-75*dp).toInt().coerceAtLeast(0),
                            (bounds.left+110*dp).toInt().coerceAtMost(shot.width),(bounds.bottom+22*dp).toInt().coerceAtMost(shot.height))
                        assertTrue("The expanded card must display the same emblem in $theme",verifyShape(context,shot,area,File(output,"api37-$theme-shade-icon.png")))
                    }finally{shot.recycle()}
                }
            }
        }finally{
            device.executeShellCommand("cmd statusbar collapse")
            manager.cancel(CampaignSyncNotifications.ID)
            control.savedStatus=oldStatus;control.notificationsEnabled=oldNotification
            if(oldNotification)CampaignSyncNotifications.refresh(context)
            if(oldNight in setOf("yes","no","auto","custom"))device.executeShellCommand("cmd uimode night $oldNight")
        }
    }

    /** SystemUI owns both notification images. The foreground supplies a measured, contrasting window. */
    @android.annotation.TargetApi(30)
    private fun onSystemBarsSurface(device:UiDevice,theme:String,expected:Int,observe:()->Unit) {
        val light=theme=="light"
        val marker="System notification backdrop $theme"
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            waitUntil {
                var mode=Configuration.UI_MODE_NIGHT_UNDEFINED
                scenario.onActivity { mode=it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK }
                mode==expected
            }
            scenario.onActivity { activity ->
                val window=activity.window
                window.setDecorFitsSystemWindows(false)
                window.statusBarColor=Color.TRANSPARENT
                window.isStatusBarContrastEnforced=false
                window.setContentView(View(activity).apply {
                    setBackgroundColor(if (light) Color.WHITE else Color.BLACK)
                    contentDescription=marker
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
                })
                requireNotNull(window.insetsController).apply {
                    show(WindowInsets.Type.statusBars())
                    val flag=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    setSystemBarsAppearance(if (light) flag else 0,flag)
                }
            }
            assertTrue("The themed fixture window must be visible",device.wait(Until.hasObject(By.desc(marker)),15000))
            waitUntil {
                var ready=false
                scenario.onActivity { activity ->
                    val decor=activity.window.decorView
                    val mode=activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    val appearance=activity.window.insetsController?.systemBarsAppearance ?: return@onActivity
                    val darkGlyphs=(appearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)!=0
                    ready=activity.hasWindowFocus() && mode==expected && darkGlyphs==light &&
                        decor.rootWindowInsets?.isVisible(WindowInsets.Type.statusBars())==true
                }
                ready
            }
            try { observe() } finally { device.executeShellCommand("cmd statusbar collapse") }
        }
    }

    private fun waitUntil(predicate:()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+10000
        while(!predicate() && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100)
        assertTrue(predicate())
    }
    private fun write(bitmap:Bitmap,file:File)=file.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
    private fun verifyShape(context:Context,screen:Bitmap,area:Rect,crop:File):Boolean {
        if(area.width()<8 || area.height()<8)return false
        val size=24
        val reference=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        val emblem=requireNotNull(context.getDrawable(R.drawable.ic_astravar_notification))
        emblem.setBounds(0,0,size,size);emblem.draw(android.graphics.Canvas(reference))
        val samples=mutableListOf<Triple<Int,Int,Int>>()
        for(y in 2 until 23 step 2)for(x in 3 until 22 step 2){
            val a=Color.alpha(reference.getPixel(x,y));if(a>=220 || a<=12)samples+=Triple(x,y,if(a>=220)1 else 0)
        }
        reference.recycle()
        val w=area.width();val h=area.height();val pixels=IntArray(w*h)
        screen.getPixels(pixels,0,w,area.left,area.top,w,h)
        for(i in pixels.indices){val c=pixels[i];pixels[i]=(Color.red(c)*54+Color.green(c)*183+Color.blue(c)*19)/256}
        val white=samples.count{it.third==1};val black=samples.size-white;val n=samples.size
        var best=0.0;var match:Rect?=null
        val density=context.resources.displayMetrics.density
        for(side in maxOf(10,(density*10).toInt())..minOf(w,h,(density*36).toInt()) step 2){
            val offsets=samples.map{ ((it.second+0.5)*side/size).toInt()*w+((it.first+0.5)*side/size).toInt() }.toIntArray()
            for(y in 0..h-side step 2)for(x in 0..w-side step 2){
                val index=y*w+x;var ink=0;var total=0;var square=0
                for(i in offsets.indices){val p=pixels[index+offsets[i]];if(samples[i].third==1)ink+=p;total+=p;square+=p*p}
                val variance=square.toDouble()*n-total.toDouble()*total
                if(variance<=0 || abs(ink.toDouble()/white-(total-ink).toDouble()/black)<25)continue
                val score=abs(ink.toDouble()*n-total.toDouble()*white)/sqrt(variance*white*black)
                if(score>best){best=score;match=Rect(area.left+x,area.top+y,area.left+x+side,area.top+y+side)}
            }
        }
        match?.let{val extracted=Bitmap.createBitmap(screen,it.left,it.top,it.width(),it.height());try{write(extracted,crop)}finally{extracted.recycle()}}
        return best>=0.72
    }
}
