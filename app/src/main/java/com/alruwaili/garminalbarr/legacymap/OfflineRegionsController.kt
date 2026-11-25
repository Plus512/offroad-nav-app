package com.alruwaili.garminalbarr.legacymap

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.alruwaili.garminalbarr.legacymap.nav.OverpassClient
import com.alruwaili.garminalbarr.legacymap.nav.RoadNetwork
import com.alruwaili.garminalbarr.legacymap.nav.RoadNetworkCache
import com.alruwaili.garminalbarr.legacymap.nav.TerrainTileCache
import org.json.JSONObject
import java.io.File
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * يتحكم بتحميل مناطق الخريطة دون انترنت عبر OfflineManager الخاص بـ MapLibre.
 * يخزّن معرّف المنطقة (OfflineRegionDef.id) داخل الحقل الوصفي (metadata) لكل منطقة محمَّلة، كي يمكن
 * التعرف لاحقاً على أي المناطق محمَّلة فعلاً دون الحاجة لقاعدة بيانات منفصلة.
 */
class OfflineRegionsController(private val context: Context) {

    companion object {
        private const val STYLE_URL = "asset://style_dalil.json"
        private val JSON_CHARSET: Charset = Charset.forName("UTF-8")
        private const val JSON_FIELD_REGION_ID = "regionId"

        // حجم خلية الاستعلام الواحدة بالدرجات: أصغر من MAX_BBOX_SIDE_DEGREES في RoutingEngine (١.٦)
        // تحسُّباً، كي يبقى كل استعلام Overpass لمنطقة بحجم دولة كاملة ضمن حجم عملي معقول لخادم مجاني
        // (لا نضمن نجاح كل خلية دائماً — خوادم Overpass العامة قد ترفض استعلاماً ثقيلاً بـ 429/504 حتى
        // ضمن هذا الحجم، وهذا مُفصح عنه صراحة وليس وعداً بنجاح مضمون 100% لمناطق بمساحة دولة كاملة).
        private const val CELL_SIZE_DEGREES = 1.2

        // طُلب صراحةً (٢٣ نوفمبر) بعد أن أبلغ المستخدم ببطء شديد جداً وظهور أول تحديث للنسبة بعد
        // دقائق: كانت الخلايا تُجلب الواحدة تلو الأخرى (تعليق صريح سابق في هذا الملف: "عمداً لتخفيف
        // الحمل على خوادم Overpass"). مع خلية واحدة فقط في كل مرة، وحتى ٣ خوادم احتياطية × مهلة
        // تصل ٣٦ ثانية لكل خادم عند فشله بالكامل (انظر OverpassClient)، كان أول تحديث فعلي للشاشة قد
        // يتأخر دقيقة إلى دقيقتين، ومجموع الوقت لمنطقة بحجم الأردن (نحو ١٦ خلية) قد يبلغ عشرات
        // الدقائق في أسوأ الأحوال. جلب ٤ خلايا معاً بالتوازي (بدل واحدة) يقلّص الزمن الكلي إلى نحو
        // الربع تقريباً دون تغيير منطق الخادم الاحتياطي أو التسامح مع فشل خلية واحدة، مقابل خطر أعلى
        // قليلاً لرفض خادم Overpass المجاني لبعض الطلبات المتزامنة (مُعالَج أصلاً: فشل خلية واحدة لا
        // يُسقط التحميل كله، فقط يجعل تلك الخلية جزءاً من "failedCells").
        private const val PARALLEL_CELL_DOWNLOADS = 4

        // مصدر بلاطات الارتفاع: نفس مصدر AWS Terrarium العام المستخدم في «الدليلة بلس» لمحرك العرض
        // ثلاثي الأبعاد (رابط عام موثَّق، لا تخمين). أقصى تكبير توفّره هذه البلاطات فعلياً هو 15.
        private const val TERRAIN_URL_FMT = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png"

        // نطاق تكبير التضاريس المحفوظة دون انترنت لهذا التبويب: 6 (نفس minzoom لطبقة hillshade_relief
        // في style_dalil.json) حتى 10 فقط — وليس حتى أقصى ما يوفره المصدر (15)، لأن المنطقة هنا بحجم
        // دولة كاملة (انظر OfflineRegionDef)، وتغطية كل مستويات التكبير لمساحة كهذه قد تبلغ عشرات
        // الجيجابايت ولا قيمة عملية حقيقية منها (طبقة التظليل hillshade تُقرأ على نطاق واسع أصلاً).
        // صريح للمستخدم: عند تكبير الخريطة لمستوى أعلى من 10 دون اتصال إنترنت، قد لا تظهر طبقة
        // التظليل في تلك البقعة تحديداً حتى يعود الاتصال — سلوك "أقرب بلاطة محفوظة" الدقيق هنا يعتمد
        // على مكتبة MapLibre نفسها ولم يتسنَّ التحقق منه ببناء فعلي في هذه البيئة.
        private const val TERRAIN_MIN_ZOOM = 6
        private const val TERRAIN_MAX_ZOOM = 10
    }

