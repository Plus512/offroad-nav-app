package com.alruwaili.dalilalbar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * لوحة البوصلة السفلية في "وضع الشاشة المنقسمة": قرص بوصلة حقيقي الدوران + نص الاتجاه والمسافة
 * والارتفاع (الارتفاع من بيانات GPS الحقيقية للجهاز، وليس مقدَّراً). تُعرض أسفل الخريطة عند الضغط
 * على سهم البوصلة بعد اختيار وجهة، كما طُلب.
 */
class CompassPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var bearingDegrees: Float = 0f
        set(value) { field = value; invalidate() }

    var bearingLabelText: String = ""
        set(value) { field = value; invalidate() }

    var distanceText: String = ""
        set(value) { field = value; invalidate() }

    var elevationText: String = ""
        set(value) { field = value; invalidate() }

    private val backgroundPaint = Paint().apply { color = Color.argb(255, 28, 30, 36) }

    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 140, 145, 155)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 230, 60, 40)
        style = Paint.Style.FILL
    }

    private val needlePath = Path()

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
    }

    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 210, 210, 210)
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val cx = width / 2f
        val cy = height * 0.4f
        val r = minOf(width / 2f, height * 0.75f) * 0.42f
        if (r <= 0f) return

        var deg = 0
        while (deg < 360) {
            val a = Math.toRadians(deg.toDouble())
            val x1 = cx + (r - 16f) * sin(a).toFloat()
            val y1 = cy - (r - 16f) * cos(a).toFloat()
            val x2 = cx + r * sin(a).toFloat()
            val y2 = cy - r * cos(a).toFloat()
            canvas.drawLine(x1, y1, x2, y2, dialPaint)
            deg += 30
        }
        canvas.drawCircle(cx, cy, r, dialPaint)

        // رأس سهم مصمَّت (مطابق تماماً لشكل سهم البوصلة العلوية CompassArrowView) بدل خط رفيع ونقطة.
        canvas.save()
        canvas.rotate(bearingDegrees, cx, cy)
        val tipY = cy - r * 0.95f
        val backY = cy + r * 0.25f
        val halfWidth = r * 0.30f
        needlePath.reset()
        needlePath.moveTo(cx, tipY)
        needlePath.lineTo(cx - halfWidth, backY)
        needlePath.lineTo(cx, backY - r * 0.20f)
        needlePath.lineTo(cx + halfWidth, backY)
        needlePath.close()
        canvas.drawPath(needlePath, needlePaint)
        canvas.restore()

        canvas.drawText(bearingLabelText, cx, cy + r + 70f, labelPaint)
        canvas.drawText(distanceText, cx, cy + r + 118f, subLabelPaint)
        canvas.drawText(elevationText, cx, cy + r + 160f, subLabelPaint)
    }
}
