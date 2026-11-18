package com.alruwaili.garminnav

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * شاشة بوصلة مستقلة بأسلوب قارمن: حلقة ثابتة الحجم، تدور بها حروف الاتجاهات
 * الأربعة حسب اتجاه السير الفعلي، بينما سهم ثابت في الأعلى يمثّل دائماً الاتجاه
 * الذي يشير إليه الهاتف (تماماً كبوصلة أجهزة قارمن أثناء القيادة).
 * الفكرة مأخوذة من شاشة Compass في قارمن، لكن الرسم والشيفرة هنا أصليان بالكامل.
 */
class CompassActivity : Activity(), SensorEventListener {

    private lateinit var dial: CompassDialView
    private lateinit var headingText: TextView
    private lateinit var sm: SensorManager
    private var rotSensor: Sensor? = null
    private var smoothHeading = Float.NaN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(GTheme.BEZEL)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        val colLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        colLp.gravity = Gravity.CENTER

        val title = TextView(this)
        title.text = "البوصلة"
        title.textDirection = View.TEXT_DIRECTION_RTL
        title.textSize = 22f
        title.setTextColor(GTheme.TEXT)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, dp(30))
        col.addView(title)

        dial = CompassDialView(this)
        val size = dp(260)
        col.addView(dial, LinearLayout.LayoutParams(size, size))

        headingText = TextView(this)
        headingText.text = "٠°"
        headingText.textSize = 30f
        headingText.setTextColor(GTheme.ACCENT)
        headingText.gravity = Gravity.CENTER
        headingText.setPadding(0, dp(24), 0, 0)
        col.addView(headingText)

        root.addView(col, colLp)
        setContentView(root)

        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onResume() {
        super.onResume()
        rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rm = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rm, e.values)
        val o = FloatArray(3)
        SensorManager.getOrientation(rm, o)
        var az = Math.toDegrees(o[0].toDouble()).toFloat()
        az = (az + 360f) % 360f
        if (smoothHeading.isNaN()) smoothHeading = az
        else {
            val diff = ((az - smoothHeading + 540f) % 360f) - 180f
            smoothHeading = (smoothHeading + diff * 0.15f + 360f) % 360f
        }
        dial.heading = smoothHeading
        headingText.text = "${smoothHeading.toInt()}°  ${compassLabel(smoothHeading)}"
    }

    private fun compassLabel(deg: Float): String = when {
        deg < 22.5f || deg >= 337.5f -> "شمال"
        deg < 67.5f -> "شمال شرق"
        deg < 112.5f -> "شرق"
        deg < 157.5f -> "جنوب شرق"
        deg < 202.5f -> "جنوب"
        deg < 247.5f -> "جنوب غرب"
        deg < 292.5f -> "غرب"
        else -> "شمال غرب"
    }
}

/**
 * قرص بوصلة مرسوم يدوياً: الحلقة وحروف الاتجاهات تدور معاً بعكس اتجاه السير
 * (فتصل حرف الشمال فعلياً إلى حيث الشمال الحقيقي)، بينما سهم ثابت أعلى القرص
 * يمثّل دائماً الاتجاه الذي يشير إليه مقدّم الهاتف.
 */
private class CompassDialView(ctx: Context) : View(ctx) {

    var heading: Float = 0f
        set(v) {
            field = v
            invalidate()
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GTheme.MUTED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GTheme.MUTED
        strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GTheme.TEXT
        textAlign = Paint.Align.CENTER
        textSize = 28f
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GTheme.NEEDLE
        style = Paint.Style.FILL
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 14f

        // الحلقة الدوّارة (الاتجاهات)
        c.save()
        c.rotate(-heading, cx, cy)
        c.drawCircle(cx, cy, r, ringPaint)
        for (deg in 0 until 360 step 30) {
            val rad = Math.toRadians(deg.toDouble())
            val x1 = cx + (r - 10f) * Math.sin(rad).toFloat()
            val y1 = cy - (r - 10f) * Math.cos(rad).toFloat()
            val x2 = cx + r * Math.sin(rad).toFloat()
            val y2 = cy - r * Math.cos(rad).toFloat()
            c.drawLine(x1, y1, x2, y2, tickPaint)
        }
        c.drawText("شمال", cx, cy - r + 34f, labelPaint)
        c.drawText("جنوب", cx, cy + r - 12f, labelPaint)
        c.drawText("شرق", cx + r - 28f, cy + 10f, labelPaint)
        c.drawText("غرب", cx - r + 28f, cy + 10f, labelPaint)
        c.restore()

        // السهم الثابت: يمثّل دائماً اتجاه مقدّم الهاتف (أعلى الشاشة).
        val path = Path()
        path.moveTo(cx, cy - r + 6f)
        path.lineTo(cx + 16f, cy - r + 42f)
        path.lineTo(cx, cy - r + 26f)
        path.lineTo(cx - 16f, cy - r + 42f)
        path.close()
        c.drawPath(path, needlePaint)
    }
}
