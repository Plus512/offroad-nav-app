package com.navigationgps.desert.map.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigationgps.desert.map.NavigationMath

/**
 * Stateless driving-mode HUD. Reads only 1 Hz [ActiveRouteUiState]
 * (never the 15 Hz azimuth) so recomposition stays cheap.
 *
 * Subtitle shows remaining distance only — no bearing/direction text.
 * Map stays North-Up and user-rotatable; the heading arrow on the map
 * indicates physical heading relative to the route.
 */
data class ActiveRouteUiState(
    val targetName: String,
    val remainingMeters: Double?,
    val isCarRoute: Boolean = false,
    val isCalculatingRoute: Boolean = false
)

@Composable
fun NavigationHud(
    state: ActiveRouteUiState,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    onToggleRouteMode: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val arrived = state.remainingMeters != null &&
            NavigationMath.isArrived(state.remainingMeters)
    val metricsText = if (state.remainingMeters == null) {
        "جاري تحديد المسافة…"
    } else if (arrived) {
        "0 م | وصلت"
    } else {
        NavigationMath.formatRemaining(state.remainingMeters)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xE61F1A16),
        border = BorderStroke(1.dp, Color(0xFFDDA01E)),
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 440.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
            ) {
                Text(
                    text = state.targetName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = metricsText,
                    color = Color(0xFFDDA01E),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (onToggleRouteMode != null) {
                    if (state.isCalculatingRoute) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(22.dp)
                                .padding(2.dp),
                            color = Color(0xFFDDA01E),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                    } else {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (state.isCarRoute) Color(0xFF3E2723) else Color(0xFF2C2013),
                            border = BorderStroke(1.dp, if (state.isCarRoute) Color(0xFFFF6D00) else Color(0xFFDDA01E)),
                            modifier = Modifier
                                .padding(end = 2.dp)
                                .clickable { onToggleRouteMode() }
                        ) {
                            Text(
                                text = if (state.isCarRoute) "🚗 بر" else "🧭 مباشر",
                                color = if (state.isCarRoute) Color(0xFFFF6D00) else Color(0xFFDDA01E),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "تعديل المسار",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "حذف المسار",
                        tint = Color(0xFFE53935),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
