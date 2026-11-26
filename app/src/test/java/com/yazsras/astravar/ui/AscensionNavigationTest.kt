package com.yazsras.astravar.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w400dp-h850dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AscensionNavigationTest {
    @get:Rule val compose=createAstravarComposeRule()

    @Test fun fourDestinationsShareTheInboxAndClosingItPreservesSelectedDestination() {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                AstravarTheme {
                    var destination by remember { mutableStateOf("Character") }
                    var inbox by remember { mutableStateOf(false) }
                    Column {
                        RebuildAppHeader(false,{},{},{inbox=true},2)
                        RebuildPrimaryNavigation(destination){destination=it}
                    }
                    if(inbox)PlayerSheet("Notification center",{inbox=false}){
                        PlayerTextButton({inbox=false}){androidx.compose.material3.Text("Return to current page")}
                    }
                }
            }
            assertEquals(listOf("Character","Inventory","Connections","Chronicle"),rebuildDestinations)
            for (name in rebuildDestinations) {
                compose.onNodeWithTag("navigation-$name").performClick().assertIsSelected()
                compose.onNodeWithContentDescription("Notification center. 2 requests to review").performClick()
                compose.onNodeWithText("Return to current page").performClick()
                compose.onNodeWithTag("navigation-$name").assertIsSelected()
            }
        } finally {activity.pause().stop().destroy()}
    }
    @Test fun smallNotificationEmblemHasTransparentSilhouetteAndMonochromePixels(){
        val context=ApplicationProvider.getApplicationContext<Context>()
        val drawable=requireNotNull(context.getDrawable(R.drawable.ic_astravar_notification))
        val image=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888)
        drawable.setBounds(0,0,48,48);drawable.draw(Canvas(image))
        var opaque=0;var transparent=0
        for(x in 0 until image.width)for(y in 0 until image.height){
            val color=image.getPixel(x,y)
            if(Color.alpha(color)==0)transparent++ else if(Color.alpha(color)==255){
                opaque++;assertEquals(Color.red(color),Color.green(color));assertEquals(Color.red(color),Color.blue(color))
            }
        }
        assertTrue(opaque>100 && transparent>650)
        assertEquals(0, Color.alpha(image.getPixel(0,0)));assertEquals(0,Color.alpha(image.getPixel(47,47)))
        image.recycle()
    }
}
