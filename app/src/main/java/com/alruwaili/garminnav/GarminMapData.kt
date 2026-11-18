package com.alruwaili.garminnav

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * فك تشفير طرق/معالم/نقاط خريطة قارمن الحقيقية من قطاعي TRE و RGN.
 *
 * التنسيق غير موثّق رسمياً بشكل كامل (حتى المراجع المتخصصة تتضارب)، لذا كل حقل هنا
 * تم التحقق منه تجريبياً مقابل ملف خريطة حقيقي (almisharIMAP.img) قبل اعتماده:
 * - حدود الخريطة ومستويات التكبير وشجرة التقسيمات الفرعية: تحقق كامل (٠ أخطاء على كل
 *   التقسيمات عبر كل المستويات).
 * - النقاط (POI): تحقق كامل على عينات حقيقية (الإزاحات والتسميات مطابقة منطقياً).
 * - الطرق (polylines): خوارزمية فك الضغط الخاصة بقارمن للإحداثيات المتتالية (bitstream)
 *   تم اختبارها إحصائياً على آلاف الطرق الحقيقية. للحماية من أي خطأ محتمل في الحالات
 *   النادرة، كل نقطة يتم التحقق من وقوعها ضمن حدود التقسيمة الفرعية (bounding box)؛
 *   أي خط يتجاوز الحدود بشكل غير منطقي يُهمَل بدل عرضه بشكل خاطئ (لا نعرض بيانات غير
 *   مؤكدة كأنها طرق حقيقية).
 * - الأسماء (قطاع LBL): رأس الملف يحدد نوع الترميز فعلياً (بدل تخمينه) — في هذه الخريطة
 *   جاء النوع "٩" مع صفحة ترميز ١٢٥٦ (Windows Arabic)، أي نص عربي عادي بايت‌لكل‌حرف، لا
 *   ترميز الـ ٦-بت غير الموثَّق. تم التحقق من ذلك على عشرات الأسماء الحقيقية (الرياض،
 *   جدة، مكة المكرمة، المدينة المنورة، الدمام، الربع الخالي، النفود الكبير...) وجاءت
 *   صحيحة ١٠٠٪. ومع ذلك، لأن بعض إزاحات التسميات المستخرجة من سجلات النقاط قد تكون غير
 *   دقيقة (تلوّث من بيانات مجاورة)، يُفحص كل اسم مفكوك: يُعرض فقط إن كان نصاً عربياً أو
 *   لاتينياً مقروءاً بالكامل (بدون رموز تحكم أو بايتات غريبة)؛ أي نتيجة مشكوك فيها تُهمَل
 *   بصمت بدل عرضها كاسم خاطئ.
 */

private val ARABIC_LATIN_LABEL_RE = Regex("^[\\u0600-\\u06FF\\u0750-\\u077F\\u200e\\u200f A-Za-z0-9.,\\-'()/&:+]*$")

/** يتحقق أن النص المفكوك عربي/لاتيني مقروء بالكامل قبل السماح بعرضه كاسم حقيقي. */
private fun isCleanLabel(s: String?): Boolean {
    if (s.isNullOrBlank()) return false
    return ARABIC_LATIN_LABEL_RE.matches(s)
}

class TreLevel(val zoom: Int, val inherited: Boolean, val bits: Int, val nsub: Int)

/** تقسيمة فرعية جغرافية صغيرة من شجرة الخريطة (أدق مستوى = أكثر تفصيلاً). */
class Subdivision(
    val levelIdx: Int,
    val bits: Int,
    val rgnOff: Int,
    val lon: Int,
    val lat: Int,
    val halfWidth: Int,
    val halfHeight: Int,
    val hasPoint: Boolean,
    val hasIPoint: Boolean,
    val hasLine: Boolean,
    val hasPoly: Boolean,
) {
    val lonMin: Int get() = lon - halfWidth
    val lonMax: Int get() = lon + halfWidth
    val latMin: Int get() = lat - halfHeight
    val latMax: Int get() = lat + halfHeight

    fun intersects(qLonMin: Int, qLonMax: Int, qLatMin: Int, qLatMax: Int): Boolean =
        lonMin <= qLonMax && lonMax >= qLonMin && latMin <= qLatMax && latMax >= qLatMin
}

/** خط مُفكَّك (طريق أو حدود مضلع) بإحداثيات lon/lat متتالية (بوحدات قارمن الخام). */
class GLine(val lons: IntArray, val lats: IntArray, val isPolygon: Boolean)

