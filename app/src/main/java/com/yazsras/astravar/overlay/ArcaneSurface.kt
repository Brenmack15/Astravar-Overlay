package com.yazsras.astravar.overlay

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.view.View
import com.yazsras.astravar.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Painted metal rather than a bitmap-backed screenshot. Bounds-dependent paths,
 * shaders and engraving are cached; every control stays a genuine Android View.
 */
class ArcaneSurface(
    private val gold: Boolean=false,
    private val danger: Boolean=false,
    private val radius: Float=18f,
    private val accent: Int=if(danger) OverlayInk.RED else OverlayInk.GOLD,
    private val capsule: Boolean=false,
    private val ornament: Boolean=false,
    private val texture: Shader?=null
): Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline=Path()
    private val insetLine=Path()
    private val engraved=Path()
    private val rim=RectF()
    private var material: Shader?=null
    private var bevel: Shader?=null
    private var glow: Shader?=null
    private var grain=FloatArray(0)
    private var alphaValue=255
    private var disabled=false
    private var pressed=false

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val unit=maxOf(1f,radius/10f)
        rim.set(bounds.left+unit,bounds.top+unit,bounds.right-unit,bounds.bottom-unit)
        contour(outline,rim,if(capsule) rim.height()/2f else min(radius*.55f,rim.height()*.2f),capsule)
        val inner=RectF(rim).apply {inset(unit*3f,unit*3f)}
        contour(insetLine,inner,if(capsule) inner.height()/2f else min(radius*.5f,inner.height()*.2f),capsule)
        material=LinearGradient(rim.left,rim.top,rim.right,rim.bottom,
            if(gold) intArrayOf(0xFF554330.toInt(),0xFF25211B.toInt(),0xFF4C3924.toInt(),0xFF191A1B.toInt())
            else intArrayOf(0xFF282C30.toInt(),0xFF111517.toInt(),0xFF1B2023.toInt(),0xFF0A0E10.toInt()),
            floatArrayOf(0f,.36f,.67f,1f),Shader.TileMode.CLAMP)
        bevel=LinearGradient(rim.left,rim.top,rim.right,rim.bottom,
            intArrayOf(0xFFF0D19A.toInt(),accent,0xFF4E3B24.toInt(),0xFFC3A46F.toInt()),
            floatArrayOf(0f,.22f,.56f,1f),Shader.TileMode.CLAMP)
        glow=RadialGradient(rim.centerX(),rim.bottom,rim.width()*.6f,
            tint(accent,if(gold) 62 else 18),Color.TRANSPARENT,Shader.TileMode.CLAMP)
        // Reproducible fine graphite scratches. A single drawPoints call, no live
        // random source, per-frame allocations, or texture-sized bitmap decoding.
        val count=(rim.width()*rim.height()/420f).toInt().coerceIn(24,380)
        grain=FloatArray(count*4)
        var seed=119L
        for(i in 0 until count) {
            seed=(seed*48271L)%2147483647L
            val x=rim.left+(seed%1000)/1000f*rim.width()
            seed=(seed*48271L)%2147483647L
            val y=rim.top+(seed%1000)/1000f*rim.height()
            grain[i*4]=x;grain[i*4+1]=y;grain[i*4+2]=x+unit*2f;grain[i*4+3]=y-unit*.5f
        }
        engraved.reset()
        if(ornament && !capsule) {
            val cx=rim.right-rim.height()*.12f;val cy=rim.centerY();val r=min(rim.width()*.22f,rim.height()*.8f)
            engraved.addCircle(cx,cy,r,Path.Direction.CW)
            engraved.addCircle(cx,cy,r*.7f,Path.Direction.CW)
            for(i in 0..11) {
                val a=i*Math.PI/6
                engraved.moveTo(cx+(cos(a)*r*.55f).toFloat(),cy+(sin(a)*r*.55f).toFloat())
                engraved.lineTo(cx+(cos(a)*r*1.12f).toFloat(),cy+(sin(a)*r*1.12f).toFloat())
            }
            engraved.moveTo(rim.left+unit*12f,rim.bottom-unit*14f)
            engraved.lineTo(rim.width()*.3f+rim.left,rim.bottom-unit*14f)
            engraved.lineTo(rim.width()*.38f+rim.left,rim.bottom-unit*23f)
        }
    }
    override fun draw(canvas: Canvas) {
        if(rim.isEmpty) return
        val unit=maxOf(1f,radius/10f)
        val opacity=if(disabled) .55f else 1f
        paint.style=Paint.Style.FILL;paint.shader=material;paint.alpha=(alphaValue*opacity).toInt()
        canvas.drawPath(outline,paint)
        if(texture!=null) {paint.shader=texture;paint.alpha=(alphaValue*opacity*(if(gold) .62f else .42f)).toInt();canvas.drawPath(outline,paint);paint.alpha=(alphaValue*opacity).toInt()}
        canvas.save();canvas.clipPath(outline)
        paint.shader=glow;canvas.drawRect(rim,paint)
        paint.shader=null;paint.color=tint(0xFFB5B6AA.toInt(),13);paint.strokeWidth=unit*.55f
        canvas.drawLines(grain,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=unit*.65f;paint.color=tint(accent,21)
        canvas.drawPath(engraved,paint)
        if(pressed) {paint.style=Paint.Style.FILL;paint.color=tint(accent,45);canvas.drawRect(rim,paint)}
        canvas.restore()
        paint.style=Paint.Style.STROKE;paint.strokeWidth=unit*(if(gold) .95f else .5f);paint.shader=bevel;paint.alpha=(alphaValue*opacity).toInt();canvas.drawPath(outline,paint)
        paint.shader=null;paint.color=tint(accent,if(gold || pressed) 186 else 84);paint.strokeWidth=unit*.3f;canvas.drawPath(insetLine,paint)
        // Small machined corner facets create depth without a broad ornamental frame.
        if(!capsule && rim.height()>unit*28) {
            paint.color=tint(OverlayInk.GOLD,106);paint.strokeWidth=unit*.8f
            val cut=min(radius*.55f,rim.height()*.2f)
            canvas.drawLine(rim.left+cut+unit*4,rim.top+unit*7,rim.left+cut+unit*17,rim.top+unit*7,paint)
            canvas.drawLine(rim.right-cut-unit*4,rim.bottom-unit*7,rim.right-cut-unit*17,rim.bottom-unit*7,paint)
        }
    }
    override fun isStateful()=true
    override fun onStateChange(state: IntArray): Boolean {
        val nextDisabled=android.R.attr.state_enabled !in state
        val nextPressed=android.R.attr.state_pressed in state || android.R.attr.state_selected in state
        if(nextDisabled==disabled && nextPressed==pressed) return false
        disabled=nextDisabled;pressed=nextPressed;invalidateSelf();return true
    }
    override fun setAlpha(alpha: Int) {alphaValue=alpha;invalidateSelf()}
    override fun setColorFilter(filter: ColorFilter?) {paint.colorFilter=filter;invalidateSelf()}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
    private fun contour(path: Path,rect: RectF,cut: Float,round: Boolean) {
        path.reset()
        if(round) path.addRoundRect(rect,cut,cut,Path.Direction.CW)
        else {path.moveTo(rect.left+cut,rect.top);path.lineTo(rect.right-cut,rect.top);path.lineTo(rect.right,rect.top+cut);path.lineTo(rect.right,rect.bottom-cut);path.lineTo(rect.right-cut,rect.bottom);path.lineTo(rect.left+cut,rect.bottom);path.lineTo(rect.left,rect.bottom-cut);path.lineTo(rect.left,rect.top+cut);path.close()}
    }
}

