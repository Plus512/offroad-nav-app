package com.navigationgps.desert.map.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigationgps.desert.R
import com.navigationgps.desert.track.RecordingStatus
import com.navigationgps.desert.track.TrackRecordingSnapshot
import java.util.Locale

private val DesertBg = Color(0xE61F1A16)
private val DesertGold = Color(0xFFDDA01E)
private val StatusRed = Color(0xFFE53935)
private val StatusPaused = Color(0xFFFFB300)
private val DesertBorder = Color(0x66DDA01E)

@Composable
fun TrackRecordingHud(
    snapshot: TrackRecordingSnapshot,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSaveRequest: () -> Unit,
    onDiscardRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (snapshot.status == RecordingStatus.IDLE) return

    var isCollapsed by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val isPaused = snapshot.status == RecordingStatus.PAUSED
    val statusColor = if (isPaused) StatusPaused else StatusRed
    val statusText = if (isPaused) "متوقف مؤقتًا" else "تسجيل الأثر"

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = DesertBg,
        border = BorderStroke(1.dp, DesertBorder),
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 440.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Header Row: Status + Indicator + Timer + Quick Actions + Collapse
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { isCollapsed = !isCollapsed }
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                            .graphicsLayer {
                                alpha = if (!isPaused) pulseAlpha else 1f
                            }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusText,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatElapsed(snapshot.elapsedDurationSeconds),
                        color = DesertGold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Pause / Resume Action
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isPaused) Color(0x33FFB300) else Color(0x33FFFFFF),
                        modifier = Modifier.clickable {
                            if (isPaused) onResume() else onPause()
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Close,
                                contentDescription = if (isPaused) "استمرار" else "إيقاف",
                                tint = if (isPaused) StatusPaused else Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isPaused) "استئناف" else "إيقاف",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Save Action
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x334CAF50),
                        border = BorderStroke(1.dp, Color(0x664CAF50)),
                        modifier = Modifier.clickable(onClick = onSaveRequest)
                    ) {
                        Text(
                            text = "حفظ",
                            color = Color(0xFF81C784),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Discard Action
                    IconButton(
                        onClick = onDiscardRequest,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "إلغاء المسار",
                            tint = Color(0xFFE57373),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Collapse Chevron
                    IconButton(
                        onClick = { isCollapsed = !isCollapsed },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            contentDescription = "طي/توسيع",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Expanded Metrics Row (Distance, Speed, Altitude)
            AnimatedVisibility(visible = !isCollapsed) {
                Column {
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.1f))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MetricItem(
                            label = "المسافة",
                            value = formatDistance(snapshot.totalDistanceMeters)
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(20.dp)
                                .background(Color.White.copy(alpha = 0.15f))
                        )
                        MetricItem(
                            label = "السرعة",
                            value = String.format(Locale.US, "%.0f كم/س", snapshot.currentSpeedMps * 3.6f)
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(20.dp)
                                .background(Color.White.copy(alpha = 0.15f))
                        )
                        MetricItem(
                            label = "الارتفاع",
                            value = if (snapshot.currentAltitudeMeters != null) {
                                String.format(Locale.US, "%.0f م", snapshot.currentAltitudeMeters)
                            } else "—"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricItem(
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            color = DesertGold,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatElapsed(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

private fun formatDistance(distMeters: Double): String {
    return if (distMeters >= 1000.0) {
        String.format(Locale.US, "%.1f كم", distMeters / 1000.0)
    } else {
        String.format(Locale.US, "%.0f م", distMeters)
    }
}
