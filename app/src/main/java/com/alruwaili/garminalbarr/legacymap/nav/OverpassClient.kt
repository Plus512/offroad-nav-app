package com.alruwaili.garminalbarr.legacymap.nav

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
    // أُضيفت هذه القائمة بعد أن أبلغ المستخدم فعلياً بفشل توجيه السيارة بخطأ "HTTP 429" (طلبات كثيرة
    // جداً) من الخادم الرئيسي overpass-api.de — وهو مُحمَّل بشدة ويحدّ معدل الطلبات لكل عنوان IP، فإن
    // استُنفد الحد (خصوصاً بعد عدة محاولات توجيه متتالية) تُرفض حتى الطلبات التالية الصغيرة لفترة. بدل
    // محاولة الخادم نفسه فقط، نجرّب بالترتيب ثلاثة خوادم Overpass عامة مجانية بتغطية عالمية كاملة (بلا
    // مفتاح API)، موثَّقة فعلياً في صفحة wiki.openstreetmap.org/wiki/Overpass_API الرسمية (تحقَّقتُ منها
    // مباشرة قبل إضافتها، وليست أسماء مخمَّنة): الرئيسي ثم VK Maps ثم private.coffee (الاسم الحالي لِما
    // كان يُعرف سابقاً باسم overpass.kumi.systems).
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )
    // مهلة اتصال HTTP الخاصة بنا يجب أن تكون أكبر من أعلى مهلة [timeout:N] مطلوبة من خادم Overpass
    // نفسه في أي من الاستعلامات أدناه (٣٠ ثانية لاستعلام الممر)، وإلا قد نقطع الاتصال قبل أن يستجيب
    // الخادم نفسه بفشل حقيقي، فنُعامل استعلاماً لا يزال يعمل كأنه خطأ اتصال.
    // ملاحظة (٢٣ نوفمبر): connectTimeout وreadTimeout مرحلتان منفصلتان في HttpURLConnection — إن
    // تعطّل الاتصال بالكامل (لا استجابة إطلاقاً من الخادم، كما في شبكة تحجب هذا النطاق)، أسوأ حالة
    // لكل خادم واحد هي connectTimeout + readTimeout معاً، أي كانت تصل فعلياً حتى ٧٠ ثانية × ٣ خوادم
    // = ١٧٥–٢١٠ ثانية (نحو ٣.٥ دقيقة) من التجمّد الظاهري دون أي تحديث على الشاشة قبل ولو فشل خلية
    // واحدة — وهذا يطابق تماماً ما أبلغ عنه المستخدم (توقف عند 0% لأربع دقائق ثم فشل). تخفيضها هنا
    // يقلّص أسوأ حالة انتظار لكل خادم تقريباً إلى النصف، دون كسر أي استعلام بطيء لكنه يعمل فعلاً
    // (لا يزال أكبر من أعلى مهلة [timeout:N] أعلاه). هذا لا يُصلح عدم القدرة الفعلية على الوصول إلى
    // خوادم Overpass من شبكته إن كانت محجوبة فعلاً — فقط يجعل الفشل يظهر أسرع بدل أن يبدو "متجمّداً".
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 26000

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

    // مطابق لسياسة استخدام Overpass API الرسمية (overpass-api.de): يجب أن يحمل كل طلب رأس
    // User-Agent يعرّف التطبيق بوضوح، وإلا رفضت الخوادم الطلب (غالباً بخطأ HTTP 406). هذا هو
    // السبب الحقيقي المؤكَّد (موثَّق في نقاشات مجتمع OpenStreetMap الرسمية) لظهور "HTTP 406" هنا —
    // وليس خطأً في صياغة استعلام الممر وحده، ولهذا كان توجيه السيارة يفشل حتى للمسافات القصيرة.
    private const val USER_AGENT = "DalilAlBar-Android/1.0 (offline navigation app; contact via GitHub Plus512)"

    /**
     * ينفّذ الاستعلام على خوادم [ENDPOINTS] بالترتيب: إن رفض خادم الطلب بخطأ خادم مؤقّت (429 طلبات
     * كثيرة جداً، أو أي خطأ 5xx)، أو تعذّر الاتصال به أصلاً، يُجرَّب الخادم التالي فوراً بنفس الاستعلام
     * بدل الفشل الكلي مباشرة. لا يُعاد تجربة نفس الخادم (التجربة باءت بالفشل فعلاً الآن)، ولا تُضاف أي
     * فترة انتظار صناعية بين الخوادم لأنها خوادم مختلفة تماماً (لا فائدة من الانتظار قبل تجربة خادم آخر).
     */
    @Throws(OverpassException::class)
    private fun execute(query: String): RoadNetwork {
        var lastError: OverpassException? = null
        for ((index, endpoint) in ENDPOINTS.withIndex()) {
            try {
                return executeOn(endpoint, query)
            } catch (e: OverpassException) {
                lastError = e
                // نجرّب الخادم التالي فقط إن بقي خادم آخر في القائمة؛ غير ذلك نرمي الخطأ الأخير كما هو.
                if (index == ENDPOINTS.lastIndex) throw e
            }
        }
        // لا يصل التنفيذ إلى هنا عملياً (القائمة غير فارغة دائماً)، لكن مطلوب إرضاءً لنوع الإرجاع.
        throw lastError ?: OverpassException("تعذّر الاتصال بأي من خوادم Overpass المتاحة")
    }

    @Throws(OverpassException::class)
    private fun executeOn(endpoint: String, query: String): RoadNetwork {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "POST"
        // توثيق Overpass API الرسمي (wiki.openstreetmap.org/wiki/Overpass_API) يوصي بإرسال
        // الاستعلام عبر POST بترميز form، وليس عبر GET مع وضع الاستعلام كاملاً داخل رابط URL —
        // وهذا يتجنّب أيضاً أي مشكلة محتملة في طول الرابط مع استعلامات الممر الطويلة.
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        try {
            val payload = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val reason = if (code == 429) {
                    "الخادم مزدحم حالياً (طلبات كثيرة جداً، HTTP 429)"
                } else {
                    "استجابة غير متوقعة من خدمة Overpass: HTTP $code"
                }
                throw OverpassException(reason)
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
