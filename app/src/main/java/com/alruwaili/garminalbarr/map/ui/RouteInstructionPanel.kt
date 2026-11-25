package com.alruwaili.garminalbarr.map.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alruwaili.garminalbarr.map.routing.OfflineRouter

@Composable
fun RouteInstructionPanel(
    instructions: List<OfflineRouter.RouteInstruction>,
    totalDistanceMeters: Double,
    totalDurationSeconds: Double,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFF8F4E8))
            .padding(16.dp)
    ) {
        Text(
            text = "المسار",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF3D2B1F)
        )

        Text(
            text = "المسافة: ${formatDistance(totalDistanceMeters)} | الوقت: ${formatDuration(totalDurationSeconds)}",
            fontSize = 14.sp,
            color = Color(0xFF8B6914)
        )

        instructions.forEachIndexed { index, instruction ->
            Text(
                text = "${index + 1}. ${instruction.text} (${formatDistance(instruction.distanceMeters)})",
                fontSize = 14.sp,
                color = Color(0xFF3D2B1F),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

private fun formatDistance(meters: Double): String {
    return if (meters >= 1000) {
        "${"%.1f".format(meters / 1000)} كم"
    } else {
        "${"%.0f".format(meters)} م"
    }
}

private fun formatDuration(seconds: Double): String {
    val hours = seconds.toInt() / 3600
    val minutes = (seconds.toInt() % 3600) / 60
    return if (hours > 0) {
        "$hours ساعة $minutes دقيقة"
    } else {
        "$minutes دقيقة"
    }
}