/** Tiny shared raster tiles; no Activity/Service reference is retained by the asset cache. */
internal object OverlayMaterials {
    private var graphite: Bitmap?=null
    private var metal: Bitmap?=null
    private var weapon: Bitmap?=null
    @Synchronized fun weapon(resources: Resources): Drawable {
        val bitmap=weapon ?: BitmapFactory.decodeResource(resources,R.drawable.astravar_cutout,BitmapFactory.Options().apply {
            inScaled=false;inSampleSize=2;inPreferredConfig=Bitmap.Config.ARGB_8888
        }).also {weapon=it}
        return BitmapDrawable(resources,bitmap).apply {isFilterBitmap=true;setDither(true)}
    }
    @Synchronized fun shader(resources: Resources,gold: Boolean=false): Shader {
        val bitmap=if(gold) metal ?: BitmapFactory.decodeResource(resources,R.drawable.metal_texture,BitmapFactory.Options().apply {inScaled=false}).also {metal=it}
            else graphite ?: BitmapFactory.decodeResource(resources,R.drawable.graphite_texture,BitmapFactory.Options().apply {inScaled=false}).also {graphite=it}
        return BitmapShader(bitmap,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT)
    }
}

internal object OverlayInk {
    val GOLD=0xFFD1AC70.toInt()
    val TEXT=0xFFF0EADF.toInt()
    val MUTED=0xFFB4ADA0.toInt()
    val BLUE=0xFF72BCED.toInt()
    val GREEN=0xFF82D6AE.toInt()
    val RED=0xFFE19B97.toInt()
}
private fun tint(color: Int,alpha: Int)=(color and 0x00FFFFFF) or (alpha.coerceIn(0,255) shl 24)

