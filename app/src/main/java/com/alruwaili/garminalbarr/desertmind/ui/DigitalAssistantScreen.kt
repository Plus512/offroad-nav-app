package com.alruwaili.garminalbarr.desertmind.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alruwaili.garminalbarr.LocationLightweight
import com.alruwaili.garminalbarr.map.NavigationMath
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

// نفس لوحة الألوان الصحراوية الموحّدة المستخدمة في باقي شاشات التطبيق (مواقعي، الأدوات، الرادار).
private val HudBg = Color(0xFF0E0B07)
private val HudPanel = Color(0xFF1F1A16)
private val HudCard = Color(0xFF2C2013)
private val HudBorder = Color(0xFF3D2B1F)
private val HudGold = Color(0xFFD4A017)
private val HudGreen = Color(0xFF39FF6A)
private val HudTan = Color(0xFFF5E6C8)
private val HudMuted = Color(0xFF9E9E9E)

/**
 * شاشة "الملاحة الرقمية" — لوحة بيانات رقمية (HUD) على طراز رادار الطائرات، تُظهر فقط ما
 * يحسبه التطبيق فعليًا من بيانات حقيقية (سرعة GPS، اتجاه حي، إحداثيات، ارتفاع DEM فعلي، عدد
 * أقمار GNSS المرئية فعليًا، والمواقع القريبة المحفوظة بمسافاتها واتجاهاتها الحقيقية)، دون أي
 * رقم أو زخرفة وهمية. أي زر لا يملك وظيفة أو مصدر بيانات حقيقي في هذه الشاشة (طبقات الخريطة،
 * عرض 3D، التفاصيل) يعرض "قريبًا إن شاء الله" عند الضغط عليه بدلاً من إخفائه أو تلفيق بيانات له
 * — طبقًا لتعليمات المستخدم الصريحة.
 */
