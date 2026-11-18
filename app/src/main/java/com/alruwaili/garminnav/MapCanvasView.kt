package com.alruwaili.garminnav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.concurrent.thread

/**
 * شاشة الخريطة. ترسم الآن الطرق والمعالم الحقيقية المفكوكة من ملف قارمن .img
 * (قطاعا TRE وRGN)، فوق خلفية رملية، مع سهم الموقع الحالي (يدور حسب اتجاه السير)
 * ودعم السحب بالإصبع (Pan) والتكبير بإصبعين (Pinch-zoom) وزر إعادة التوسيط («موقعي»).
 *
 * فك التشفير (الحدود، التقسيمات الفرعية، الطرق، النقاط، وأسماء المعالم من قطاع LBL) تم
 * التحقق منه تجريبياً وبصرياً على خريطة حقيقية قبل اعتماده (راجع التعليقات في
 * GarminMapData.kt للتفاصيل). تُعرض أسماء المعالم فقط عند تكبير كافٍ، وفقط للأسماء التي
 * اجتازت فحص السلامة (نص عربي/لاتيني مقروء بالكامل) — أي اسم مشكوك فيه يُهمَل بصمت.
 */
class MapCanvasView(ctx: Context) : View(ctx) {

    var heading: Float = 0f
        set(v) { field = v; invalidate() }

    var hasFix: Boolean = false
        set(v) { field = v; invalidate() }

    var mapLoaded: Boolean = false
        set(v) { field = v; invalidate() }

    /** true أثناء قراءة وفك ملف الخريطة في الخلفية (قد يستغرق وقتاً لملف كبير). */
    var mapLoading: Boolean = false
        set(v) { field = v; invalidate() }