/** Compact original compass, core, payload and dice marks, drawn at their actual target size. */
internal class OverlaySigil(private val kind: String="compass",private val ink: Int=OverlayInk.GOLD): Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    override fun draw(canvas: Canvas) {
        val r=min(bounds.width(),bounds.height())*.46f
        val cx=bounds.exactCenterX();val cy=bounds.exactCenterY()
        paint.shader=null;paint.style=Paint.Style.STROKE;paint.strokeWidth=maxOf(1f,r*.035f);paint.color=tint(ink,150)
        canvas.drawCircle(cx,cy,r*.88f,paint)
        canvas.drawCircle(cx,cy,r*.7f,paint)
        paint.color=ink;path.reset()
        when(kind) {
            "expand","collapse" -> {val sign=if(kind=="expand") -1 else 1;path.moveTo(cx-r*.32f,cy+sign*r*.12f);path.lineTo(cx,cy-sign*r*.18f);path.lineTo(cx+r*.32f,cy+sign*r*.12f)}
            "dice" -> {
                for(i in 0..5) {val a=-Math.PI/2+i*Math.PI/3;val x=cx+(cos(a)*r*.62).toFloat();val y=cy+(sin(a)*r*.62).toFloat();if(i==0) path.moveTo(x,y) else path.lineTo(x,y)};path.close()
                path.moveTo(cx,cy-r*.62f);path.lineTo(cx-r*.54f,cy+r*.31f);path.lineTo(cx+r*.54f,cy+r*.31f);path.close()
                path.moveTo(cx-r*.54f,cy-r*.31f);path.lineTo(cx,cy+r*.62f);path.lineTo(cx+r*.54f,cy-r*.31f)
            }
            "payload" -> {
                path.moveTo(cx-r*.18f,cy-r*.63f);path.lineTo(cx+r*.18f,cy-r*.63f);path.lineTo(cx+r*.18f,cy-r*.36f);path.lineTo(cx+r*.36f,cy-r*.15f);path.lineTo(cx+r*.36f,cy+r*.45f);path.lineTo(cx-r*.36f,cy+r*.45f);path.lineTo(cx-r*.36f,cy-r*.15f);path.lineTo(cx-r*.18f,cy-r*.36f);path.close()
                path.moveTo(cx-r*.25f,cy+r*.1f);path.lineTo(cx+r*.25f,cy+r*.1f)
            }
            "core" -> {
                path.moveTo(cx,cy-r*.68f);path.lineTo(cx+r*.38f,cy-r*.18f);path.lineTo(cx+r*.23f,cy+r*.42f);path.lineTo(cx,cy+r*.65f);path.lineTo(cx-r*.23f,cy+r*.42f);path.lineTo(cx-r*.38f,cy-r*.18f);path.close()
                path.moveTo(cx,cy-r*.68f);path.lineTo(cx,cy+r*.65f);path.moveTo(cx-r*.38f,cy-r*.18f);path.lineTo(cx+r*.23f,cy+r*.42f);path.moveTo(cx+r*.38f,cy-r*.18f);path.lineTo(cx-r*.23f,cy+r*.42f)
            }
            else -> {
                for(i in 0..7) {val a=-Math.PI/2+i*Math.PI/4;val length=if(i%2==0) r else r*.24f;val x=cx+(cos(a)*length).toFloat();val y=cy+(sin(a)*length).toFloat();if(i==0) path.moveTo(x,y) else path.lineTo(x,y)};path.close()
                path.moveTo(cx-r*.94f,cy);path.lineTo(cx+r*.94f,cy);path.moveTo(cx,cy-r*.94f);path.lineTo(cx,cy+r*.94f)
            }
        }
        canvas.drawPath(path,paint)
        if(kind !in setOf("expand","collapse","payload")) {paint.style=Paint.Style.FILL;paint.color=tint(OverlayInk.BLUE,110);canvas.drawCircle(cx,cy,r*.11f,paint)}
    }
    override fun setAlpha(alpha: Int) {paint.alpha=alpha}
    override fun setColorFilter(filter: ColorFilter?) {paint.colorFilter=filter}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** Dial ornament only; the actual attack total and raw dice remain native TextViews above it. */
