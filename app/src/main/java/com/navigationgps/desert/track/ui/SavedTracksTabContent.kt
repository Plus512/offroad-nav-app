package com.navigationgps.desert.track.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigationgps.desert.R
import com.navigationgps.desert.track.TrackEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DesertCard = Color(0xFF2C2013)
private val DesertCardBorder = Color(0xFF3D2B1F)
private val DesertGold = Color(0xFFDDA01E)
private val DesertTan = Color(0xFFF5E6C8)
private val DesertMuted = Color(0xFF9E9E9E)

@Composable
fun SavedTracksTabContent(
    tracks: List<TrackEntity>,
    onTrackClick: (TrackEntity) -> Unit,
    onToggleVisibility: (TrackEntity) -> Unit,
    onBacktrack: (TrackEntity) -> Unit,
    onExportGpx: (TrackEntity) -> Unit,
    onDeleteTrack: (TrackEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var trackPendingDelete by remember { mutableStateOf<TrackEntity?>(null) }

    if (tracks.isEmpty()) {
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
                    Text(
                        text = "〰",
                        fontSize = 32.sp,
                        color = DesertGold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "لا توجد مسارات مسجلة بعد",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "عند قيادتك في الصحراء والبر، اضغط على زر 'تسجيل الأثر' على الخريطة ليتم رسم أثر حركتك وحفظه هنا للعودة عليه لاحقًا.",
                    color = DesertMuted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tracks, key = { it.id }) { track ->
                TrackCardItem(
                    track = track,
                    onClick = { onTrackClick(track) },
                    onToggleVisibility = { onToggleVisibility(track) },
                    onBacktrack = { onBacktrack(track) },
                    onExportGpx = { onExportGpx(track) },
                    onDelete = { trackPendingDelete = track }
                )
            }
        }
    }

    // Delete Confirmation Dialog
    val trackToDelete = trackPendingDelete
    if (trackToDelete != null) {
        AlertDialog(
            onDismissRequest = { trackPendingDelete = null },
            modifier = Modifier.border(1.dp, DesertCardBorder, RoundedCornerShape(16.dp)),
            title = {
                Text(
                    text = "حذف المسار المسجل",
                    color = DesertGold,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = "هل أنت متأكد من حذف مسار '${trackToDelete.title}'؟ سيتم حذف جميع النقاط المسجلة نهائيًا.",
                    color = DesertTan,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteTrack(trackToDelete)
                        trackPendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("حذف نهائي", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { trackPendingDelete = null }) {
                    Text("إلغاء", color = DesertTan)
                }
            },
            containerColor = DesertCard,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun TrackCardItem(
    track: TrackEntity,
    onClick: () -> Unit,
    onToggleVisibility: () -> Unit,
    onBacktrack: () -> Unit,
    onExportGpx: () -> Unit,
    onDelete: () -> Unit
) {
    val trackColor = try {
        Color(android.graphics.Color.parseColor(track.colorHex))
    } catch (_: Exception) {
        DesertGold
    }

    val dateFormatted = remember(track.startTimeMillis) {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
        sdf.format(Date(track.startTimeMillis))
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DesertCard),
        border = BorderStroke(1.dp, DesertCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            // Header: Color Indicator + Title + Date + Visibility Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(trackColor)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = dateFormatted,
                        color = DesertMuted,
                        fontSize = 11.sp
                    )
                }

                // Visibility Toggle Button
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (track.isVisibleOnMap) Color(0x33DDA01E) else Color(0x22FFFFFF),
                    border = BorderStroke(
                        1.dp,
                        if (track.isVisibleOnMap) DesertGold else Color(0x44FFFFFF)
                    ),
                    modifier = Modifier.clickable(onClick = onToggleVisibility)
                ) {
                    Text(
                        text = if (track.isVisibleOnMap) "ظاهر بالخريطة" else "مخفي",
                        color = if (track.isVisibleOnMap) DesertGold else DesertMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Optional Description
            if (!track.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = track.description,
                    color = DesertTan,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Metrics Row
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0x441F1A16),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetricText(label = "المسافة", value = formatDistance(track.totalDistanceMeters))
                    MetricDivider()
                    MetricText(label = "المدة", value = formatDuration(track.durationSeconds))
                    MetricDivider()
                    MetricText(label = "متوسط السرعة", value = String.format(Locale.US, "%.0f كم/س", track.avgSpeedMps * 3.6f))
                    if (track.maxAltitudeMeters != null) {
                        MetricDivider()
                        MetricText(label = "أعلى ارتفاع", value = String.format(Locale.US, "%.0f م", track.maxAltitudeMeters))
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Backtrack Button ("العودة على نفس الأثر")
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0x2200E5FF),
                    border = BorderStroke(1.dp, Color(0x6600E5FF)),
                    modifier = Modifier.clickable(onClick = onBacktrack)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "↩ العودة على الأثر",
                            color = Color(0xFF00E5FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Export GPX
                    IconButton(
                        onClick = onExportGpx,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "تصدير GPX",
                            tint = DesertGold,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Delete
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "حذف المسار",
                            tint = Color(0xFFE57373),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricText(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = DesertMuted, fontSize = 9.sp)
        Text(text = value, color = DesertGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MetricDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(18.dp)
            .background(Color.White.copy(alpha = 0.15f))
    )
}

private fun formatDistance(distMeters: Double): String {
    return if (distMeters >= 1000.0) {
        String.format(Locale.US, "%.1f كم", distMeters / 1000.0)
    } else {
        String.format(Locale.US, "%.0f م", distMeters)
    }
}

private fun formatDuration(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%d س %d د", h, m)
    } else {
        String.format(Locale.US, "%d د", m)
    }
}
