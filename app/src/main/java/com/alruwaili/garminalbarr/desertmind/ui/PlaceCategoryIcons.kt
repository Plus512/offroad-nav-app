package com.alruwaili.garminalbarr.desertmind.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.alruwaili.garminalbarr.desertmind.DesertMindEngine

/**
 * أيقونة ولون موحّدان لكل نوع موقع، مبنيان على نفس تصنيف [DesertMindEngine.classifyPoiTitle]
 * (بادئة الاسم)، كي تظهر نفس الرموز في كل من "الرادار الصحراوي" و"المواقع القريبة" ضمن شاشة
 * "المساعد الرقمي" — بدل أيقونة ذهبية عامة واحدة للجميع، كما طلب المستخدم (2026-11-26) بعد
 * صورة مرجعية توضح أيقونات ملوّنة بحسب النوع (قطرة ماء، مضخة وقود، بيت، جبل).
 */
object PlaceCategoryIcons {
    private val WaterBlue = Color(0xFF4FC3F7)
    private val FuelAmber = Color(0xFFFFC107)
    private val HouseViolet = Color(0xFFBA68C8)
    private val TerrainRed = Color(0xFFEF5350)
    private val DefaultGold = Color(0xFFDDA01E)

    fun iconFor(title: String): Pair<ImageVector, Color> {
        return when (DesertMindEngine.classifyPoiTitle(title)) {
            "well", "spring", "pond", "water_generic" -> Icons.Default.WaterDrop to WaterBlue
            "station" -> Icons.Default.LocalGasStation to FuelAmber
            "village", "center", "house", "town" -> Icons.Default.Home to HouseViolet
            "mountain", "cairn", "tower", "valley", "dune", "ravine", "plain" -> Icons.Default.Terrain to TerrainRed
            else -> Icons.Default.Place to DefaultGold
        }
    }
}