internal class OverlayResultDial(private val accent: Int=OverlayInk.BLUE): Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var glow: Shader?=null
    override fun onBoundsChange(bounds: android.graphics.Rect) {
        glow=RadialGradient(bounds.exactCenterX(),bounds.exactCenterY(),maxOf(1f,bounds.height()*.65f),tint(accent,55),Color.TRANSPARENT,Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) {
        val cx=bounds.exactCenterX();val cy=bounds.exactCenterY();val r=bounds.height()*.49f
        paint.style=Paint.Style.FILL;paint.shader=glow;canvas.drawRect(bounds,paint);paint.shader=null
        paint.style=Paint.Style.STROKE;paint.strokeWidth=maxOf(1f,r*.007f);paint.color=tint(OverlayInk.GOLD,75)
        canvas.drawCircle(cx,cy,r,paint);canvas.drawCircle(cx,cy,r*.93f,paint);canvas.drawCircle(cx,cy,r*.74f,paint)
        for(i in 0..15) {val a=i*Math.PI/8;canvas.drawLine(cx+(cos(a)*r*.86).toFloat(),cy+(sin(a)*r*.86).toFloat(),cx+(cos(a)*r).toFloat(),cy+(sin(a)*r).toFloat(),paint)}
        paint.color=tint(accent,100);canvas.drawArc(RectF(cx-r*.81f,cy-r*.81f,cx+r*.81f,cy+r*.81f),207f,64f,false,paint)
    }
    override fun setAlpha(alpha: Int) {paint.alpha=alpha}
    override fun setColorFilter(filter: ColorFilter?) {paint.colorFilter=filter}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** Noninteractive ready light; its parent always retains a readable state label. */
internal class OverlayReadyLight(context: Context,private val ready: Boolean): View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val r=min(width,height)*.4f;val cx=width/2f;val cy=height/2f;val ink=if(ready) OverlayInk.GREEN else OverlayInk.RED
        paint.style=Paint.Style.STROKE;paint.strokeWidth=maxOf(1f,r*.12f);paint.color=tint(ink,150);canvas.drawCircle(cx,cy,r,paint)
        paint.style=Paint.Style.FILL;paint.color=ink;canvas.drawCircle(cx,cy,r*.43f,paint)
    }
}

object OverlayLayout {
    const val COLLAPSED_WIDTH=174
    const val COLLAPSED_HEIGHT=52
    const val EXPANDED_WIDTH=300
    const val WEAPON_HEIGHT=364
    const val DETAILS_HEIGHT=420
}
