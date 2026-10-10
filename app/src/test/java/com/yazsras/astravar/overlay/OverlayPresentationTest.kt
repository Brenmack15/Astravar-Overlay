package com.yazsras.astravar.overlay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.yazsras.astravar.data.OverlayPrefs
import com.yazsras.astravar.rules.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Exercises the real service view builder without claiming system-overlay permission/gesture coverage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayPresentationTest {
    private fun texts(v:View):List<String> = (if(v is TextView) listOf(v.text.toString()) else emptyList()) + if(v is ViewGroup) (0 until v.childCount).flatMap {texts(v.getChildAt(it))} else emptyList()
    @Test(timeout=60000) fun collapsedExpandedAndResultUseCompactSharedExpressionsWithoutAc() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        val controller=Robolectric.buildService(OverlayService::class.java)
        val service=controller.get()
        ShadowSettings.setCanDrawOverlays(true)
        val initial=Campaign(onboarded=true,attuned=true,installedId="core",cores=listOf(Core("core","Resonant crystal")),profile=Profile(baseDamageDiceCount=4))
        ReflectionHelpers.setField(service,"uiContext",activity.get())
        ReflectionHelpers.setField(service,"wm",activity.get().getSystemService(WindowManager::class.java))
        ReflectionHelpers.setField(service,"state",initial)
        ReflectionHelpers.setField(service,"preferencesLoaded",true)
        ReflectionHelpers.setField(service,"prefs",OverlayPrefs(mode=Ability.ELEMENTAL,elements=listOf(DamageType.LIGHTNING)))
        ReflectionHelpers.setField(service,"elements",listOf(DamageType.LIGHTNING))
        fun render(name:String):View {
            ReflectionHelpers.callInstanceMethod<Unit>(service,"render")
            val view=ReflectionHelpers.getField<View>(service,"view")
            val params=ReflectionHelpers.getField<WindowManager.LayoutParams>(service,"params")
            view.measure(View.MeasureSpec.makeMeasureSpec(params.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(params.height,View.MeasureSpec.EXACTLY));view.layout(0,0,params.width,params.height)
            System.getProperty("astravar.screenshots")?.let {dir->File(dir).mkdirs();val bitmap=Bitmap.createBitmap(params.width,params.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap));File(dir,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            return view
        }
        try {
            val collapsed=render("overlay-collapsed")
            assertTrue(texts(collapsed).any {it.contains("ASTRAVAR")})
            assertTrue(collapsed.height<=110);assertTrue(collapsed.width<=350)
            ReflectionHelpers.setField(service,"expanded",true)
            val expanded=render("overlay-expanded")
            val words=texts(expanded)
            assertTrue(words.contains("4d12+5 force + 2d8 lightning"))
            assertTrue(words.contains("Roll attack"));assertTrue(words.none {it.contains("Armor Class")})
            assertTrue(expanded.width<=600);assertTrue(expanded.height<=728)
            val p=Engine.plan(initial,"roll",Ability.ELEMENTAL,elements=listOf(DamageType.LIGHTNING))
            val r=DiceRoller.roll(p,die={if(it==20) 16 else 3})
            ReflectionHelpers.setField(service,"state",initial.copy(pending=listOf(PendingShot(p,DiceRoller.resolution(p,r),r))))
            ReflectionHelpers.setField(service,"panelPage","result")
            val result=texts(render("overlay-result"))
            assertTrue(result.contains("ATTACK ROLL: 24"));assertTrue(result.contains("23 POTENTIAL DAMAGE"))
            assertFalse(result.contains("HIT"));assertFalse(result.contains("MISS"))
        } finally {ReflectionHelpers.callInstanceMethod<Unit>(service,"removeWindow");activity.pause().stop().destroy()}
    }
}
