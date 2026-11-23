package com.alruwaili.dalilalbar

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.alruwaili.dalilalbar.nav.OverpassClient
import com.alruwaili.dalilalbar.nav.RoadNetwork
import com.alruwaili.dalilalbar.nav.RoadNetworkCache
import org.json.JSONObject
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.nio.charset.Charset

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

    // حجم خلية الاستعلام الواحدة بالدرجات: أصغر من MAX_BBOX_SIDE_DEGREES في RoutingEngine (١.٦) تحسُّباً،
    // كي يبقى كل استعلام Overpass لمنطقة بحجم دولة كاملة ضمن حجم عملي معقول لخادم مجاني (لا نضمن نجاح كل
    // خلية دائماً — خوادم Overpass العامة قد ترفض استعلاماً ثقيلاً بـ 429/504 حتى ضمن هذا الحجم، وهذا
    // مُفصح عنه صراحة وليس وعداً بنجاح مضمون 100% لمناطق بمساحة دولة كاملة).
    private const val CELL_SIZE_DEGREES = 1.2

    /**
     * يحمّل شبكة الطرق/الدروب الحقيقية (Overpass API) لكامل [def] محلياً، مقسَّمة شبكياً إلى خلايا
     * بحجم [CELL_SIZE_DEGREES] (منطقة بحجم دولة كاملة أكبر من حدّ استعلام Overpass الواحد العملي)، ثم
     * تدمج كل الخلايا وتُحفَظ عبر [RoadNetworkCache] كي يستخدمها RoutingEngine لاحقاً دون اتصال إنترنت.
     * تُنفَّذ الخلايا بالتتابع (وليس بالتوازي) عمداً لتخفيف الحمل على خوادم Overpass المجانية.
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
            for ((index, cell) in cells.withIndex()) {
                try {
                    networks.add(OverpassClient.fetchRoadNetwork(cell[0], cell[1], cell[2], cell[3]))
                } catch (e: OverpassClient.OverpassException) {
                    // خلية واحدة فشلت (ازدحام خادم مؤقت غالباً) لا تُسقط التحميل كله؛ يُكمَل بما نجح من
                    // خلايا أخرى، والنتيجة ستكون تغطية جزئية فحسب — لا تُخمَّن بيانات الخلية الفاشلة.
                    failures++
                }
                val percent = ((index + 1) * 100) / cells.size
                mainHandler.post { listener.onProgress(def, percent) }
            }
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