    interface ProgressListener {
        /** [region] نفسه مُمرَّر هنا (وليس فقط النسبة) كي تستطيع واجهة المستخدم استدعاء pauseDownload/
         * resumeDownload/cancelDownload عليه لاحقاً (أزرار توقف مؤقت/إلغاء/خلفية في شاشة التقدّم). */
        fun onProgress(def: OfflineRegionDef, percent: Int, region: OfflineRegion)
        fun onComplete(def: OfflineRegionDef)
        fun onDownloadError(def: OfflineRegionDef, message: String)
    }

    private val manager get() = OfflineManager.getInstance(context)

    /** المناطق المحمَّلة فعلاً حالياً (بحسب قاعدة MapLibre للتحميلات دون انترنت)، بصرف النظر عن اكتمالها. */
    fun listDownloadedIds(callback: (Set<String>) -> Unit) {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val ids = (offlineRegions ?: emptyArray()).mapNotNull { regionIdOf(it) }.toSet()
                callback(ids)
            }

            override fun onError(error: String) {
                callback(emptySet())
            }
        })
    }

    /** كل المناطق المحمَّلة فعلاً حالياً، مع كائن OfflineRegion نفسه (مطلوب لحذفها). تُستخدم في شاشة الإعدادات. */
    fun listDownloadedRegions(callback: (List<Pair<String, OfflineRegion>>) -> Unit) {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val pairs = (offlineRegions ?: emptyArray()).mapNotNull { region ->
                    regionIdOf(region)?.let { id -> id to region }
                }
                callback(pairs)
            }

            override fun onError(error: String) {
                callback(emptyList())
            }
        })
    }

    /** يحذف منطقة محمَّلة دون انترنت نهائياً (تحرير مساحة التخزين). */
    fun deleteRegion(region: OfflineRegion, onDone: (success: Boolean) -> Unit) {
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                onDone(true)
            }

            override fun onError(error: String) {
                onDone(false)
            }
        })
    }

    /** يوقف التحميل مؤقتاً (الأمر "توقف مؤقت" في شاشة التقدّم) — يبقى ما تم تحميله، ويمكن استئنافه لاحقاً. */
    fun pauseDownload(region: OfflineRegion) {
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
    }

    /** يستأنف تحميلاً مُوقَفاً مؤقتاً. */
    fun resumeDownload(region: OfflineRegion) {
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    /** يُلغي التحميل كلياً ويحذف ما تم تنزيله منه حتى الآن (الأمر "إلغاء" في شاشة التقدّم). */
    fun cancelDownload(region: OfflineRegion, onDone: (Boolean) -> Unit) {
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        deleteRegion(region, onDone)
    }

    interface RoadNetworkProgressListener {
        /** [percent] نسبة عدد الخلايا الجغرافية المكتملة من إجمالي خلايا المنطقة (وليس حجم بايتات). */
        fun onProgress(def: OfflineRegionDef, percent: Int)
        /** [failedCells]/[totalCells]: إن كان failedCells > 0 فالتغطية جزئية فقط (بعضها سيعتمد على
         * الإنترنت المباشر وقت التنقّل لاحقاً)، وليس خطأً كاملاً يستدعي onDownloadError. */
        fun onComplete(def: OfflineRegionDef, failedCells: Int, totalCells: Int)
        fun onDownloadError(def: OfflineRegionDef, message: String)
    }

    /**
     * يحمّل شبكة الطرق/الدروب الحقيقية (Overpass API) لكامل [def] محلياً، مقسَّمة شبكياً إلى خلايا
     * بحجم [CELL_SIZE_DEGREES] (منطقة بحجم دولة كاملة أكبر من حدّ استعلام Overpass الواحد العملي)، ثم
     * تدمج كل الخلايا وتُحفَظ عبر [RoadNetworkCache] كي يستخدمها RoutingEngine لاحقاً دون اتصال إنترنت.
     * تُنفَّذ [PARALLEL_CELL_DOWNLOADS] خلايا معاً بالتوازي (انظر التعليق على الثابت نفسه لسبب هذا
     * التغيير)، لا خلية واحدة بالتتابع كما كان سابقاً.
     */
    fun downloadRoadNetwork(def: OfflineRegionDef, listener: RoadNetworkProgressListener) {
        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            val b = def.bounds
            val cells = ArrayList<DoubleArray>()
            var south = b.latitudeSouth
            while (south < b.latitudeNorth) {
                val north = minOf(south + CELL_SIZE_DEGREES, b.latitudeNorth)
                var west = b.longitudeWest
                while (west < b.longitudeEast) {
                    val east = minOf(west + CELL_SIZE_DEGREES, b.longitudeEast)
                    cells.add(doubleArrayOf(south, west, north, east))
                    west += CELL_SIZE_DEGREES
                }
                south += CELL_SIZE_DEGREES
            }
            if (cells.isEmpty()) {
                mainHandler.post { listener.onDownloadError(def, "حدود المنطقة غير صالحة") }
                return@Thread
            }
            val networks = ArrayList<RoadNetwork>()
            var failures = 0
            val pool = Executors.newFixedThreadPool(minOf(PARALLEL_CELL_DOWNLOADS, cells.size))
            val completionService = ExecutorCompletionService<RoadNetwork?>(pool)
            cells.forEach { cell ->
                completionService.submit(Callable {
                    try {
                        OverpassClient.fetchRoadNetwork(cell[0], cell[1], cell[2], cell[3])
                    } catch (e: Exception) {
                        // خلية واحدة فشلت (ازدحام خادم مؤقت غالباً، أو أي خطأ آخر غير متوقَّع) لا تُسقط
                        // التحميل كله؛ يُكمَل بما نجح من خلايا أخرى، والنتيجة تغطية جزئية فحسب — لا
                        // تُخمَّن بيانات الخلية الفاشلة. null هنا فقط إشارة فشل هذه الخلية بالذات.
                        null
                    }
                })
            }
            // completionService.take() تُعيد كل خلية فور اكتمالها (ليس بترتيب الإرسال)، فالنسبة على
            // الشاشة تتحرك بمجرد انتهاء أول ٤ خلايا متوازية، لا بعد انتظار كل خلية سابقة بالتتابع.
            for (completed in 1..cells.size) {
                val result = completionService.take().get()
                if (result != null) networks.add(result) else failures++
                val percent = (completed * 100) / cells.size
                mainHandler.post { listener.onProgress(def, percent) }
            }
            pool.shutdown()
            if (networks.isEmpty()) {
                mainHandler.post { listener.onDownloadError(def, "تعذّر جلب أي بيانات طرق حقيقية (فشلت كل الخلايا: $failures)") }
                return@Thread
            }
            val merged = OverpassClient.merge(networks)
            RoadNetworkCache.save(context, def.id, def.bounds, merged)
            mainHandler.post {
                listener.onComplete(def, failures, cells.size)
            }
        }.start()
    }

    interface TerrainProgressListener {
        fun onProgress(def: OfflineRegionDef, percent: Int)
        /** [failedTiles] > 0 يعني تغطية جزئية فقط (بعض البلاطات فشل جلبها)، وليس خطأً كاملاً. */
        fun onComplete(def: OfflineRegionDef, failedTiles: Int, totalTiles: Int)
        fun onDownloadError(def: OfflineRegionDef, message: String)
    }

    // تحويل إحداثيات جغرافية ← رقم بلاطة Slippy Map القياسي (نفس حساب GLTerrainData.kt في
    // «الدليلة بلس» تماماً، لكن مكرَّر هنا لأنه تطبيق مختلف لا يشترك بنفس وحدة الكود).
    private fun tileX(lon: Double, z: Int): Int =
        floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    private fun tileY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / Math.PI) / 2.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }

    private fun terrainTilesFor(def: OfflineRegionDef): List<IntArray> {
        val b = def.bounds
        val out = ArrayList<IntArray>()
        for (z in TERRAIN_MIN_ZOOM..TERRAIN_MAX_ZOOM) {
            val x0 = tileX(b.longitudeWest, z); val x1 = tileX(b.longitudeEast, z)
            val y0 = tileY(b.latitudeNorth, z); val y1 = tileY(b.latitudeSouth, z)
            for (x in min(x0, x1)..max(x0, x1)) for (y in min(y0, y1)..max(y0, y1)) out.add(intArrayOf(z, x, y))
        }
        return out
    }

    /**
     * يحمّل بلاطات ارتفاع التضاريس (AWS Terrarium) لكامل [def] ضمن نطاق [TERRAIN_MIN_ZOOM]..
     * [TERRAIN_MAX_ZOOM]، ويحفظها عبر [TerrainTileCache] كي تعمل طبقة hillshade_relief دون انترنت.
     * نفس أسلوب [downloadRoadNetwork] (تنزيل متوازٍ، تسامح مع فشل عنصر واحد دون إسقاط التحميل كله).
     */
    fun downloadTerrain(def: OfflineRegionDef, listener: TerrainProgressListener) {
        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            val tiles = terrainTilesFor(def)
            if (tiles.isEmpty()) {
                mainHandler.post { listener.onDownloadError(def, "حدود المنطقة غير صالحة") }
                return@Thread
            }
            var failures = 0
            val pool = Executors.newFixedThreadPool(minOf(PARALLEL_CELL_DOWNLOADS, tiles.size))
            val completionService = ExecutorCompletionService<Boolean>(pool)
            tiles.forEach { t ->
                completionService.submit(Callable {
                    val (z, x, y) = Triple(t[0], t[1], t[2])
                    val f = TerrainTileCache.tileFile(context, def.id, z, x, y)
                    if (f.exists()) return@Callable true
                    var ok = false
                    for (attempt in 0 until 2) {
                        try {
                            val cn = URL(String.format(TERRAIN_URL_FMT, z, x, y)).openConnection() as HttpURLConnection
                            try {
                                cn.connectTimeout = 15000
                                cn.readTimeout = 25000
                                cn.setRequestProperty("User-Agent", "DalilAlBar/TerrainCache")
                                when (cn.responseCode) {
                                    200 -> {
                                        val bytes = cn.inputStream.use { it.readBytes() }
                                        val tmp = File(f.parentFile, f.name + ".tmp")
                                        tmp.writeBytes(bytes)
                                        tmp.renameTo(f)
                                        ok = true
                                    }
                                    404 -> ok = true // لا بلاطة ارتفاع هنا (بحر غالباً)؛ لا تُعدّ فشلاً
                                }
                            } finally {
                                cn.disconnect()
                            }
                        } catch (e: Exception) {
                        }
                        if (ok) break
                    }
                    ok
                })
            }
            for (completed in 1..tiles.size) {
                val ok = try { completionService.take().get() } catch (e: Exception) { false }
                if (!ok) failures++
                val percent = (completed * 100) / tiles.size
                mainHandler.post { listener.onProgress(def, percent) }
            }
            pool.shutdown()
            TerrainTileCache.writeManifest(context, def.id, TERRAIN_MIN_ZOOM, TERRAIN_MAX_ZOOM, tiles.size, failures)
            mainHandler.post { listener.onComplete(def, failures, tiles.size) }
        }.start()
    }

    private fun regionIdOf(region: OfflineRegion): String? = try {
        val json = JSONObject(String(region.metadata, JSON_CHARSET))
        json.optString(JSON_FIELD_REGION_ID).ifBlank { null }
    } catch (e: Exception) {
        null
    }

    fun startDownload(def: OfflineRegionDef, listener: ProgressListener) {
        val definition = OfflineTilePyramidRegionDefinition(
            STYLE_URL,
            def.bounds,
            5.0,
            // 14.0: أعلى مستوى تكبير تقدّمه بيانات OpenFreeMap الشعاعية (planet) فعلياً — تم التحقق منه
            // من ملف النمط الأصلي (maxzoom:14 لكل الطبقات التفصيلية). كانت القيمة سابقاً
            // Double.POSITIVE_INFINITY، وهذا على الأغلب هو سبب تجمّد التحميل عند 0% دون أي استجابة:
            // OfflineManager يحاول حساب عدد البلاطات المطلوبة عبر مدى غير محدود من مستويات التكبير،
            // فلا تكتمل العملية أبداً ولا يصل أي حدث onStatusChanged لتحديث الواجهة.
            14.0,
            context.resources.displayMetrics.density
        )
        val metadata = JSONObject().put(JSON_FIELD_REGION_ID, def.id).toString().toByteArray(JSON_CHARSET)
        manager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            val percent = if (status.requiredResourceCount > 0) {
                                (100.0 * status.completedResourceCount / status.requiredResourceCount).toInt()
                            } else 0
                            if (status.isComplete) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                listener.onComplete(def)
                            } else {
                                listener.onProgress(def, percent, offlineRegion)
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            listener.onDownloadError(def, error.message)
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            listener.onDownloadError(def, "تجاوز حد عدد البلاطات المسموح ($limit)")
                        }
                    })
                }

                override fun onError(error: String) {
                    listener.onDownloadError(def, error)
                }
            }
        )
    }
}
