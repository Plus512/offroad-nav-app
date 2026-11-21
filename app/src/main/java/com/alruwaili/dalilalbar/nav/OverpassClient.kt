package com.alruwaili.dalilalbar.nav

import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * يجلب شبكة الطرق/الدروب الحقيقية من خدمة Overpass API الرسمية التابعة لمشروع OpenStreetMap
 * (overpass-api.de) — وهي نفس قاعدة بيانات OpenStreetMap الحيّة التي تُبنى منها بلاطات OpenFreeMap
 * المستخدمة في عرض الخريطة، فالبيانات هنا حقيقية دائماً وليست مخمّنة أو مصطنعة بأي شكل.
 *
 * يُستعلم فقط عن عناصر "way" الحاملة لوسم highway (طرق/دروب صالحة للسيارات، مع استبعاد ممرات
 * المشاة والدراجات والسلالم وما شابه)، ضمن مربع جغرافي محدد، مع كل العقد (nodes) المرجعية لمعرفة
 * إحداثياتها الحقيقية (نمط "out body; >; out skel qt;" القياسي في Overpass QL).
 *
 * يُنفَّذ الاتصال بشكل متزامن (blocking) على الخيط المستدعي — يجب استدعاؤه من خيط خلفي وليس من
 * الخيط الرئيسي لواجهة المستخدم.
 */
object OverpassClient {
    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    // مهلة اتصال HTTP الخاصة بنا يجب أن تكون أكبر من أعلى مهلة [timeout:N] مطلوبة من خادم Overpass
    // نفسه في أي من الاستعلامات أدناه (٣٠ ثانية لاستعلام الممر)، وإلا قد نقطع الاتصال قبل أن يستجيب
    // الخادم نفسه بفشل حقيقي، فنُعامل استعلاماً لا يزال يعمل كأنه خطأ اتصال.
    private const val TIMEOUT_MS = 35000

    // أنواع ليست طرقاً/دروباً صالحة لسيارة: ممرات مشاة ودراجات وسلالم وممرات غير مستخدمة فعلياً، إلخ.
    private const val EXCLUDED_HIGHWAY =
        "footway|cycleway|path|steps|pedestrian|proposed|construction|abandoned|platform|razed|bridleway|corridor"

    class OverpassException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** حدود المربع الجغرافي بترتيب Overpass القياسي: جنوب، غرب، شمال، شرق (south, west, north, east). */
    @Throws(OverpassException::class)
    fun fetchRoadNetwork(south: Double, west: Double, north: Double, east: Double): RoadNetwork {
        val query = "[out:json][timeout:22];\n" +
            "(\n" +
            "  way[\"highway\"][\"highway\"!~\"^($EXCLUDED_HIGHWAY)$\"]($south,$west,$north,$east);\n" +
            ");\n" +
            "out body;\n" +
            ">;\n" +
            "out skel qt;"

        return execute(query)
    }

    /**
     * يجلب شبكة الطرق/الدروب الحقيقية ضمن مضلّع (poly) محدَّد بدل مربع جغرافي — يُستخدم لبناء "ممر"
     * بحث ضيّق بمحاذاة خط السير بين نقطتين بعيدتين جداً (بدل استعلام مربع كامل غير عملي الحجم).
     * بُنية مرشِّح poly محقَّقة من توثيق Overpass QL الرسمي: poly:"lat1 lon1 lat2 lon2 ...".
     */
    @Throws(OverpassException::class)
    fun fetchRoadNetworkInPolygon(polygon: List<LatLng>): RoadNetwork {
        val polyCoords = polygon.joinToString(" ") { "${it.latitude} ${it.longitude}" }
        val query = "[out:json][timeout:30];\n" +
            "(\n" +
            "  way[\"highway\"][\"highway\"!~\"^($EXCLUDED_HIGHWAY)$\"](poly:\"$polyCoords\");\n" +
            ");\n" +
            "out body;\n" +
            ">;\n" +
            "out skel qt;"
        return execute(query)
    }

    /** يدمج عدة شبكات طرق (من استعلامات مختلفة) في شبكة واحدة، مع إزالة تكرار العقد/الخطوط بنفس المعرّف. */
    fun merge(networks: List<RoadNetwork>): RoadNetwork {
        val nodes = HashMap<Long, DoubleArray>()
        val ways = LinkedHashMap<Long, OsmWay>()
        for (network in networks) {
            nodes.putAll(network.nodes)
            for (way in network.ways) ways[way.id] = way
        }
        return RoadNetwork(nodes, ways.values.toList())
    }

    @Throws(OverpassException::class)
    private fun execute(query: String): RoadNetwork {
        val fullUrl = ENDPOINT + "?data=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val connection = URL(fullUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.requestMethod = "GET"
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw OverpassException("استجابة غير متوقعة من خدمة Overpass: HTTP $code")
            }
            val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            return parse(body)
        } catch (e: OverpassException) {
            throw e
        } catch (e: Exception) {
            throw OverpassException("تعذّر الاتصال بخدمة Overpass لجلب بيانات الطرق الحقيقية", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String): RoadNetwork {
        val root = JSONObject(body)
        val elements = root.getJSONArray("elements")
        val nodes = HashMap<Long, DoubleArray>()
        val ways = ArrayList<OsmWay>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            when (el.optString("type")) {
                "node" -> {
                    val id = el.getLong("id")
                    nodes[id] = doubleArrayOf(el.getDouble("lat"), el.getDouble("lon"))
                }
                "way" -> {
                    val id = el.getLong("id")
                    val nodesArr = el.getJSONArray("nodes")
                    val nodeIds = LongArray(nodesArr.length()) { idx -> nodesArr.getLong(idx) }
                    val tags = el.optJSONObject("tags")
                    ways.add(
                        OsmWay(
                            id = id,
                            nodeIds = nodeIds,
                            highway = tags?.optString("highway")?.ifEmpty { null },
                            surface = tags?.optString("surface")?.ifEmpty { null },
                            tracktype = tags?.optString("tracktype")?.ifEmpty { null },
                            oneway = tags?.optString("oneway")?.ifEmpty { null }
                        )
                    )
                }
            }
        }
        return RoadNetwork(nodes, ways)
    }
}