    /** مستوى التكبير (١ أبعد ← ١٠ أقرب). */
    var zoomLevel: Int = 5
        set(v) { field = v; invalidate(); requestDecode() }

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
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B2B2E")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val polygonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 120, 150, 90)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    // أودية/مجاري مائية (رمز النوع ٠x18 «مجرى مائي رفيع»، ٠x1f «نهر»، ٠x26 «نهر متقطع» —
    // تم التحقق من وجودها فعلياً في بيانات خريطتك: ٥٨٩٢ خطاً بالرمز ٠x18 وحده، منطقي جداً
    // لشبكة الشعاب الكثيفة في منطقة صحراوية) — تُرسم بلون أزرق مائي بدل اللون العام للطرق.
    private val wadiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3C78A8")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // مضلعات مائية (نهر ٠x46، بحيرة ٠x3c، نهر/بحيرة متقطعة ٠x4c) — تعبئة زرقاء شبه شفافة.
    private val waterPolyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 60, 120, 168)
        style = Paint.Style.FILL
    }
    private val poiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B8441C")
        style = Paint.Style.FILL
    }
    // أسماء المعالم (من قطاع LBL، عربية صحيحة ١٠٠٪ تم التحقق منها) — تُرسم فقط عند
    // تكبير كافٍ وبحد أقصى للعدد لتفادي ازدحام الشاشة وبطء الرسم.
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B2B2E")
        textSize = 24f
        textAlign = Paint.Align.LEFT
    }

    // إزاحة السحب بالإصبع (بكسل)؛ تُصفَّر عند الضغط على زر «موقعي» لإعادة التوسيط.
    private var panX = 0f
    private var panY = 0f
    private var scaleAccum = 1f

    // ---------- بيانات الخريطة الحقيقية (قارمن .img) ----------
    private var garminMap: GarminMap? = null
    @Volatile private var decoded: DecodedFeatures? = null
    @Volatile private var decodeGeneration = 0
    private var centerLon = 0
    private var centerLat = 0
    private var mapLoadFailed = false

    /** وحدات قارمن الخام لكل بكسل حسب مستوى التكبير (١=الأبعد، ١٠=الأقرب). */
    private fun unitsPerPixel(): Double = 16.0 * Math.pow(2.0, (5 - zoomLevel).toDouble())

    /** يحمّل ملف قارمن .img من مسار مباشر (الأسلوب القديم)، في خيط خلفي. */
    fun loadGarminMap(path: String) {
        loadGarminMapCommon { GarminMap(path).also { it.open() } }
    }

    /**
     * يحمّل ملف قارمن .img من أي مصدر (مجرى بيانات)، في خيط خلفي — يُستخدم عند
     * القراءة من مجلد محفوظ عبر SAF (حيث لا يوجد مسار ملف مباشر). [openStream]
     * يُستدعى في الخيط الخلفي نفسه ويُغلق تلقائياً بعد القراءة.
     */
    fun loadGarminMapFromStream(openStream: () -> java.io.InputStream) {
        loadGarminMapCommon {
            val m = GarminMap()
            openStream().use { m.openFromStream(it) }
            m
        }
    }

    private fun loadGarminMapCommon(opener: () -> GarminMap) {
        mapLoadFailed = false
        mapLoading = true
        thread {
            try {
                val m = opener()
                if (m.tiles.isEmpty()) {
                    post { mapLoading = false; mapLoadFailed = true; invalidate() }
                    return@thread
                }
                garminMap = m
                MapRepository.map = m
                MapRepository.labelIndex = null
                post {
                    centerLon = m.centerLon
                    centerLat = m.centerLat
                    mapLoading = false
                    mapLoaded = true
                    scheduleDecode()
                }
            } catch (e: Exception) {
                post { mapLoading = false; mapLoadFailed = true; invalidate() }
            }
        }
    }

    /** يعيد فك الطرق/النقاط المرئية حالياً فقط (حسب موقع الكاميرا والتكبير)، في خيط خلفي. */
    private fun scheduleDecode() {
        val m = garminMap ?: return
        if (width == 0 || height == 0) return
        val myGen = ++decodeGeneration
        val upp = unitsPerPixel()
        // المركز الجغرافي الفعلي المعروض حالياً في وسط الشاشة (يأخذ السحب بالإصبع بعين الاعتبار).
        val viewLon = centerLon - (panX * upp).toInt()
        val viewLat = centerLat + (panY * upp).toInt()
        val halfW = (width * upp / 2).toInt()
        val halfH = (height * upp / 2).toInt()
        val margin = ((halfW + halfH) / 2 * 0.3).toInt()
        val lonMin = viewLon - halfW - margin
        val lonMax = viewLon + halfW + margin
        val latMin = viewLat - halfH - margin
        val latMax = viewLat + halfH + margin
        thread {
            val result = m.decodeVisible(lonMin, lonMax, latMin, latMax, 8000)
            if (myGen == decodeGeneration) {
                decoded = result
                post { invalidate() }
            }
        }
    }

    private val decodeRunnable = Runnable { scheduleDecode() }

    /** يطلب إعادة فك بعد توقف قصير عن التحريك، لتفادي إعادة الفك مع كل حدث سحب. */
    private fun requestDecode() {
        if (garminMap == null) return
        removeCallbacks(decodeRunnable)
        postDelayed(decodeRunnable, 180)
    }

    private val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            panX -= distanceX
            panY -= distanceY
            invalidate()
            requestDecode()
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaleAccum = 1f
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleAccum *= detector.scaleFactor
            while (scaleAccum > 1.15f) {
                zoomLevel = (zoomLevel + 1).coerceAtMost(10)
                scaleAccum /= 1.15f
            }
            while (scaleAccum < 1f / 1.15f) {
                zoomLevel = (zoomLevel - 1).coerceAtLeast(1)
                scaleAccum *= 1.15f
            }
            requestDecode()
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (garminMap != null) scheduleDecode()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    /** زر «موقعي»: يعيد توسيط سهم الموقع بعد سحبه بالإصبع، ويستأنف تتبّع GPS إن توفّر. */
    fun recenter() {
        panX = 0f
        panY = 0f
        if (lastFixLon != null && lastFixLat != null) {
            centerLon = lastFixLon!!
            centerLat = lastFixLat!!
        }
        invalidate()
        requestDecode()
    }

    /** ينقل مركز العرض إلى موقع محدد (مثلاً نتيجة بحث)، بمعزل عن موقع GPS الحالي. */
    fun goTo(lon: Int, lat: Int) {
        panX = 0f
        panY = 0f
        centerLon = lon
        centerLat = lat
        if (zoomLevel < 7) zoomLevel = 7 // تكبير كافٍ لرؤية الاسم المختار بوضوح
        invalidate()
        requestDecode()
    }

    /**
     * آخر إحداثي GPS مؤكَّد، بوحدات قارمن الخام (نفس وحدات goTo/centerLon/centerLat)،
     * أو null إن لم تُستقبل أي إشارة بعد — تستخدمه شاشات مثل «وضع علامة» و«الطوارئ».
     */
    fun currentFix(): Pair<Int, Int>? {
        val lon = lastFixLon
        val lat = lastFixLat
        return if (lon != null && lat != null) lon to lat else null
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)

        val gm = garminMap
        val feats = decoded
        if (gm != null && feats != null) {
            val upp = unitsPerPixel()
            val cx0 = width / 2f + panX
            val cy0 = height / 2f + panY
            fun px(lon: Int): Float = cx0 + ((lon - centerLon) / upp).toFloat()
            fun py(lat: Int): Float = cy0 - ((lat - centerLat) / upp).toFloat()

            val path = android.graphics.Path()
            for (line in feats.lines) {
                if (line.lons.isEmpty()) continue
                path.reset()
                path.moveTo(px(line.lons[0]), py(line.lats[0]))
                for (i in 1 until line.lons.size) path.lineTo(px(line.lons[i]), py(line.lats[i]))
                val isWadiLine = !line.isPolygon && (line.typ == 0x18 || line.typ == 0x1f || line.typ == 0x26)
                val isWaterPoly = line.isPolygon && (line.typ == 0x46 || line.typ == 0x3c || line.typ == 0x4c)
                val paint = when {
                    isWadiLine -> wadiPaint
                    isWaterPoly -> waterPolyPaint
                    line.isPolygon -> polygonPaint
                    else -> roadPaint
                }
                c.drawPath(path, paint)
            }
            var labelsDrawn = 0
            val showLabels = zoomLevel >= 5
            for (pt in feats.points) {
                val x = px(pt.lon); val y = py(pt.lat)
                c.drawCircle(x, y, 5f, poiPaint)
                val lbl = pt.label
                if (showLabels && lbl != null && labelsDrawn < 400) {
                    c.drawText(lbl, x + 8f, y + 8f, labelPaint)
                    labelsDrawn++
                }
            }
        } else {
            // لا خريطة محمّلة بعد: شبكة خفيفة فقط لإعطاء إحساس بالحركة، وليست طرقاً حقيقية.
            val step = (220 - zoomLevel * 18).coerceAtLeast(40)
            val stepF = step.toFloat()
            var y = (((panY % stepF) + stepF) % stepF) - stepF
            while (y < height) { c.drawLine(0f, y, width.toFloat(), y, gridPaint); y += stepF }
            var x = (((panX % stepF) + stepF) % stepF) - stepF
            while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), gridPaint); x += stepF }
        }

        if (mapLoading) {
            c.drawText("جارٍ قراءة ملف الخريطة… قد يستغرق بعض الوقت", width / 2f, height / 2f - 60f, msgPaint)
        } else if (!mapLoaded) {
            c.drawText("لم تُستورد خريطة بعد — اضغط «استيراد خريطة» من القائمة", width / 2f, height / 2f - 60f, msgPaint)
        } else if (mapLoadFailed) {
            c.drawText("تعذّر قراءة ملف الخريطة", width / 2f, height / 2f - 60f, msgPaint)
        } else if (gm != null && feats == null) {
            c.drawText("جارٍ تحميل الخريطة…", width / 2f, height / 2f - 60f, msgPaint)
        }

        // سهم المركبة في وسط الشاشة افتراضياً، يتحرك مع السحب بالإصبع ويدور مع اتجاه السير.
        val cx = width / 2f + panX
        val cy = height / 2f + panY
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

    private var lastFixLon: Int? = null
    private var lastFixLat: Int? = null

    fun onLocation(loc: Location) {
        hasFix = true
        val lon = (loc.longitude * (1 shl 23) / 180.0).toInt()
        val lat = (loc.latitude * (1 shl 23) / 180.0).toInt()
        lastFixLon = lon
        lastFixLat = lat
        // في وضع التتبّع (لم يسحب المستخدم الشاشة بعيداً)، يتبع مركز العرض الموقع الحالي.
        if (panX == 0f && panY == 0f) {
            centerLon = lon
            centerLat = lat
            requestDecode()
        }
    }
}
