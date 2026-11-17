package com.alruwaili.garminnav

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * قارئ حاوية ملفات قارمن .img (التنسيق القديم DSKIMG/GARMIN).
 * يفكّ رأس الملف وجدول الفهرس (FAT) فقط، ليحدد مكان وحجم كل ملف فرعي
 * (TRE / RGN / LBL / NET / NOD / TYP ...) بداخل الحاوية.
 *
 * تم التحقق من صحة هذا التفكيك على ملف خريطة حقيقي (almisharIMAP.img):
 * استخرج بنجاح 4 قطع خريطة (كل منها TRE+RGN+LBL، وأحدها بيانات توجيه NET+NOD)
 * بالإضافة لملف التنسيق TYP وملف وصف المنتج MPS.
 *
 * هذا الجزء (فك الحاوية) يعمل مع أي ملف قارمن .img بهذه الصيغة، وليس خاصاً
 * بهذا الملف فقط.
 */
class GarminSubfile(
    val name: String,
    val ext: String,
    val size: Long,
    val clusters: List<Int>,
)

class GarminImg(private val path: String) {

    lateinit var raw: ByteArray
        private set
    var clusterSize: Int = 0
        private set

    val subfiles = LinkedHashMap<String, GarminSubfile>() // key = "NAME.EXT"

    fun open() {
        val f = RandomAccessFile(path, "r")
        val data = ByteArray(f.length().toInt())
        f.readFully(data)
        f.close()

        val xor = data[0].toInt() and 0xFF
        val buf = if (xor != 0) {
            ByteArray(data.size) { i -> (data[i].toInt() xor xor).toByte() }
        } else data
        raw = buf

        fun str(off: Int, len: Int): String {
            val b = buf.copyOfRange(off, off + len)
            return String(b, Charsets.US_ASCII).trimEnd('\u0000')
        }

        val sig1 = str(0x10, 6)
        val sig2 = str(0x41, 6)
        require(sig1 == "DSKIMG" || sig1 == "DSDIMG") { "ليست حاوية قارمن .img صحيحة (sig1=$sig1)" }
        require(sig2 == "GARMIN") { "ليست حاوية قارمن .img صحيحة (sig2=$sig2)" }

        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val sectorExp = buf[0x61].toInt() and 0xFF
        val clusterExp = buf[0x62].toInt() and 0xFF
        val sectorSize = 1 shl sectorExp
        clusterSize = sectorSize shl clusterExp
        val fatStartSector = buf[0x40].toInt() and 0xFF
        val fatOffset = fatStartSector * sectorSize

        // كل سجل في جدول الفهرس بحجم 512 بايت، يُقرأ حتى نهاية الملف.
        data class RawEntry(val name: String, val ext: String, val size: Long, val extent: Int, val clusters: List<Int>)
        val entries = ArrayList<RawEntry>()

        var off = fatOffset
        while (off + 512 <= buf.size) {
            val flag = buf[off].toInt() and 0xFF
            if (flag == 1) {
                val name = str(off + 1, 8).trim()
                val ext = str(off + 9, 3).trim()
                if (name.isNotBlank()) {
                    val size = bb.getInt(off + 0x0C).toLong() and 0xFFFFFFFFL
                    val extent = buf[off + 0x11].toInt() and 0xFF
                    val clist = ArrayList<Int>()
                    var p = off + 0x20
                    repeat(240) {
                        val c = bb.getShort(p).toInt() and 0xFFFF
                        if (c != 0xFFFF) clist.add(c)
                        p += 2
                    }
                    entries.add(RawEntry(name, ext, size, extent, clist))
                }
            }
            off += 512
        }

        // تجميع الامتدادات (extents) لكل ملف فرعي بالاسم+الامتداد، بترتيب extent.
        val grouped = entries.groupBy { it.name + "." + it.ext }
        for ((key, list) in grouped) {
            val sorted = list.sortedBy { it.extent }
            val total = sorted.firstOrNull { it.extent == 0 }?.size ?: 0L
            val allClusters = ArrayList<Int>()
            for (e in sorted) allClusters.addAll(e.clusters)
            subfiles[key] = GarminSubfile(sorted[0].name, sorted[0].ext, total, allClusters)
        }
    }

    /** يستخرج محتوى ملف فرعي كامل (مثل "04000050.TRE") كمصفوفة بايتات متتالية. */
    fun readSubfile(key: String): ByteArray {
        val sf = subfiles[key] ?: error("لا يوجد ملف فرعي باسم $key")
        val out = ByteArray(sf.clusters.size * clusterSize)
        for ((i, c) in sf.clusters.withIndex()) {
            val srcOff = c.toLong() * clusterSize
            if (srcOff + clusterSize <= raw.size) {
                System.arraycopy(raw, srcOff.toInt(), out, i * clusterSize, clusterSize)
            }
        }
        return if (sf.size in 1..out.size.toLong()) out.copyOf(sf.size.toInt()) else out
    }

    /** أسماء قطع الخريطة المتوفرة (بدون الامتداد)، أي كل "TRE" موجود يمثّل قطعة خريطة. */
    fun mapTileNames(): List<String> =
        subfiles.values.filter { it.ext == "TRE" }.map { it.name }.distinct()
}
