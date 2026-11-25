package com.alruwaili.garminalbarr.legacymap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * بوصلة/سهم أعلى الشاشة، مرسوم بالكامل برمجياً (وليس نصاً أو خطاً رفيعاً): قرص أبيض وسهم مصمَّت
 * واضح الرأس. يشير افتراضياً إلى الشمال (أحمر)، ويتحول بعد اختيار وجهة للإشارة فعلياً نحوها
 * (أزرق) حسب الاتجاه الحقيقي المحسوب من GeoMath.bearingDegrees — وليس مجرد خط مستقيم كما كان
 * في الفيديو التمثيلي السابق.
 */
class CompassArrowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** زاوية دوران السهم بالدرجات (٠ = للأعلى). */
    var headingDegrees: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** true إن كان السهم يشير حالياً إلى وجهة مختارة (وليس إلى الشمال). */
    var pointsToDestination: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val circleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val circleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 60, 60, 60)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val arrowNorthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 210, 30, 30)
        style = Paint.Style.FILL
    }

    private val arrowDestinationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 13, 127, 239)
        style = Paint.Style.FILL
    }

    private val arrowPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 6f
        if (r <= 0f) return

        canvas.drawCircle(cx, cy, r, circleFillPaint)
        canvas.drawCircle(cx, cy, r, circleStrokePaint)

        canvas.save()
        canvas.rotate(headingDegrees, cx, cy)

        // رأس سهم مصمَّت واضح (مثلث أمامي + مؤخرة منحنية إلى الداخل) بدل خط رفيع.
        val tipY = cy - r * 0.78f
        val backY = cy + r * 0.55f
        val halfWidth = r * 0.32f
        arrowPath.reset()
        arrowPath.moveTo(cx, tipY)
        arrowPath.lineTo(cx - halfWidth, backY)
        arrowPath.lineTo(cx, backY - r * 0.22f)
        arrowPath.lineTo(cx + halfWidth, backY)
        arrowPath.close()
        canvas.drawPath(arrowPath, if (pointsToDestination) arrowDestinationPaint else arrowNorthPaint)

        canvas.restore()
    }
}
