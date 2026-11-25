package com.alruwaili.garminalbarr.map

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/**
 * تخزين محلي لبلاطات ارتفاع التضاريس (AWS Terrarium PNG — نفس المصدر العام المستخدم سابقاً في
 * دليل البر لتبويب "تضاريس")، كي تعمل طبقة التظليل (hillshade) دون اتصال إنترنت لاحقاً.
 *
 * التخزين هنا عالمي (مسطَّح، وليس مجلداً منفصلاً لكل دولة): رقم البلاطة {z}_{x}_{y} فريد عالمياً
 * (نظام Slippy Map القياسي)، فتخزينها في مجلد واحد مشترك يمنع إعادة تنزيل نفس البلاطة أكثر من مرة
 * عندما تتشارك دولتان حدوداً (تماماً بنفس فلسفة مشاركة ملفات .rd5 بين الدول في [MapFileManager]).
 * لكل دولة ملف بيان (manifest) JSON خاص بها يسجّل أنها نزّلت فعلاً كل بلاطاتها، لعرض حالة التحميل
 * والمساحة بصدق — لا يُفترض أن البلاطة موجودة، بل تُتحقَّق فعلياً من القرص.
 */
object TerrainTileCache {
    private const val TILES_DIR = "terrain_tiles"
    private const val MANIFEST_DIR = "terrain_manifest"

    // نطاق تكبير التضاريس المحفوظة دون انترنت: من 6 حتى 10 فقط (نفس النطاق المستخدم سابقاً في
    // دليل البر) — مستوى أعلى من 10 لمساحة بحجم دولة كاملة يعني عشرات الجيجابايت دون قيمة عملية
    // حقيقية (طبقة التظليل تُقرأ على نطاق واسع أصلاً وليست خريطة تفاصيل دقيقة).
    const val MIN_ZOOM = 6
    const val MAX_ZOOM = 10

    private fun tilesRoot(context: Context): File =
        File(context.filesDir, TILES_DIR).apply { if (!exists()) mkdirs() }

    private fun manifestRoot(context: Context): File =
        File(context.filesDir, MANIFEST_DIR).apply { if (!exists()) mkdirs() }

    private fun manifestFile(context: Context, regionId: String): File =
        File(manifestRoot(context), "$regionId.json")

    fun tileFile(context: Context, z: Int, x: Int, y: Int): File =
        File(tilesRoot(context), "${z}_${x}_${y}.png")

    // تحويل إحداثيات جغرافية ← رقم بلاطة Slippy Map القياسي (نفس حساب OfflineMapEngine/Mapsforge).
    fun tileX(lon: Double, z: Int): Int =
        floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    fun tileY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / Math.PI) / 2.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }

    /** قائمة كل بلاطات (z,x,y) الواقعة ضمن حدود [south,west,north,east] عبر [MIN_ZOOM]..[MAX_ZOOM]. */
    fun tilesForBounds(south: Double, west: Double, north: Double, east: Double): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (z in MIN_ZOOM..MAX_ZOOM) {
            val x0 = tileX(west, z); val x1 = tileX(east, z)
            val y0 = tileY(north, z); val y1 = tileY(south, z)
            for (x in min(x0, x1)..max(x0, x1)) for (y in min(y0, y1)..max(y0, y1)) out.add(intArrayOf(z, x, y))
        }
        return out
    }

    fun isRegionCached(context: Context, regionId: String): Boolean = manifestFile(context, regionId).exists()

    fun failedTileCount(context: Context, regionId: String): Int {
        val f = manifestFile(context, regionId)
        if (!f.exists()) return 0
        return try { JSONObject(f.readText(Charsets.UTF_8)).optInt("failedTiles", 0) } catch (e: Exception) { 0 }
    }

    fun totalTileCount(context: Context, regionId: String): Int {
        val f = manifestFile(context, regionId)
        if (!f.exists()) return 0
        return try { JSONObject(f.readText(Charsets.UTF_8)).optInt("totalTiles", 0) } catch (e: Exception) { 0 }
    }

    fun writeManifest(context: Context, regionId: String, tileList: List<IntArray>, failedTiles: Int) {
        val j = JSONObject()
        j.put("regionId", regionId)
        j.put("zMin", MIN_ZOOM)
        j.put("zMax", MAX_ZOOM)
        j.put("totalTiles", tileList.size)
        j.put("failedTiles", failedTiles)
        manifestFile(context, regionId).writeText(j.toString(), Charsets.UTF_8)
    }

    fun deleteManifest(context: Context, regionId: String): Boolean {
        val f = manifestFile(context, regionId)
        return !f.exists() || f.delete()
    }

    /** حجم كل بلاطات التضاريس المخزَّنة فعلياً على الجهاز (بالبايت)، بصرف النظر عن أي منطقة تخصّها. */
    fun totalCachedSizeBytes(context: Context): Long {
        val dir = tilesRoot(context)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * يحذف بلاطات [regionId] التي لا تحتاجها أي منطقة أخرى محمَّلة حالياً، ويحذف بيانها.
     * [otherCachedRegionBounds] حدود كل منطقة أخرى يُعتبر التضاريس فيها محمَّلاً فعلاً حالياً.
     */
    fun deleteRegionTiles(
        context: Context,
        regionId: String,
        regionBounds: DoubleArray, // [south, west, north, east]
        otherCachedRegionBounds: List<DoubleArray>
    ) {
        val myTiles = tilesForBounds(regionBounds[0], regionBounds[1], regionBounds[2], regionBounds[3])
        val sharedKeys = otherCachedRegionBounds
            .flatMap { b -> tilesForBounds(b[0], b[1], b[2], b[3]) }
            .map { "${it[0]}_${it[1]}_${it[2]}" }
            .toHashSet()
        for (t in myTiles) {
            val key = "${t[0]}_${t[1]}_${t[2]}"
            if (key in sharedKeys) continue
            tileFile(context, t[0], t[1], t[2]).delete()
        }
        deleteManifest(context, regionId)
    }
}

