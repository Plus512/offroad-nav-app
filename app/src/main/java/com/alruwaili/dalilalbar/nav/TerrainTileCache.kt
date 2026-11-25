package com.alruwaili.dalilalbar.nav

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * تخزين محلي (على جهاز المستخدم) لبلاطات ارتفاع التضاريس (Terrarium PNG، نفس المصدر العام
 * المستخدم في «الدليلة بلس») لمنطقة/دولة بأكملها، كي تعمل طبقة التظليل (hillshade_relief) في
 * style_dalil.json دون اتصال إنترنت لاحقاً — تماماً بنفس فلسفة [RoadNetworkCache] لبيانات الطرق:
 * لا تُخمَّن بلاطة، بل تُحفَظ فعلياً كما وردت من الخادم، وتُقرأ لاحقاً من القرص مباشرة.
 *
 * ملاحظة صدق مهمة: هذا تخزين منفصل تماماً عن تنزيل OfflineManager الخاص بـ MapLibre (تبويب
 * "خريطة")، وليس اعتماداً على افتراض غير مؤكَّد بأن ذلك التنزيل يشمل مصدر terrain_dem الشعاعي
 * ضمنياً (لم يتسنَّ التحقق من هذا السلوك الداخلي لمكتبة MapLibre ببناء فعلي هنا). لذلك بُنيت هذه
 * الآلية لتضمن تنزيلاً حقيقياً مستقلاً للبلاطات، بصرف النظر عمّا قد يفعله OfflineManager داخلياً.
 *
 * التنسيق: مجلد فرعي لكل منطقة (region id) يحوي ملفات PNG الفعلية كما وردت من الخادم
 * (بتسمية "{z}_{x}_{y}.png")، + ملف بيان (manifest) JSON واحد في الجذر يسجّل نطاق
 * مستويات التكبير المحمَّلة وعدد البلاطات الفاشلة، ليُعرَض ذلك صراحةً للمستخدم بدل الادّعاء بتغطية
 * كاملة لا توجد فعلاً.
 */
object TerrainTileCache {
    private const val DIR_NAME = "terrain_cache"

    private fun rootDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun regionDir(context: Context, regionId: String): File =
        File(rootDir(context), regionId).apply { if (!exists()) mkdirs() }

    private fun manifestFile(context: Context, regionId: String): File =
        File(rootDir(context), "$regionId.json")

    fun tileFile(context: Context, regionId: String, z: Int, x: Int, y: Int): File =
        File(regionDir(context, regionId), "${z}_${x}_${y}.png")

    fun isCached(context: Context, regionId: String): Boolean = manifestFile(context, regionId).exists()

    /** نطاق مستويات التكبير المحفوظة فعلياً (أو null إن لم تُحمَّل المنطقة)، ليُعرض للمستخدم بصدق. */
    fun zoomRange(context: Context, regionId: String): IntArray? {
        val f = manifestFile(context, regionId)
        if (!f.exists()) return null
        return try {
            val j = JSONObject(f.readText(Charsets.UTF_8))
            intArrayOf(j.getInt("zMin"), j.getInt("zMax"))
        } catch (e: Exception) {
            null
        }
    }

    /** عدد البلاطات التي فشل جلبها (شبكة ضعيفة أو خطأ خادم وقت التحميل)، أو 0 إن لم تُسجَّل. */
    fun failedTileCount(context: Context, regionId: String): Int {
        val f = manifestFile(context, regionId)
        if (!f.exists()) return 0
        return try { JSONObject(f.readText(Charsets.UTF_8)).optInt("failedTiles", 0) } catch (e: Exception) { 0 }
    }

    /** حجم كل ملفات PNG المخزَّنة فعلياً لهذه المنطقة بالميغابايت (قراءة حجم حقيقي، وليس تقديراً). */
    fun cachedSizeMb(context: Context, regionId: String): Double? {
        val f = manifestFile(context, regionId)
        if (!f.exists()) return null
        val dir = regionDir(context, regionId)
        val files = dir.listFiles() ?: return 0.0
        val bytes = files.sumOf { it.length() }
        return bytes / (1024.0 * 1024.0)
    }

    fun writeManifest(context: Context, regionId: String, zMin: Int, zMax: Int, totalTiles: Int, failedTiles: Int) {
        val j = JSONObject()
        j.put("regionId", regionId)
        j.put("zMin", zMin)
        j.put("zMax", zMax)
        j.put("totalTiles", totalTiles)
        j.put("failedTiles", failedTiles)
        manifestFile(context, regionId).writeText(j.toString(), Charsets.UTF_8)
    }

    /** يحذف بيانات التضاريس المحفوظة لهذه المنطقة بالكامل (الملفات + البيان) لتحرير التخزين. */
    fun deleteCache(context: Context, regionId: String): Boolean {
        val dir = regionDir(context, regionId)
        dir.listFiles()?.forEach { it.delete() }
        val dirOk = !dir.exists() || dir.delete()
        val manifestOk = manifestFile(context, regionId).let { !it.exists() || it.delete() }
        return dirOk && manifestOk
    }
}
