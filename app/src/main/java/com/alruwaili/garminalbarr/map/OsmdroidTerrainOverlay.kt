package com.alruwaili.garminalbarr.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import com.alruwaili.garminalbarr.BuildConfig
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.max
import kotlin.math.min

/**
 * طبقة تظليل تضاريس (hillshade) فوق الخريطة الأساسية، مبنية من بلاطات ارتفاع AWS Terrarium
 * المحفوظة محلياً عبر [TerrainTileCache] (نفس المصدر المستخدم سابقاً في دليل البر).
 *
 * ملاحظة صدق مهمة: Terrarium PNG ليست صورة مرئية جاهزة، بل بيانات ارتفاع مُرمَّزة في قنوات
 * RGB (height = R*256 + G + B/256 - 32768). osmdroid لا يملك محرك تظليل تضاريس جاهزاً (بعكس
 * MapLibre الذي يحسب hillshade داخلياً من مصدر raster-dem)، لذا تُحسَب هنا درجة تظليل تقريبية
 * يدوياً من شدة الانحدار بين بكسلات مجاورة، وتُرسم كطبقة سوداء شبه شفافة فوق الخريطة (الانحدار
 * الحاد يصبح أغمق، والمسطح يبقى شفافاً تقريباً). هذا تقريب بصري معقول رياضياً، لكن جودته الفعلية
 * على الشاشة لم تُختبَر بعين بشرية حقيقية في هذه البيئة (لا توجد وسيلة لعرض الخريطة هنا) — يحتاج
 * تأكيداً ميدانياً من المستخدم بعد التجربة الفعلية على الجهاز، وقد يحتاج ضبط شدة الشفافية لاحقاً.
 */
class OsmdroidTerrainOverlay(private val context: Context) : Overlay() {

    companion object {
        private const val TAG = "OsmdroidTerrainOverlay"
        private const val SOURCE_TILE_SIZE = 256
        private const val GRID_SIZE = 64
        private const val STEP = SOURCE_TILE_SIZE / GRID_SIZE
        private const val MAX_ALPHA = 90 // من أصل 255 — شفافية خفيفة/متحفِّظة عمداً، قابلة للتعديل لاحقاً
        private const val SLOPE_NORM = 40.0
        private const val MAX_CACHE_ENTRIES = 200
    }

    // تخزين مؤقت في الذاكرة لكل بلاطة مُعالَجة (Bitmap الظل الناتج)، أو null إن تأكَّد عدم توفرها على القرص.
    private val shadeCache = object : LinkedHashMap<String, Bitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    /** يُستدعى بعد اكتمال تنزيل جديد كي تُعاد قراءة أي بلاطة كانت مسجَّلة سابقاً كغير متوفرة. */
    fun invalidateCache() {
        shadeCache.clear()
    }

    override fun draw(canvas: Canvas?, mapView: MapView?, shadow: Boolean) {
        if (shadow || canvas == null || mapView == null) return
        val zoomDouble = mapView.zoomLevelDouble
        val z = zoomDouble.toInt()
        if (z < TerrainTileCache.MIN_ZOOM || z > TerrainTileCache.MAX_ZOOM) return

        val projection = mapView.projection
        val screenRect = mapView.getScreenRect(null)
        val nw = projection.fromPixels(screenRect.left, screenRect.top) as GeoPoint
        val se = projection.fromPixels(screenRect.right, screenRect.bottom) as GeoPoint

        val xStart = TerrainTileCache.tileX(nw.longitude, z)
        val xEnd = TerrainTileCache.tileX(se.longitude, z)
        val yStart = TerrainTileCache.tileY(nw.latitude, z)
        val yEnd = TerrainTileCache.tileY(se.latitude, z)

        val xLo = min(xStart, xEnd); val xHi = max(xStart, xEnd)
        val yLo = min(yStart, yEnd); val yHi = max(yStart, yEnd)

        // حماية من تكرار رسم مئات البلاطات حين يكون التكبير بعيداً جداً (تحسُّب خطأ غير متوقَّع).
        if ((xHi - xLo + 1).toLong() * (yHi - yLo + 1).toLong() > 512L) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val destRect = Rect()

        for (x in xLo..xHi) {
            for (y in yLo..yHi) {
                val bmp = getShadeBitmap(z, x, y) ?: continue

                val tileNorth = tileLat(y, z)
                val tileSouth = tileLat(y + 1, z)
                val tileWest = tileLon(x, z)
                val tileEast = tileLon(x + 1, z)

                val topLeft = projection.toPixels(GeoPoint(tileNorth, tileWest), null)
                val bottomRight = projection.toPixels(GeoPoint(tileSouth, tileEast), null)

                destRect.set(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
                if (destRect.width() <= 0 || destRect.height() <= 0) continue
                canvas.drawBitmap(bmp, null, destRect, paint)
            }
        }
    }

    private fun tileLon(x: Int, z: Int): Double = x.toDouble() / (1 shl z) * 360.0 - 180.0

    private fun tileLat(y: Int, z: Int): Double {
        val n = Math.PI - 2.0 * Math.PI * y.toDouble() / (1 shl z)
        return Math.toDegrees(Math.atan(Math.sinh(n)))
    }

    private fun getShadeBitmap(z: Int, x: Int, y: Int): Bitmap? {
        val key = "${z}_${x}_${y}"
        if (shadeCache.containsKey(key)) return shadeCache[key]

        val bmp = try {
            computeShadeBitmap(z, x, y)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed computing shade for $key", e)
            null
        }
        shadeCache[key] = bmp
        return bmp
    }

    private fun computeShadeBitmap(z: Int, x: Int, y: Int): Bitmap? {
        val file = TerrainTileCache.tileFile(context, z, x, y)
        if (!file.exists() || file.length() == 0L) return null

        val source = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        try {
            val w = source.width
            val h = source.height
            if (w < STEP * 2 || h < STEP * 2) return null

            val out = Bitmap.createBitmap(GRID_SIZE, GRID_SIZE, Bitmap.Config.ARGB_8888)
            for (gy in 0 until GRID_SIZE) {
                for (gx in 0 until GRID_SIZE) {
                    val sx = (gx * STEP).coerceIn(0, w - 2)
                    val sy = (gy * STEP).coerceIn(0, h - 2)

                    val eCenter = elevationAt(source, sx, sy)
                    val eRight = elevationAt(source, sx + 1, sy)
                    val eDown = elevationAt(source, sx, sy + 1)

                    val dx = eRight - eCenter
                    val dy = eDown - eCenter
                    val slope = Math.sqrt(dx * dx + dy * dy)

                    val alpha = (slope / SLOPE_NORM * MAX_ALPHA).coerceIn(0.0, MAX_ALPHA.toDouble()).toInt()
                    out.setPixel(gx, gy, Color.argb(alpha, 0, 0, 0))
                }
            }
            return out
        } finally {
            source.recycle()
        }
    }

    /** يفك ترميز Terrarium القياسي: height = R*256 + G + B/256 - 32768 (نطاق عام موثَّق، وليس تخميناً). */
    private fun elevationAt(bmp: Bitmap, x: Int, y: Int): Double {
        val pixel = bmp.getPixel(x, y)
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 256 + g + b / 256.0) - 32768.0
    }
}
