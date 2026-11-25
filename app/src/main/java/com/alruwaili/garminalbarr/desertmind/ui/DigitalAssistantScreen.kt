package com.alruwaili.garminalbarr.desertmind.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alruwaili.garminalbarr.LocationLightweight
import com.alruwaili.garminalbarr.map.NavigationMath
import java.util.Locale

// نفس لوحة الألوان الصحراوية الموحّدة المستخدمة في باقي شاشات التطبيق (مواقعي، الأدوات، الرادار).
private val HudBg = Color(0xFF1A1209)
private val HudPanel = Color(0xFF1F1A16)
private val HudCard = Color(0xFF2C2013)
private val HudBorder = Color(0xFF3D2B1F)
private val HudGold = Color(0xFFD4A017)
private val HudGreen = Color(0xFF39FF6A)
private val HudTan = Color(0xFFF5E6C8)
private val HudMuted = Color(0xFF9E9E9E)

/**
 * شاشة "المساعد الرقمي" — لوحة بيانات رقمية (HUD) تُظهر فقط ما يحسبه التطبيق فعليًا من
 * بيانات حقيقية (سرعة GPS، اتجاه حي، مسافات، أقرب المواقع المحفوظة، ارتفاع DEM فعلي)، دون أي
 * رقم أو زخرفة وهمية. أي زر معروض لا يملك وظيفة أو مصدر بيانات حقيقي في هذه الشاشة (الإعدادات،
 * طبقات الخريطة، عرض 3D، عدّاد الأقمار الصناعية، إعادة المسح، تفاصيل) يعرض "قريبًا إن شاء الله"
 * عند الضغط عليه بدلاً من إخفائه أو تلفيق بيانات له — طبقًا لتعليمات المستخدم الصريحة.
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
    onClose: () -> Unit,
    onComingSoon: () -> Unit
) {
    val headingLabel = NavigationMath.arabicDirection(azimuthDeg)
    val headingDegText = String.format(Locale.US, "%03d°", ((azimuthDeg % 360f) + 360f).toInt() % 360)

    val nearbySorted = remember(nearbyLocations, userLat, userLon) {
        if (userLat == null || userLon == null) {
            emptyList()
        } else {
            nearbyLocations
                .filter { it.latitude != null && it.longitude != null }
                .map { loc ->
                    val d = NavigationMath.distanceMeters(userLat, userLon, loc.latitude!!, loc.longitude!!)
                    loc to d
                }
                .sortedBy { it.second }
                .take(8)
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
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(HudGreen, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "مباشر",
                        color = HudGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "المساعد الرقمي",
                        color = HudGold,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    // زر الإعدادات: لا توجد له شاشة إعدادات فعلية هنا — قريبًا إن شاء الله
                    HudIconChip(icon = Icons.Default.Menu, onClick = onComingSoon, contentDescription = "الإعدادات")
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
                // ===== اللوحة اليسرى: سرعة + اتجاه رقمي + ارتفاع =====
                Column(
                    modifier = Modifier
                        .weight(0.9f)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HudStatCard(label = "السرعة الحالية", value = speedKmh?.let { String.format(Locale.US, "%.0f كم/س", it) } ?: "—")
                    HudStatCard(label = "الاتجاه", value = "$headingDegText $headingLabel")
                    HudStatCard(
                        label = "تحليل التضاريس — الارتفاع",
                        value = elevationMeters?.let { String.format(Locale.US, "%.0f م", it) } ?: "غير متاح هنا"
                    )
                    HudStatCard(
                        label = "المسافة المقطوعة",
                        value = if (isRecordingTrack) NavigationMath.formatRemaining(traveledMeters) else "يتطلب تسجيل الأثر"
                    )
                }

                // ===== الوسط: المؤشر المركزي الثابت + المسافة المتبقية =====
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // سهم ذهبي متوهج داخل حلقة — شكل ثابت لا يتغير حسب حالة التنقل (طلب صريح)
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .border(2.dp, HudGold.copy(alpha = 0.55f), CircleShape)
                            .padding(10.dp)
                            .background(HudGold.copy(alpha = 0.08f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = "موقعك الحالي",
                            tint = HudGold,
                            modifier = Modifier
                                .size(56.dp)
                                .rotate(azimuthDeg)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = headingDegText, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(text = headingLabel, color = HudMuted, fontSize = 12.sp)

                    Spacer(modifier = Modifier.height(14.dp))
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
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(text = "لا توجد وجهة نشطة حاليًا", color = HudMuted, fontSize = 12.sp)
                    }
                }

                // ===== اللوحة اليمنى: المواقع القريبة (بيانات حقيقية) =====
                Column(modifier = Modifier.weight(1.1f).fillMaxSize()) {
                    Text(
                        text = "المواقع القريبة",
                        color = HudGold,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
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
                                text = "لا توجد مواقع محفوظة قريبة",
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
                            items(nearbySorted, key = { "${it.first.id}_${it.first.title}" }) { (loc, distMeters) ->
                                val (icon, color) = PlaceCategoryIcons.iconFor(loc.title ?: "")
                                Surface(color = HudCard, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = loc.title ?: "موقع",
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = NavigationMath.formatRemaining(distMeters),
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

            // ===== الشريط السفلي: أزرار — بعضها بلا وظيفة حقيقية هنا، فتعرض "قريبًا إن شاء الله" =====
            Surface(color = HudPanel, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    HudBottomButton(icon = Icons.Default.Place, label = "طبقات الخريطة", onClick = onComingSoon)
                    HudBottomButton(icon = Icons.Default.Star, label = "عرض 3D", onClick = onComingSoon)
                    HudBottomButton(icon = Icons.Default.Refresh, label = "إعادة المسح", onClick = onComingSoon)
                    HudBottomButton(icon = Icons.Default.LocationOn, label = "الأقمار الصناعية", onClick = onComingSoon)
                    HudBottomButton(icon = Icons.Default.MoreVert, label = "تفاصيل", onClick = onComingSoon)
                }
            }
        }
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
            Text(text = value, color = HudGreen, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
