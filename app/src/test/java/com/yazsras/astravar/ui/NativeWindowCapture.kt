package com.yazsras.astravar.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import com.yazsras.astravar.savePresentationCapture
import org.junit.Assert.assertTrue

/** Synthetic test evidence from actual Android windows, including native Dialog surfaces. */
internal fun invalidateNativeCaptureWindows() {
    fun invalidate(view: View) {
        view.invalidate()
        if (view is ViewGroup) for (i in 0 until view.childCount) invalidate(view.getChildAt(i))
    }
    WindowInspector.getGlobalWindowViews().forEach(::invalidate)
}

internal fun captureNativeTestWindows(name: String, activity: ComponentActivity, modal: Boolean = false) {
    if (System.getProperty("astravar.screenshots") == null) return
    val decor = activity.window.decorView
    val windows = WindowInspector.getGlobalWindowViews().filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
    if (modal) assertTrue("Capture must include a genuine Android Dialog window", windows.any { it !== decor })
    check(decor.width > 0 && decor.height > 0)
    val base = IntArray(2).also { decor.getLocationOnScreen(it) }
    fun draw(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        decor.draw(canvas)
        windows.filter { it !== decor }.forEach { window ->
            val params = window.layoutParams as? WindowManager.LayoutParams
            if (params != null && params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
                canvas.drawColor(Color.argb((params.dimAmount * 255).toInt().coerceIn(0, 255), 0, 0, 0))
            val location = IntArray(2).also { window.getLocationOnScreen(it) }
            canvas.save()
            canvas.translate((location[0] - base[0]).toFloat(), (location[1] - base[1]).toFloat())
            window.draw(canvas)
            canvas.restore()
        }
    }
    val warmup = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
    try { draw(warmup) } finally { warmup.recycle() }
    val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
    try { draw(bitmap); savePresentationCapture(bitmap, name) } finally { bitmap.recycle() }
}