/**
 * قراءة ارتفاع نقطة واحدة (موقع المستخدم الحالي غالبًا) من بلاطة ارتفاع Terrarium محفوظة محلياً
 * عند أدق تكبير متاح ([TerrainTileCache.MAX_ZOOM] = 10)، لعرضها في لوحة "تحليل التضاريس" ضمن
 * شاشة "المساعد الرقمي". تُعيد null إن لم تكن بلاطة التضاريس لتلك المنطقة محمَّلة أصلاً (لا تخمين
 * بلا بيانات). نفس صيغة فك الترميز الموثَّقة والمستخدمة فعلياً في [OsmdroidTerrainOverlay]
 * (height = R*256 + G + B/256 - 32768)، وموضع البكسل داخل البلاطة بتقريب خطي بسيط بين حدودها —
 * كافٍ لعرض رقم تقريبي واحد، وليس حسابًا مساحيًا دقيقًا.
 */
object TerrainElevationLookup {
    private fun tileLonEdge(x: Int, z: Int): Double = x.toDouble() / (1 shl z) * 360.0 - 180.0
    private fun tileLatEdge(y: Int, z: Int): Double {
        val n = Math.PI - 2.0 * Math.PI * y.toDouble() / (1 shl z)
        return Math.toDegrees(atan(sinh(n)))
    }

    fun currentElevationMeters(context: Context, lat: Double, lon: Double): Double? {
        val z = TerrainTileCache.MAX_ZOOM
        val x = TerrainTileCache.tileX(lon, z)
        val y = TerrainTileCache.tileY(lat, z)
        val file = TerrainTileCache.tileFile(context, z, x, y)
        if (!file.exists() || file.length() == 0L) return null
        val bmp = try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            val w = bmp.width
            val h = bmp.height
            val lonWest = tileLonEdge(x, z)
            val lonEast = tileLonEdge(x + 1, z)
            val latNorth = tileLatEdge(y, z)
            val latSouth = tileLatEdge(y + 1, z)
            val px = (((lon - lonWest) / (lonEast - lonWest)) * w).toInt().coerceIn(0, w - 1)
            val py = (((latNorth - lat) / (latNorth - latSouth)) * h).toInt().coerceIn(0, h - 1)
            val pixel = bmp.getPixel(px, py)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            (r * 256 + g + b / 256.0) - 32768.0
        } catch (e: Exception) {
            null
        } finally {
            bmp.recycle()
        }
    }
}