@Composable
fun DigitalAssistantScreen(
    speedKmh: Float?,
    azimuthDeg: Float,
    hasActiveRoute: Boolean,
    destinationName: String?,
    remainingMeters: Double?,
    isRecordingTrack: Boolean,
    traveledMeters: Double,
    elevationMeters: Double?,
    nearbyLocations: List<LocationLightweight>,
    userLat: Double?,
    userLon: Double?,
    radiusKm: Int,
    satellitesInView: Int?,
    onClose: () -> Unit,
    onComingSoon: () -> Unit,
    onOpenRadiusSettings: () -> Unit,
    onRescan: () -> Unit,
    onGoTo: (lat: Double, lon: Double, title: String) -> Unit
) {
    val headingLabel = NavigationMath.arabicDirection(azimuthDeg)
    val headingDegText = String.format(Locale.US, "%03d°", ((azimuthDeg % 360f) + 360f).toInt() % 360)
    val radiusMeters = radiusKm * 1000.0

    // المواقع ضمن نطاق البحث الحالي فقط، مرتبة من الأقرب للأبعد — بيانات حقيقية (مسافة
    // واتجاه محسوبان فعليًا من موقع المستخدم الحي)، وليست قائمة ثابتة.
    data class NearbyTarget(val loc: LocationLightweight, val distanceMeters: Double, val bearingDeg: Float)

    val nearbySorted = remember(nearbyLocations, userLat, userLon, radiusKm) {
        if (userLat == null || userLon == null) {
            emptyList()
        } else {
            nearbyLocations
                .filter { it.latitude != null && it.longitude != null }
                .map { loc ->
                    val d = NavigationMath.distanceMeters(userLat, userLon, loc.latitude!!, loc.longitude!!)
                    val b = NavigationMath.bearingDeg(userLat, userLon, loc.latitude!!, loc.longitude!!)
                    NearbyTarget(loc, d, b)
                }
                .filter { it.distanceMeters <= radiusMeters }
                .sortedBy { it.distanceMeters }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HudBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ===== الشريط العلوي =====
            Surface(color = HudPanel, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 36.dp, bottom = 10.dp, start = 12.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = null,
                        tint = HudGold,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "الملاحة الرقمية",
                        color = HudGold,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(HudGreen, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "مباشر — موقعك الحالي",
                        color = HudGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // "نطاق البحث" — عدد حقيقي قابل للتعديل (يفتح نفس نافذة اختيار نطاق
                    // الرادار الصحراوي)، وليس زخرفة ثابتة.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(HudCard, RoundedCornerShape(8.dp))
                            .clickable(onClick = onOpenRadiusSettings)
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "نطاق البحث",
                            tint = HudTan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "نطاق البحث $radiusKm كم", color = HudTan, fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    HudIconChip(icon = Icons.Default.Close, onClick = onClose, contentDescription = "إغلاق", tint = HudTan)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ===== اللوحة اليسرى: اتجاه + موقع + ارتفاع + سرعة (بيانات حقيقية حية) =====
                Column(
                    modifier = Modifier
                        .weight(0.85f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HudStatCard(label = "الاتجاه الحالي", value = "$headingDegText $headingLabel")
                    HudStatCard(
                        label = "الموقع الحالي",
                        value = if (userLat != null && userLon != null) {
                            String.format(Locale.US, "N %.4f°\nE %.4f°", userLat, userLon)
                        } else "بانتظار GPS…"
                    )
                    HudStatCard(
                        label = "الارتفاع",
                        value = elevationMeters?.let { String.format(Locale.US, "%.0f م", it) } ?: "غير متاح هنا"
                    )
                    HudStatCard(label = "السرعة", value = speedKmh?.let { String.format(Locale.US, "%.0f كم/س", it) } ?: "0 كم/س")
                }

                // ===== الوسط: رادار/بوصلة دائرية حقيقية — المواقع القريبة ضمن النطاق =====
                Column(
                    modifier = Modifier
                        .weight(1.3f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    DigitalAssistantRadarPanel(
                        azimuthDeg = azimuthDeg,
                        radiusMeters = radiusMeters,
                        targets = nearbySorted.map { RadarLabelTarget(it.loc.title ?: "موقع", it.distanceMeters, it.bearingDeg) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    if (hasActiveRoute && remainingMeters != null) {
                        Text(
                            text = "المتبقي إلى ${destinationName ?: "الوجهة"}",
                            color = HudMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = NavigationMath.formatRemaining(remainingMeters),
                            color = HudGreen,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else if (isRecordingTrack) {
                        Text(
                            text = "الأثر المسجَّل: ${NavigationMath.formatRemaining(traveledMeters)}",
                            color = HudMuted,
                            fontSize = 11.sp
                        )
                    } else {
                        Text(text = "لا توجد وجهة نشطة حاليًا", color = HudMuted, fontSize = 11.sp)
                    }
                }

                // ===== اللوحة اليمنى: المواقع القريبة ضمن النطاق (بيانات حقيقية) =====
                Column(modifier = Modifier.weight(1.05f).fillMaxHeight()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "المواقع القريبة",
                            color = HudGold,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        HudIconChip(icon = Icons.Default.Refresh, onClick = onRescan, contentDescription = "تحديث")
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    if (userLat == null || userLon == null) {
                        Surface(color = HudCard, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "بانتظار تحديد موقعك الحالي…",
                                color = HudMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    } else if (nearbySorted.isEmpty()) {
                        Surface(color = HudCard, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "لا توجد مواقع محفوظة ضمن نطاق $radiusKm كم",
                                color = HudMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(nearbySorted, key = { "${it.loc.id}_${it.loc.title}" }) { target ->
                                val (icon, color) = PlaceCategoryIcons.iconFor(target.loc.title ?: "")
                                Surface(
                                    color = HudCard,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onGoTo(target.loc.latitude!!, target.loc.longitude!!, target.loc.title ?: "موقع")
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = target.loc.title ?: "موقع",
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${NavigationMath.formatRemaining(target.distanceMeters)} — ${NavigationMath.arabicDirection(target.bearingDeg)}",
                                                color = HudTan,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ===== الشريط السفلي =====
            Surface(color = HudPanel, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HudBottomButton(
                        icon = Icons.Default.Navigation,
                        label = "الذهاب إلى الموقع",
                        onClick = {
                            nearbySorted.firstOrNull()?.let { target ->
                                onGoTo(target.loc.latitude!!, target.loc.longitude!!, target.loc.title ?: "موقع")
                            }
                        }
                    )
                    HudBottomButton(icon = Icons.Default.Place, label = "طبقات الخريطة", onClick = onComingSoon)
                    HudBottomButton(icon = Icons.Default.Star, label = "عرض 3D", onClick = onComingSoon)

                    // زر المسح الجديد — دائري أكبر في المنتصف، كما في التصميم المرجعي، ووظيفته
                    // حقيقية (إعادة حساب الارتفاع والمواقع القريبة الآن وليس وهمية).
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .border(2.dp, HudGold, CircleShape)
                            .background(HudGold.copy(alpha = 0.12f), CircleShape)
                            .clickable(onClick = onRescan),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "مسح جديد", tint = HudGold, modifier = Modifier.size(24.dp))
                    }

                    HudBottomButton(icon = Icons.Default.MoreVert, label = "التفاصيل", onClick = onComingSoon)

                    // عدّاد أقمار GNSS الحقيقي — قراءة بيانات فقط، ليس زرًا؛ يعرض "—" بدل رقم
                    // وهمي إن لم يتوفر بعد (قبل أول قراءة من مستشعر GPS).
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.LocationOn, contentDescription = null, tint = HudGreen, modifier = Modifier.size(18.dp))
                        Text(
                            text = satellitesInView?.toString() ?: "—",
                            color = HudGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(text = "أقمار GPS", color = HudMuted, fontSize = 8.sp)
                    }
                }
            }
        }
    }
}

private data class RadarLabelTarget(val title: String, val distanceMeters: Double, val bearingDeg: Float)

/**
 * لوحة رادار/بوصلة دائرية ثابتة (بلا خط مسح متحرك، بخلاف "الرادار الصحراوي" كامل الشاشة):
 * حلقات مدى + حروف اتجاهات + سهم مركزي يدور فعليًا حسب اتجاه السير الحقيقي (azimuthDeg) +
 * نقاط المواقع القريبة الحقيقية مع تفادي تداخل أسمائها (نفس أسلوب "الرادار الصحراوي").
 */
@Composable
private fun DigitalAssistantRadarPanel(
    azimuthDeg: Float,
    radiusMeters: Double,
    targets: List<RadarLabelTarget>,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val radiusPx = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(color = HudGold.copy(alpha = 0.5f), radius = radiusPx, center = center, style = Stroke(width = 2.dp.toPx()))
        for (fraction in listOf(0.33f, 0.66f, 1f)) {
            drawCircle(
                color = HudGold.copy(alpha = 0.22f),
                radius = radiusPx * fraction,
                center = center,
                style = Stroke(width = 1.2f)
            )
        }
        drawLine(color = HudGold.copy(alpha = 0.3f), start = Offset(center.x, center.y - radiusPx), end = Offset(center.x, center.y + radiusPx), strokeWidth = 1f)
        drawLine(color = HudGold.copy(alpha = 0.3f), start = Offset(center.x - radiusPx, center.y), end = Offset(center.x + radiusPx, center.y), strokeWidth = 1f)

        val cardinalStyle = TextStyle(color = HudGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        val cardinals = listOf(
            "شمال" to Offset(center.x, center.y - radiusPx - 14f),
            "شرق" to Offset(center.x + radiusPx + 14f, center.y),
            "جنوب" to Offset(center.x, center.y + radiusPx + 14f),
            "غرب" to Offset(center.x - radiusPx - 14f, center.y)
        )
        for ((label, pos) in cardinals) {
            val layout = textMeasurer.measure(label, cardinalStyle)
            drawText(textLayoutResult = layout, topLeft = Offset(pos.x - layout.size.width / 2f, pos.y - layout.size.height / 2f))
        }

        // المواقع القريبة — الأقرب أولًا لأولوية إظهار اسمه عند الازدحام (نفس أسلوب تفادي
        // التداخل المستخدم في شاشة الرادار الصحراوي الكاملة).
        val placedLabelRects = mutableListOf<Rect>()
        for (target in targets.sortedBy { it.distanceMeters }) {
            val distFraction = (target.distanceMeters / radiusMeters).coerceIn(0.0, 1.0)
            val pointRad = Math.toRadians((target.bearingDeg - 90.0).toDouble())
            val point = Offset(
                center.x + (radiusPx * distFraction * cos(pointRad)).toFloat(),
                center.y + (radiusPx * distFraction * sin(pointRad)).toFloat()
            )
            val (_, categoryColor) = PlaceCategoryIcons.iconFor(target.title)
            drawCircle(color = categoryColor, radius = 4f, center = point)
            drawCircle(color = categoryColor, radius = 4f, center = point, style = Stroke(width = 1f))

            val labelText = "${NavigationMath.formatRemaining(target.distanceMeters)}"
            val layout = textMeasurer.measure(
                labelText,
                TextStyle(color = HudTan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            )
            val w = layout.size.width.toFloat()
            val h = layout.size.height.toFloat()
            val candidates = listOf(
                Offset(point.x + 6f, point.y - h / 2f),
                Offset(point.x - w - 6f, point.y - h / 2f),
                Offset(point.x - w / 2f, point.y - h - 6f),
                Offset(point.x - w / 2f, point.y + 6f)
            )
            for (candidate in candidates) {
                val rect = Rect(candidate.x, candidate.y, candidate.x + w, candidate.y + h)
                if (placedLabelRects.none { it.overlaps(rect) }) {
                    drawText(textLayoutResult = layout, topLeft = candidate)
                    placedLabelRects.add(rect)
                    break
                }
            }
        }

        // السهم المركزي — يدور فعليًا حسب اتجاه السير الحقيقي (ليس شكلًا ثابتًا مزخرفًا).
        val headingRad = Math.toRadians((azimuthDeg - 90.0).toDouble())
        val arrowTip = Offset(center.x + (26f * cos(headingRad)).toFloat(), center.y + (26f * sin(headingRad)).toFloat())
        drawLine(color = HudGold, start = center, end = arrowTip, strokeWidth = 3f)
        drawCircle(color = HudGold, radius = 9f, center = center)
        drawCircle(color = Color.White, radius = 3.5f, center = center)
    }
}

@Composable
private fun HudStatCard(label: String, value: String) {
    Surface(
        color = HudPanel,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(text = label, color = HudMuted, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = value, color = HudGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HudIconChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    contentDescription: String,
    tint: Color = HudGold
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(HudCard, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun HudBottomButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(HudCard, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = HudTan, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(text = label, color = HudMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
