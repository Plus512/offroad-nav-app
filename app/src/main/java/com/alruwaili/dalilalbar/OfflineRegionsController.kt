package com.alruwaili.dalilalbar

import android.content.Context
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
        fun onProgress(def: OfflineRegionDef, percent: Int)
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
            Double.POSITIVE_INFINITY, // يقتصر تلقائياً على أعلى مستوى تكبير تقدّمه بيانات OpenFreeMap لكل مصدر
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
                                listener.onProgress(def, percent)
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
