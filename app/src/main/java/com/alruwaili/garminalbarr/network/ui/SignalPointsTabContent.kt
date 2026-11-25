package com.alruwaili.garminalbarr.network.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alruwaili.garminalbarr.LocationData
import com.alruwaili.garminalbarr.map.NavigationMath
import com.alruwaili.garminalbarr.network.SignalPointEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DesertCard = Color(0xFF2C2013)
private val DesertCardBorder = Color(0xFF3D2B1F)
private val DesertGold = Color(0xFFD4A017)
private val DesertTan = Color(0xFFF5E6C8)
private val DesertMuted = Color(0xFF9E9E9E)
private val StatusRed = Color(0xFFE53935)

/**
 * "نقاط الشبكة" tab content — the list of signal points auto-recorded by
 * [com.alruwaili.garminalbarr.network.NetworkSpotterService]. Tapping a point opens an
 * options sheet: حفظ (rename its custom label), ذهاب إلى (navigate to it), عرض (show
 * compass direction + distance from the user's current position).
 */
@Composable
fun SignalPointsTabContent(
    points: List<SignalPointEntity>,
    currentLocation: LocationData?,
    onGoTo: (SignalPointEntity) -> Unit,
    onRename: (SignalPointEntity, String?) -> Unit,
    onDelete: (SignalPointEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedPoint by remember { mutableStateOf<SignalPointEntity?>(null) }
    var pointBeingRenamed by remember { mutableStateOf<SignalPointEntity?>(null) }
    var pointBeingViewed by remember { mutableStateOf<SignalPointEntity?>(null) }

    if (points.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(DesertCard),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = StatusRed.copy(alpha = 0.6f),
                        modifier = Modifier.size(38.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "لا توجد نقاط شبكة مسجلة بعد",
                    color = DesertTan,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "فعّل راصد الشبكة من الشاشة الرئيسية ليتم تسجيل نقطة تلقائيًا عند فقدان تغطية الشبكة",
                    color = DesertMuted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(points, key = { it.id }) { point ->
                SignalPointCard(
                    point = point,
                    onClick = { selectedPoint = point }
                )
            }
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    // Tap options: حفظ / ذهاب إلى / عرض
    selectedPoint?.let { point ->
        AlertDialog(
            onDismissRequest = { selectedPoint = null },
            containerColor = DesertCard,
            title = {
                Text(text = point.displayName, color = DesertGold, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    OptionRow("حفظ") {
                        pointBeingRenamed = point
                        selectedPoint = null
                    }
                    OptionRow("ذهاب إلى") {
                        onGoTo(point)
                        selectedPoint = null
                    }
                    OptionRow("عرض") {
                        pointBeingViewed = point
                        selectedPoint = null
                    }
                    OptionRow("حذف", color = StatusRed) {
                        onDelete(point)
                        selectedPoint = null
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedPoint = null }) {
                    Text("إغلاق", color = DesertMuted)
                }
            }
        )
    }

    // حفظ (rename) dialog
    pointBeingRenamed?.let { point ->
        var text by remember(point.id) { mutableStateOf(point.customLabel ?: "") }
        AlertDialog(
            onDismissRequest = { pointBeingRenamed = null },
            containerColor = DesertCard,
            title = { Text("حفظ باسم", color = DesertGold, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "سيظهر دائمًا باسم \"نقطة شبكة\" بالإضافة إلى الاسم الذي تختاره",
                        color = DesertMuted,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        placeholder = { Text("مثال: ألف") },
                        keyboardOptions = KeyboardOptions.Default
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(point, text.trim().ifBlank { null })
                    pointBeingRenamed = null
                }) {
                    Text("حفظ", color = DesertGold, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pointBeingRenamed = null }) {
                    Text("إلغاء", color = DesertMuted)
                }
            }
        )
    }

    // عرض (view direction + distance) dialog
    pointBeingViewed?.let { point ->
        val distanceMeters = currentLocation?.let {
            NavigationMath.distanceMeters(it.latitude, it.longitude, point.latitude, point.longitude)
        }
        val bearing = currentLocation?.let {
            NavigationMath.bearingDeg(it.latitude, it.longitude, point.latitude, point.longitude)
        }
        val directionText = bearing?.let { NavigationMath.arabicDirection(it) } ?: "—"
        val distanceText = distanceMeters?.let { NavigationMath.formatRemaining(it) } ?: "جاري تحديد المسافة…"

        AlertDialog(
            onDismissRequest = { pointBeingViewed = null },
            containerColor = DesertCard,
            title = { Text(point.displayName, color = DesertGold, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(text = "الاتجاه: $directionText", color = DesertTan, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "المسافة: $distanceText", color = DesertTan, fontSize = 14.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onGoTo(point)
                    pointBeingViewed = null
                }) {
                    Text("الذهاب إليها", color = DesertGold, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pointBeingViewed = null }) {
                    Text("إغلاق", color = DesertMuted)
                }
            }
        )
    }
}

@Composable
private fun OptionRow(label: String, color: Color = DesertTan, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SignalPointCard(
    point: SignalPointEntity,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = DesertCard),
        border = BorderStroke(1.dp, DesertCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(StatusRed)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = point.displayName,
                    color = DesertTan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatCapturedAt(point.capturedAtMillis),
                    color = DesertMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

private fun formatCapturedAt(millis: Long): String {
    val fmt = SimpleDateFormat("yyyy/MM/dd - HH:mm", Locale.US)
    return fmt.format(Date(millis))
}
