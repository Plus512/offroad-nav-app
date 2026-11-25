package com.alruwaili.garminalbarr.map.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alruwaili.garminalbarr.map.download.DownloadState
import com.alruwaili.garminalbarr.map.download.MapRegion
import com.alruwaili.garminalbarr.map.download.RegionRegistry

@Composable
fun DownloadBanner(
    region: MapRegion,
    downloadState: DownloadState?,
    downloadProgress: Float?,
    onDownload: (MapRegion) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(durationMillis = 300)
        ) + fadeIn(animationSpec = tween(durationMillis = 300)),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(durationMillis = 250)
        ) + fadeOut(animationSpec = tween(durationMillis = 250)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .shadow(8.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF2C2013))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "\uD83D\uDDFA\uFE0F خريطة ${region.nameAr}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF5E6C8),
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = RegionRegistry.formatSize(region.sizeBytes),
                    fontSize = 12.sp,
                    color = Color(0xFFD4A017),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            if (downloadState == DownloadState.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { downloadProgress ?: 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = Color(0xFFD4A017),
                    trackColor = Color(0xFF3D2B1F)
                )

                Text(
                    text = "${((downloadProgress ?: 0f) * 100).toInt()}%",
                    fontSize = 12.sp,
                    color = Color(0xFFD4A017),
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("لاحقاً", color = Color(0xFF8B6914))
                    }

                    Button(
                        onClick = { onDownload(region) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD4A017)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("تحميل الآن", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
