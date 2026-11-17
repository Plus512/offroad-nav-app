package com.alruwaili.garminnav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.view.View

/**
 * شاشة الخريطة. حالياً: خلفية رملية + سهم الموقع الحالي (ثابت في المنتصف،
 * يدور حسب اتجاه السير) + دائرة دقة الموقع.
 *
 * رسم الطرق والنقاط الفعلية من ملف قارمن .img (قطاع RGN) لم يُفعَّل بعد:
 * فكّ حاوية الملف (FAT) تم التحقق منه على خريطتك الحقيقية، لكن استخراج
 * شكل الطرق/النقاط من داخل RGN يحتاج عملاً إضافياً لم يكتمل، ولن تُعرض
 * بيانات غير مؤكدة كأنها خريطة حقيقية.
 */
class MapCanvasView(ctx: Context) : View(ctx) {

    var heading: Float = 0f
        set(v) { field = v; invalidate() }

    var hasFix: Boolean = false
        set(v) { field = v; invalidate() }

    var mapLoaded: Boolean = false
        set(v) { field = v; invalidate() }

    /** مستوى تكبير اصطناعي (١-١٠) يُغيّر تباعد الشبكة فقط، بانتظار ربطه برسم خريطة حقيقية. */
    var zoomLevel: Int = 5
        set(v) { field = v; invalidate() }

    private val bg = Paint().apply { color = GTheme.SAND }
    private val gridPaint = Paint().apply {
        color = Color.argb(60, 0, 0, 0)
        strokeWidth = 2f
    }
    private val vehiclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GTheme.ACCENT
        style = Paint.Style.FILL
    }
    private val vehicleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C1E")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 20, 15, 10)
        textAlign = Paint.Align.CENTER
        textSize = 30f
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)

        // شبكة خفيفة فقط لإعطاء إحساس بالحركة والمسافة، وليست طرقاً حقيقية.
        val step = (220 - zoomLevel * 18).coerceAtLeast(40)
        var y = (height % step)
        while (y < height) { c.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), gridPaint); y += step }
        var x = (width % step)
        while (x < width) { c.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), gridPaint); x += step }

        if (!mapLoaded) {
            c.drawText("لم تُستورد خريطة بعد — اضغط «استيراد خريطة» من القائمة", width / 2f, height / 2f - 60f, msgPaint)
        }

        // سهم المركبة ثابت في منتصف الشاشة، يدور مع اتجاه السير.
        val cx = width / 2f
        val cy = height / 2f
        c.save()
        c.rotate(heading, cx, cy)
        val r = minOf(width, height) * 0.05f
        val path = android.graphics.Path()
        path.moveTo(cx, cy - r)
        path.lineTo(cx + r * 0.7f, cy + r * 0.8f)
        path.lineTo(cx, cy + r * 0.35f)
        path.lineTo(cx - r * 0.7f, cy + r * 0.8f)
        path.close()
        c.drawPath(path, vehiclePaint)
        c.drawPath(path, vehicleStroke)
        c.restore()

        if (!hasFix) {
            c.drawText("بانتظار إشارة GPS…", width / 2f, height / 2f + 70f, msgPaint)
        }
    }

    fun onLocation(loc: Location) {
        hasFix = true
    }
}
