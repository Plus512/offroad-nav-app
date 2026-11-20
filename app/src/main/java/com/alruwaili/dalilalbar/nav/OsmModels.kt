package com.alruwaili.dalilalbar.nav

/** خط (way) حقيقي كما ورد فعلياً من استعلام Overpass API (لا بيانات مصطنعة). */
data class OsmWay(
    val id: Long,
    val nodeIds: LongArray,
    val highway: String?,
    val surface: String?,
    val tracktype: String?,
    val oneway: String?
)

/** شبكة الطرق/الدروب الحقيقية: عقد (معرّف → [خط العرض، خط الطول]) وخطوط (ways) كما وردت من OpenStreetMap. */
class RoadNetwork(
    val nodes: Map<Long, DoubleArray>,
    val ways: List<OsmWay>
)

enum class RoadClass { PAVED, TRACK }

/**
 * يصنّف كل خط (way) حسب وسومه الحقيقية فقط (highway/surface) إلى:
 * - PAVED: طريق معبّد من تصنيفات الطرق العادية (ما لم يكن موسوماً صراحة بسطح غير معبّد).
 * - TRACK: درب/طريق ترابي (highway=track) أو طريق عادي موسوم بسطح غير معبّد صراحة — أي "ردمية".
 * خطوط أخرى (ممرات مشاة، سكك، إلخ) تُستبعد أصلاً من استعلام Overpass نفسه قبل الوصول هنا.
 */
object RoadClassifier {
    private val PAVED_HIGHWAYS = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
        "residential", "service", "living_street",
        "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"
    )

    private val UNPAVED_SURFACES = setOf(
        "unpaved", "dirt", "sand", "gravel", "ground", "earth", "compacted",
        "fine_gravel", "grass", "mud", "pebblestone", "rock", "sett", "woodchips"
    )

    fun classify(way: OsmWay): RoadClass? {
        val hw = way.highway ?: return null
        if (hw == "track") return RoadClass.TRACK
        if (hw in PAVED_HIGHWAYS) {
            val surface = way.surface
            return if (surface != null && surface in UNPAVED_SURFACES) RoadClass.TRACK else RoadClass.PAVED
        }
        return null
    }
}
