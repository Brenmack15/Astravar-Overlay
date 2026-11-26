package com.yazsras.astravar.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloudImagePreparationTest {
    @Test fun selectedCandidateIsDecodedBoundedAndCanBeIndependentlyIdentified() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val picked = Uri.parse("content://synthetic-provider/selected")
        val original = Bitmap.createBitmap(2049, 480, Bitmap.Config.ARGB_8888)
        original.eraseColor(Color.argb(128, 130, 40, 10))
        val input = ByteArrayOutputStream().use { out ->
            assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray()
        }
        original.recycle()
        shadowOf(app.contentResolver).registerInputStreamSupplier(picked) { input.inputStream() }
        val staged = prepareCloudImage(app, picked)
        assertTrue(staged.size in 1..cloudMediaMax)
        val preview = requireNotNull(BitmapFactory.decodeByteArray(staged, 0, staged.size))
        try {
            assertTrue(preview.width in 1..1024 && preview.height in 1..1024)
            assertEquals(128, Color.alpha(preview.getPixel(0, 0)))
        } finally { preview.recycle() }
        assertEquals(cloudDigest(staged), cloudDigest(prepareCloudImage(app, picked)))
        shadowOf(app.contentResolver).registerInputStreamSupplier(picked) { "fixture text".byteInputStream() }
        assertThrows(IllegalArgumentException::class.java) { prepareCloudImage(app, picked) }
        assertThrows(IllegalArgumentException::class.java) { prepareCloudImage(app, Uri.parse("file:///fixture.png")) }
    }
}
