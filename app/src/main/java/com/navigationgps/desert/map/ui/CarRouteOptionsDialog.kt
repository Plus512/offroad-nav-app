package com.navigationgps.desert.map.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.navigationgps.desert.map.NavigationMath
import com.navigationgps.desert.map.routing.CarRouteMode

private val DesertCard = Color(0xFF2C2013)
private val DesertGold = Color(0xFFDDA01E)
private val DesertTan = Color(0xFFC4A882)
private val DesertAccent = Color(0xFFFF6D00)
private val OptionCardBg = Color(0xFF382A1B)

/**
 * Desert-themed Arabic RTL selection dialog for car route calculation mode.
 *
 * Provides:
 * - Option 1: "أقرب وصول بالسيارة (الوضع الحالي)" (Closest paved approach to target, minimizes sand driving).
 * - Option 2: "أقصر مسار إجمالي" (Shortest overall physical distance, avoids highway loops).
 */
@Composable
fun CarRouteOptionsDialog(
    precalculatedDistanceKm: Double?,
    onSelectMode: (CarRouteMode) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = DesertCard,
                border = BorderStroke(1.5.dp, DesertGold),
                shadowElevation = 16.dp,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .wrapContentHeight()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "اختر نوع مسار السيارة",
                        color = DesertGold,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "حدد طريقة حساب مسار القيادة المناسبة لرحلتك:",
                        color = DesertTan,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Option 1 Card: Closest Paved Approach (Current Logic)
                    RouteOptionItem(
                        title = "أقرب وصول بالسيارة (الوضع الحالي)",
                        subtitle = "محاولة إيصالك بالسيارة لأقرب نقطة وشارع معبد ممكن من الهدف لتقليل مسافة الرمال.",
                        badge = if (precalculatedDistanceKm != null && precalculatedDistanceKm > 0) {
                            "المسافة المقدرة: ${NavigationMath.formatRemaining(precalculatedDistanceKm * 1000.0)}"
                        } else null,
                        badgeColor = DesertGold,
                        borderColor = DesertGold.copy(alpha = 0.6f),
                        onClick = { onSelectMode(CarRouteMode.CLOSEST_PAVED) }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Option 2 Card: Shortest Overall Route (New Logic)
                    RouteOptionItem(
                        title = "أقصر مسار إجمالي",
                        subtitle = "رسم أقصر طريق كلي يوصلك للهدف بالسيارة لتوفير المسافة وتجنب الالتفافات الكبيرة.",
                        badge = "توفير المسافة واختصار الطرق البرية",
                        badgeColor = DesertAccent,
                        borderColor = DesertAccent.copy(alpha = 0.6f),
                        onClick = { onSelectMode(CarRouteMode.SHORTEST_TOTAL) }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Dismiss / Cancel Button
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "إلغاء",
                            color = DesertTan,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteOptionItem(
    title: String,
    subtitle: String,
    badge: String?,
    badgeColor: Color,
    borderColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = OptionCardBg,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = subtitle,
                color = DesertTan,
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            )

            if (badge != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = badge,
                        color = badgeColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
