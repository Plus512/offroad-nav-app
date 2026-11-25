package com.alruwaili.garminalbarr.legacymap.nav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import java.io.File

/**
 * تخزين محلي (على جهاز المستخدم) لشبكة طرق/دروب حقيقية سبق تحميلها عبر Overpass API لمنطقة بأكملها
 * (زر "تحميل بيانات مسار السيارة" الجديد)، كي يعمل توجيه السيارة لاحقاً دون الحاجة لاتصال إنترنت وقت
 * التنقّل الفعلي — وأيضاً يتجاوز أي عطل/حجب لخوادم Overpass المباشرة وقت الاستخدام، لأن البيانات هنا
 * محفوظة مسبقاً فعلياً على الجهاز (لا تُخمَّن ولا تُنشأ، بل هي نفس بيانات OpenStreetMap الحقيقية التي
 * جُلبت فعلاً عبر Overpass وقت التحميل).
 *
 * التنسيق: ملف JSON واحد لكل منطقة (region id)، يحتوي حدود المنطقة + كل العقد والخطوط كما وردت فعلياً،
 * بنفس حقول [OsmWay]/[RoadNetwork] دون أي تحويل يفقد معلومة.
 */
object RoadNetworkCache {
    private const val DIR_NAME = "road_cache"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun file(context: Context, regionId: String): File = File(dir(context), "$regionId.json")

    fun isCached(context: Context, regionId: String): Boolean = file(context, regionId).exists()

    fun deleteCache(context: Context, regionId: String): Boolean {
        val f = file(context, regionId)
        return !f.exists() || f.delete()
    }

    /** حجم الملف المخزَّن بالميغابايت (لعرضه للمستخدم في شاشة الإعدادات لاحقاً إن رغب)، أو null إن لم يكن محمَّلاً. */
    fun cachedSizeMb(context: Context, regionId: String): Double? {
        val f = file(context, regionId)
        if (!f.exists()) return null
        return f.length() / (1024.0 * 1024.0)
    }

    fun save(context: Context, regionId: String, bounds: LatLngBounds, network: RoadNetwork) {
        val root = JSONObject()
        root.put("regionId", regionId)
        root.put("south", bounds.latitudeSouth)
        root.put("west", bounds.longitudeWest)
        root.put("north", bounds.latitudeNorth)
        root.put("east", bounds.longitudeEast)

        val nodesObj = JSONObject()
        for ((id, coord) in network.nodes) {
            val arr = JSONArray()
            arr.put(coord[0]); arr.put(coord[1])
            nodesObj.put(id.toString(), arr)
        }
        root.put("nodes", nodesObj)

        val waysArr = JSONArray()
        for (way in network.ways) {
            val w = JSONObject()
            w.put("id", way.id)
            val nodeIdsArr = JSONArray()
            for (nid in way.nodeIds) nodeIdsArr.put(nid)
            w.put("nodeIds", nodeIdsArr)
            way.highway?.let { w.put("highway", it) }
            way.surface?.let { w.put("surface", it) }
            way.tracktype?.let { w.put("tracktype", it) }
            way.oneway?.let { w.put("oneway", it) }
            waysArr.put(w)
        }
        root.put("ways", waysArr)

        file(context, regionId).writeText(root.toString(), Charsets.UTF_8)
    }

    private fun loadOne(f: File): Pair<LatLngBounds, RoadNetwork>? {
        return try {
            val root = JSONObject(f.readText(Charsets.UTF_8))
            val bounds = LatLngBounds.from(
                root.getDouble("north"), root.getDouble("east"),
                root.getDouble("south"), root.getDouble("west")
            )
            val nodesObj = root.getJSONObject("nodes")
            val nodes = HashMap<Long, DoubleArray>(nodesObj.length())
            val keys = nodesObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = nodesObj.getJSONArray(k)
                nodes[k.toLong()] = doubleArrayOf(arr.getDouble(0), arr.getDouble(1))
            }
            val waysArr = root.getJSONArray("ways")
            val ways = ArrayList<OsmWay>(waysArr.length())
            for (i in 0 until waysArr.length()) {
                val w = waysArr.getJSONObject(i)
                val nodeIdsArr = w.getJSONArray("nodeIds")
                val nodeIds = LongArray(nodeIdsArr.length()) { idx -> nodeIdsArr.getLong(idx) }
                ways.add(
                    OsmWay(
                        id = w.getLong("id"),
                        nodeIds = nodeIds,
                        highway = w.optString("highway").ifEmpty { null },
                        surface = w.optString("surface").ifEmpty { null },
                        tracktype = w.optString("tracktype").ifEmpty { null },
                        oneway = w.optString("oneway").ifEmpty { null }
                    )
                )
            }
            bounds to RoadNetwork(nodes, ways)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * يحمّل ويدمج كل الملفات المخزَّنة محلياً التي تتقاطع حدودها مع [south],[west],[north],[east]
     * المطلوبة لحساب مسار معيّن. يُعاد null إن لم توجد أي منطقة محمَّلة تغطي هذا الموقع إطلاقاً (عندها
     * يُكمِل RoutingEngine بطلب Overpass المباشر كما كان، دون أي تغيير في السلوك الحالي).
     */
    fun loadCovering(context: Context, south: Double, west: Double, north: Double, east: Double): RoadNetwork? {
        val dirFile = dir(context)
        val files = dirFile.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return null
        if (files.isEmpty()) return null
        val matched = ArrayList<RoadNetwork>()
        for (f in files) {
            val pair = loadOne(f) ?: continue
            val (b, network) = pair
            val latOverlap = b.latitudeSouth <= north && b.latitudeNorth >= south
            val lonOverlap = b.longitudeWest <= east && b.longitudeEast >= west
            if (latOverlap && lonOverlap) matched.add(network)
        }
        if (matched.isEmpty()) return null
        return OverpassClient.merge(matched)
    }
}
