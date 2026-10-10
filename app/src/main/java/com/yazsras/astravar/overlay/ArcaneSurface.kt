package com.yazsras.astravar.overlay

import android.graphics.*
import android.graphics.drawable.Drawable

/** A single cheap painted surface, shared by the compact overlay and its controls. */
class ArcaneSurface(private val gold: Boolean=false,private val danger: Boolean=false,private val radius: Float=18f): Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val rect=RectF(bounds).apply {inset(1f,1f)}
        val top=if(danger) 0xFF71373A.toInt() else if(gold) 0xFFE3C28C.toInt() else 0xFF252C32.toInt()
        val bottom=if(danger) 0xFF472126.toInt() else if(gold) 0xFFBA955C.toInt() else 0xFF11171B.toInt()
        paint.style=Paint.Style.FILL;paint.shader=LinearGradient(0f,rect.top,rect.width(),rect.bottom,top,bottom,Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect,radius,radius,paint)
        paint.shader=null;paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f;paint.color=if(gold) 0xFFF4DCAE.toInt() else 0xFF685D49.toInt()
        canvas.drawRoundRect(rect,radius,radius,paint)
    }
    override fun setAlpha(alpha: Int) {paint.alpha=alpha}
    override fun setColorFilter(filter: ColorFilter?) {paint.colorFilter=filter}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

object OverlayLayout {
    const val COLLAPSED_WIDTH=174
    const val COLLAPSED_HEIGHT=52
    const val EXPANDED_WIDTH=300
    const val WEAPON_HEIGHT=364
    const val DETAILS_HEIGHT=420
}