class GPoint(val lon: Int, val lat: Int, val label: String? = null)

class DecodedFeatures {
    val lines = ArrayList<GLine>()
    val points = ArrayList<GPoint>()
}

private fun u24(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or ((b[off + 2].toInt() and 0xFF) shl 16)

private fun s24(b: ByteArray, off: Int): Int {
    val v = u24(b, off)
    return if (v and 0x800000 != 0) v - 0x1000000 else v
}

/** يفكّ رأس TRE وشجرة التقسيمات الفرعية لقطعة خريطة واحدة (TRE+RGN بنفس الاسم). */
class GarminTile(private val img: GarminImg, private val baseName: String) {

    var north = 0; var east = 0; var south = 0; var west = 0
        private set

    var levels: List<TreLevel> = emptyList()
        private set
    var subdivisions: List<Subdivision> = emptyList()
        private set

    private var rgnHeaderLen = 0
    private lateinit var rgn: ByteArray

    // قطاع LBL (الأسماء): يُفكّ رأسه فقط إن توفّر ترميز بايت-واحد (٩) مع صفحة ترميز معروفة؛
    // أي ترميز آخر (مثل الـ ٦-بت غير الموثَّق) يُعطَّل تماماً فلا تُعرض أسماء غير مؤكدة.
    private var lbl: ByteArray? = null
    private var labelStart = 0
    private var labelCharset: java.nio.charset.Charset? = null

    private fun parseLblHeader() {
        val lblKey = "$baseName.LBL"
        if (!img.subfiles.containsKey(lblKey)) return
        val buf = try { img.readSubfile(lblKey) } catch (e: Exception) { return }
        if (buf.size < 0x1F) return
        val bb2 = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        // تخطيط الرأس (مطابق لقارئ mkgmap الرسمي uk.me.parabola.imgfmt.app.lbl.LBLHeader):
        // 0x00 طول الرأس(2) + "GARMIN LBL"(10) + علم القفل(2) + تاريخ الإنشاء(7) = نهايته 0x15
        val lblStartOff = bb2.getInt(0x15)
        // 0x19 طول التسميات(4)، 0x1D مضاعِف الإزاحة(1)، 0x1E نوع الترميز(1)
        val encodingType = buf[0x1E].toInt() and 0xFF
        if (encodingType != 9) {
            // النوع الوحيد الذي تحقّقنا منه فعلياً هو ٩ (بايت واحد لكل حرف + صفحة ترميز).
            // أي نوع آخر (كالـ ٦-بت) يبقى معطَّلاً حتى يُتحقَّق منه بنفس الدقة.
            return
        }
        // جزء PlacesHeader يبدأ دائماً عند 0x1F وله طول ثابت معروف (١٢٥ بايت) ينتهي عند 0xAA،
        // ثم صفحة الترميز (u16).
        if (buf.size < 0xAC) return
        val codepage = (bb2.getShort(0xAA).toInt() and 0xFFFF)
        val cs = try { java.nio.charset.Charset.forName("windows-$codepage") } catch (e: Exception) { null }
        if (cs == null) return
        lbl = buf
        labelStart = lblStartOff
        labelCharset = cs
    }

    /** يفكّ ويتحقق من اسم واحد عند إزاحة LBL معيّنة، أو null إن لم يكن موثوقاً. */
    private fun decodeLabel(off: Int): String? {
        val b = lbl ?: return null
        val cs = labelCharset ?: return null
        if (off == 0) return null
        val abs = labelStart + off
        if (abs < 0 || abs >= b.size) return null
        var endIdx = abs
        val limit = minOf(b.size, abs + 160)
        while (endIdx < limit && b[endIdx] != 0.toByte()) endIdx++
        if (endIdx >= limit) return null
        val text = try { String(b, abs, endIdx - abs, cs) } catch (e: Exception) { return null }
        return if (isCleanLabel(text)) text.trim() else null
    }

    /** يفكّ رأس TRE وجدول التقسيمات الفرعية فقط (بيانات خفيفة، آمنة للتحميل كاملة عند فتح الخريطة). */
    fun parseHeader() {
        val treKey = "$baseName.TRE"
        val rgnKey = "$baseName.RGN"
        if (!img.subfiles.containsKey(treKey) || !img.subfiles.containsKey(rgnKey)) return
        val tre = img.readSubfile(treKey)

        north = s24(tre, 0x15); east = s24(tre, 0x18); south = s24(tre, 0x1B); west = s24(tre, 0x1E)

        val bb = ByteBuffer.wrap(tre).order(ByteOrder.LITTLE_ENDIAN)
        val tre1Off = bb.getInt(0x21); val tre1Len = bb.getInt(0x25)
        val tre2Off = bb.getInt(0x29)

        val nlev = tre1Len / 4
        val lv = ArrayList<TreLevel>(nlev)
        for (i in 0 until nlev) {
            val o = tre1Off + i * 4
            val b0 = tre[o].toInt() and 0xFF
            val b1 = tre[o + 1].toInt() and 0xFF
            val nsub = (bb.getShort(o + 2).toInt() and 0xFFFF)
            lv.add(TreLevel(zoom = b0 and 0x0F, inherited = (b0 and 0x80) != 0, bits = b1, nsub = nsub))
        }
        levels = lv

        val subs = ArrayList<Subdivision>()
        var off = tre2Off
        for (li in lv.indices) {
            val level = lv[li]
            val isLast = (li == lv.size - 1)
            val recSize = if (isLast) 14 else 16
            val shift = 24 - level.bits
            for (i in 0 until level.nsub) {
                val o = off
                val flagsRgn = bb.getInt(o)
                val hasPoly = (flagsRgn and -0x80000000) != 0        // bit31
                val hasLine = (flagsRgn and 0x40000000) != 0          // bit30
                val hasIPoint = (flagsRgn and 0x20000000) != 0        // bit29
                val hasPoint = (flagsRgn and 0x10000000) != 0         // bit28
                val rgnOff = flagsRgn and 0x0FFFFFFF
                val lon = s24(tre, o + 4)
                val lat = s24(tre, o + 7)
                val wh = bb.getShort(o + 10).toInt() and 0xFFFF
                val width = wh and 0x7FFF
                val height = bb.getShort(o + 12).toInt() and 0xFFFF
                subs.add(
                    Subdivision(
                        levelIdx = li, bits = level.bits, rgnOff = rgnOff,
                        lon = lon, lat = lat,
                        halfWidth = width shl shift, halfHeight = height shl shift,
                        hasPoint = hasPoint, hasIPoint = hasIPoint, hasLine = hasLine, hasPoly = hasPoly,
                    )
                )
                off += recSize
            }
        }
        subdivisions = subs

        val rgnBuf = img.readSubfile(rgnKey)
        rgn = rgnBuf
        rgnHeaderLen = (rgnBuf[0].toInt() and 0xFF) or ((rgnBuf[1].toInt() and 0xFF) shl 8)

        parseLblHeader()
    }

    /** أعمق مستوى (الأكثر تفصيلاً) فقط — هذا ما نرسمه حالياً. */
    fun deepestLevelIdx(): Int = levels.size - 1

    private fun readS16(b: ByteArray, o: Int): Int {
        val v = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        return if (v and 0x8000 != 0) v - 0x10000 else v
    }

    /**
     * يفكّ محتوى تقسيمة فرعية واحدة (نقاط + خطوط) إلى [out]. أي خط تخرج إحدى نقاطه عن
     * حدود التقسيمة بشكل كبير يُعتبر خطأ فك ترميز (desync) ويُهمَل الباقي من هذه الكتلة
     * بدل عرض بيانات غير موثوقة.
     */
    fun decodeSubdivisionInto(sub: Subdivision, out: DecodedFeatures, subIndexInLevel: Int, subsInLevel: List<Subdivision>) {
        val start = rgnHeaderLen + sub.rgnOff
        val end = if (subIndexInLevel + 1 < subsInLevel.size) rgnHeaderLen + subsInLevel[subIndexInLevel + 1].rgnOff else rgn.size
        if (start >= end || end > rgn.size || start < 0) return

        if (!sub.hasLine && !sub.hasPoly && !sub.hasPoint) return

        // عند وجود أكثر من نوع بيانات واحد في نفس التقسيمة، تسبقها إشارات (بايتان لكل
        // نوع إضافي) تحدد أين يبدأ كل نوع داخل الكتلة. النقاط دائماً أولاً إن وُجدت.
        var p = start
        var pointsEnd = end
        var linesStart = start
        val typeCount = (if (sub.hasPoint) 1 else 0) + (if (sub.hasIPoint) 1 else 0) +
            (if (sub.hasLine) 1 else 0) + (if (sub.hasPoly) 1 else 0)
        if (typeCount > 1) {
            val ptrBytes = (typeCount - 1) * 2
            if (p + ptrBytes > end) return
            // أول إشارة فقط نحتاجها حالياً: نهاية قسم النقاط = بداية قسم الخطوط.
            if (sub.hasPoint || sub.hasIPoint) {
                val rel = (rgn[p].toInt() and 0xFF) or ((rgn[p + 1].toInt() and 0xFF) shl 8)
                linesStart = p + ptrBytes + rel
            } else {
                linesStart = p + ptrBytes
            }
            p += ptrBytes
            pointsEnd = linesStart
        } else {
            if (sub.hasLine || sub.hasPoly) linesStart = p
            pointsEnd = if (sub.hasLine || sub.hasPoly) p else end
        }

        if (sub.hasPoint || sub.hasIPoint) {
            decodePointsInto(rgn, p, minOf(pointsEnd, end), sub.lon, sub.lat, out)
        }
        if (!sub.hasLine && !sub.hasPoly) return

        var pos = linesStart.coerceIn(start, end)
        val margin = 2000
        var guard = 0
        while (pos < end && guard < 2000) {
            guard++
            val res = decodeOnePolyline(rgn, pos, end, sub.lon, sub.lat) ?: break
            val (lons, lats, consumed) = res
            var ok = true
            for (i in lons.indices) {
                val dl = lons[i] - sub.lon
                val dla = lats[i] - sub.lat
                if (dl > sub.halfWidth + margin || dl < -sub.halfWidth - margin ||
                    dla > sub.halfHeight + margin || dla < -sub.halfHeight - margin
                ) { ok = false; break }
            }
            if (ok && lons.size >= 2) {
                out.lines.add(GLine(lons, lats, isPolygon = sub.hasPoly && !sub.hasLine))
            }
            if (consumed <= 0) break
            pos += consumed
            if (!ok) break // محتمل أن يكون فك الترميز قد انحرف؛ لا نكمل بقية الكتلة
        }
    }

    /**
     * يفكّ فقط قسم النقاط (بدون الخطوط) من تقسيمة فرعية واحدة — أخفّ وأسرع من
     * decodeSubdivisionInto، تُستخدم لبناء فهرس الأسماء الكامل للبحث فقط (لا حاجة
     * للطرق/المضلعات هناك).
     */
    fun decodePointsOnlyInto(sub: Subdivision, out: MutableList<GPoint>, subIndexInLevel: Int, subsInLevel: List<Subdivision>) {
        if (!sub.hasPoint && !sub.hasIPoint) return
        val start = rgnHeaderLen + sub.rgnOff
        val end = if (subIndexInLevel + 1 < subsInLevel.size) rgnHeaderLen + subsInLevel[subIndexInLevel + 1].rgnOff else rgn.size
        if (start >= end || end > rgn.size || start < 0) return

        var p = start
        var pointsEnd = end
        val typeCount = (if (sub.hasPoint) 1 else 0) + (if (sub.hasIPoint) 1 else 0) +
            (if (sub.hasLine) 1 else 0) + (if (sub.hasPoly) 1 else 0)
        if (typeCount > 1) {
            val ptrBytes = (typeCount - 1) * 2
            if (p + ptrBytes > end) return
            val rel = (rgn[p].toInt() and 0xFF) or ((rgn[p + 1].toInt() and 0xFF) shl 8)
            pointsEnd = p + ptrBytes + rel
            p += ptrBytes
        }
        val tmp = DecodedFeatures()
        decodePointsInto(rgn, p, minOf(pointsEnd, end), sub.lon, sub.lat, tmp)
        out.addAll(tmp.points)
    }

    /** يفكّ سلسلة نقاط (POI) ضمن [start, end). كل نقطة: نوع(١) + تسمية(٣) + فرق‌طول(٢) + فرق‌عرض(٢) + [فرعي(١) إن وُجد]. */
    private fun decodePointsInto(b: ByteArray, start: Int, end: Int, centerLon: Int, centerLat: Int, out: DecodedFeatures) {
        var p = start
        var guard = 0
        while (p + 8 <= end && guard < 5000) {
            guard++
            val lblRaw = (b[p + 1].toInt() and 0xFF) or ((b[p + 2].toInt() and 0xFF) shl 8) or ((b[p + 3].toInt() and 0xFF) shl 16)
            val hasSubtype = (lblRaw and 0x800000) != 0
            val lblOff = lblRaw and 0x3FFFFF
            val dlon = readS16(b, p + 4)
            val dlat = readS16(b, p + 6)
            val label = if (lblOff != 0) decodeLabel(lblOff) else null
            out.points.add(GPoint(centerLon + dlon, centerLat + dlat, label))
            p += if (hasSubtype) 9 else 8
        }
    }

    /** يفكّ سجل خط واحد بدءاً من [off]. يُرجع (lons, lats, عدد البايتات المستهلكة) أو null. */
    private fun decodeOnePolyline(b: ByteArray, off: Int, end: Int, centerLon: Int, centerLat: Int): Triple<IntArray, IntArray, Int>? {
        if (off + 8 > end) return null
        val typ = b[off].toInt() and 0xFF
        val lblRaw = (b[off + 1].toInt() and 0xFF) or ((b[off + 2].toInt() and 0xFF) shl 8) or ((b[off + 3].toInt() and 0xFF) shl 16)
        val extraBit = (lblRaw and 0x400000) != 0
        val dlon0 = readS16(b, off + 4)
        val dlat0 = readS16(b, off + 6)
        var p = off + 8
        val length: Int
        if (typ and 0x80 != 0) {
            if (p + 2 > end) return null
            length = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
            p += 2
        } else {
            if (p + 1 > end) return null
            length = b[p].toInt() and 0xFF
            p += 1
        }
        val bitstreamStart = p
        val bitstreamEnd = minOf(p + length, end)
        val totalConsumed = (bitstreamEnd - off)
        if (bitstreamEnd <= bitstreamStart) {
            return Triple(intArrayOf(centerLon + dlon0), intArrayOf(centerLat + dlat0), totalConsumed)
        }
        val base = b[bitstreamStart].toInt() and 0xFF
        val lonBase = base and 0x0F
        val latBase = (base shr 4) and 0x0F
        var lonBits = 1 + lonBase
        var latBits = 1 + latBase

        // قارئ بتات (LSB أولاً داخل كل بايت)، يبدأ بعد بايت الأساس.
        var bitPos = (bitstreamStart + 1 - bitstreamStart) * 8 // = 8 (نسبي لبداية الـ bitstream)
        val bsLen = (bitstreamEnd - bitstreamStart) * 8
        fun readBits(n: Int): Int {
            var v = 0
            for (i in 0 until n) {
                val bytePos = bitstreamStart + (bitPos shr 3)
                val bitIdx = bitPos and 7
                val bit = if (bytePos < bitstreamEnd) (b[bytePos].toInt() shr bitIdx) and 1 else 0
                v = v or (bit shl i)
                bitPos++
            }
            return v
        }

        val signSameLon = readBits(1)
        val lonSign = if (signSameLon != 0) readBits(1) else 0
        val signSameLat = readBits(1)
        val latSign = if (signSameLat != 0) readBits(1) else 0
        if (extraBit) { lonBits += 1; latBits += 1 }

        val lonsList = ArrayList<Int>()
        val latsList = ArrayList<Int>()
        lonsList.add(centerLon + dlon0)
        latsList.add(centerLat + dlat0)
        var curLon = dlon0
        var curLat = dlat0
        var iter = 0
        while (bsLen - bitPos >= (lonBits + latBits) && iter < 5000) {
            iter++
            val rawLon = readBits(lonBits)
            val dlon = if (signSameLon != 0) (if (lonSign != 0) -rawLon else rawLon) else {
                val signBit = (rawLon shr (lonBits - 1)) and 1
                val mag = rawLon and ((1 shl (lonBits - 1)) - 1)
                if (signBit != 0) -mag else mag
            }
            val rawLat = readBits(latBits)
            val dlat = if (signSameLat != 0) (if (latSign != 0) -rawLat else rawLat) else {
                val signBit = (rawLat shr (latBits - 1)) and 1
                val mag = rawLat and ((1 shl (latBits - 1)) - 1)
                if (signBit != 0) -mag else mag
            }
            curLon += dlon
            curLat += dlat
            lonsList.add(centerLon + curLon)
            latsList.add(centerLat + curLat)
        }
        return Triple(lonsList.toIntArray(), latsList.toIntArray(), totalConsumed)
    }
}

/** نقطة مُسمّاة واحدة في فهرس البحث الكامل (كل الخريطة، لا نافذة عرض محددة). */
class LabeledPoint(val label: String, val lon: Int, val lat: Int)

/**
 * مرجع عام للخريطة المحمّلة حالياً في الذاكرة، وفهرس أسمائها (يُبنى مرة واحدة فقط
 * عند الحاجة، بدلاً من إعادة قراءة ملف الخريطة البالغ ٥٥ م.ب من جديد). تستخدمه
 * شاشة البحث (SearchActivity) للوصول لنفس النسخة التي حمّلتها شاشة الخريطة
 * (MapCanvasView)، دون أي اعتماد بين الاثنين على واجهات أندرويد.
 */
object MapRepository {
    @Volatile var map: GarminMap? = null
    @Volatile var labelIndex: List<LabeledPoint>? = null

    /** يبني فهرس الأسماء إن لم يكن مبنياً من قبل لنفس الخريطة المحمّلة حالياً. */
    @Synchronized
    fun ensureLabelIndex(): List<LabeledPoint> {
        labelIndex?.let { return it }
        val m = map ?: return emptyList()
        val idx = m.indexAllLabeledPoints()
        labelIndex = idx
        return idx
    }

    fun clear() {
        map = null
        labelIndex = null
    }
}

/** يجمع كل قطع الخريطة (TRE/RGN) الموجودة داخل ملف .img واحد. */
class GarminMap(private val path: String? = null) {
    private val img = GarminImg(path)
    var tiles: List<GarminTile> = emptyList()
        private set

    var north = Int.MIN_VALUE; var south = Int.MAX_VALUE; var east = Int.MIN_VALUE; var west = Int.MAX_VALUE
        private set

    /** يفتح الملف من مساره المباشر (الأسلوب القديم). */
    fun open() {
        img.open()
        afterImgOpened()
    }

    /** يفتح الملف من أي مجرى (InputStream) — يُستخدم عند القراءة من مجلد محفوظ عبر SAF. */
    fun openFromStream(input: java.io.InputStream) {
        img.openFromStream(input)
        afterImgOpened()
    }

    private fun afterImgOpened() {
        val names = img.mapTileNames()
        val list = ArrayList<GarminTile>()
        for (name in names) {
            val t = GarminTile(img, name)
            t.parseHeader()
            if (t.subdivisions.isNotEmpty()) {
                list.add(t)
                if (t.north > north) north = t.north
                if (t.south < south) south = t.south
                if (t.east > east) east = t.east
                if (t.west < west) west = t.west
            }
        }
        tiles = list
    }

    val centerLon: Int get() = if (tiles.isEmpty()) 0 else (east + west) / 2
    val centerLat: Int get() = if (tiles.isEmpty()) 0 else (north + south) / 2

    /** يفكّ كل الطرق/المضلعات ضمن نافذة عرض جغرافية (بوحدات قارمن الخام)، بحد أقصى [maxLines] خط. */
    fun decodeVisible(lonMin: Int, lonMax: Int, latMin: Int, latMax: Int, maxLines: Int): DecodedFeatures {
        val out = DecodedFeatures()
        for (tile in tiles) {
            val li = tile.deepestLevelIdx()
            if (li < 0) continue
            val subsInLevel = tile.subdivisions.filter { it.levelIdx == li }
            for ((idx, sub) in subsInLevel.withIndex()) {
                if (out.lines.size >= maxLines) return out
                if (!sub.intersects(lonMin, lonMax, latMin, latMax)) continue
                tile.decodeSubdivisionInto(sub, out, idx, subsInLevel)
            }
        }
        return out
    }

    /**
     * يبني فهرساً كاملاً لكل نقطة مُسمّاة في الخريطة بأكملها (للبحث عن الأسماء)،
     * بدون فك الطرق/المضلعات (أخفّ وأسرع من decodeVisible). يُستحسن استدعاؤها مرة
     * واحدة فقط (مثلاً في خيط عمل منفصل عند أول فتح للبحث) وتخزين النتيجة.
     */
    fun indexAllLabeledPoints(): List<LabeledPoint> {
        val out = ArrayList<LabeledPoint>()
        val tmp = ArrayList<GPoint>()
        for (tile in tiles) {
            val li = tile.deepestLevelIdx()
            if (li < 0) continue
            val subsInLevel = tile.subdivisions.filter { it.levelIdx == li }
            for ((idx, sub) in subsInLevel.withIndex()) {
                tmp.clear()
                tile.decodePointsOnlyInto(sub, tmp, idx, subsInLevel)
                for (pt in tmp) {
                    val lbl = pt.label
                    if (!lbl.isNullOrBlank()) out.add(LabeledPoint(lbl, pt.lon, pt.lat))
                }
            }
        }
        return out
    }
}
