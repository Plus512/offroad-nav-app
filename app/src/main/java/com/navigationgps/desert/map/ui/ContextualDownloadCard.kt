package com.navigationgps.desert.map.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigationgps.desert.map.download.DownloadState
import com.navigationgps.desert.map.download.MapRegion
import com.navigationgps.desert.map.download.RegionRegistry

private val DesertDark = Color(0xFF1A1209)
private val DesertCard = Color(0xFF2C2013)
private val DesertGold = Color(0xFFD4A017)
private val DesertTan = Color(0xFFF5E6C8)
private val DesertBrown = Color(0xFF3D2B1F)

@Composable
fun ContextualDownloadCard(
    region: MapRegion,
    isMapDownloaded: Boolean = false,
    isRoutingDownloaded: Boolean = false,
    downloadState: DownloadState?,
    downloadProgress: Float?,
    downloadTitle: String? = null,
    onDownload: (region: MapRegion, downloadMap: Boolean, downloadRouting: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDownloading = downloadState == DownloadState.DOWNLOADING
    val isFullyDownloaded = isMapDownloaded && (region.routingSegments.isEmpty() || isRoutingDownloaded)

    var selectMap by remember(region.id, isMapDownloaded) { mutableStateOf(!isMapDownloaded) }
    var selectRouting by remember(region.id, isRoutingDownloaded) { mutableStateOf(!isRoutingDownloaded && region.routingSegments.isNotEmpty()) }

    if (isFullyDownloaded) {
        LaunchedEffect(region.id) {
            kotlinx.coroutines.delay(1200)
            onDismiss()
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = !isFullyDownloaded,
            enter = scaleIn(
                initialScale = 0.85f,
                animationSpec = tween(durationMillis = 250)
            ) + fadeIn(animationSpec = tween(durationMillis = 250)),
            exit = scaleOut(
                targetScale = 0.85f,
                animationSpec = tween(durationMillis = 200)
            ) + fadeOut(animationSpec = tween(durationMillis = 200)),
        ) {
            Column(
                modifier = Modifier
                    .width(310.dp)
                    .shadow(12.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(DesertCard)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = region.nameAr,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = DesertTan,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = region.nameEn,
                    fontSize = 13.sp,
                    color = Color(0xFF8B6914),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(14.dp))

                if (isDownloading) {
                    if (downloadTitle != null) {
                        Text(
                            text = downloadTitle,
                            fontSize = 12.sp,
                            color = DesertTan,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    LinearProgressIndicator(
                        progress = { downloadProgress ?: 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = DesertGold,
                        trackColor = DesertBrown
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${((downloadProgress ?: 0f) * 100).toInt()}%",
                            fontSize = 13.sp,
                            color = DesertGold
                        )

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(DesertBrown)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "إلغاء",
                                tint = DesertTan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else {
                    // Option 1: Map (Mapsforge .map)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isMapDownloaded) { selectMap = !selectMap }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = if (isMapDownloaded) true else selectMap,
                            onCheckedChange = { if (!isMapDownloaded) selectMap = it },
                            enabled = !isMapDownloaded,
                            colors = CheckboxDefaults.colors(
                                checkedColor = DesertGold,
                                checkmarkColor = DesertDark,
                                uncheckedColor = Color(0xFF8B6914)
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "خريطة الأساس (Mapsforge)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isMapDownloaded) Color(0xFF8B6914) else DesertTan
                            )
                            Text(
                                text = if (isMapDownloaded) "محملة بالفعل" else RegionRegistry.formatSize(region.sizeBytes),
                                fontSize = 11.sp,
                                color = if (isMapDownloaded) Color(0xFF4CAF50) else Color(0xFF8B6914)
                            )
                        }
                    }

                    // Option 2: Routing (BRouter .rd5)
                    if (region.routingSegments.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !isRoutingDownloaded) { selectRouting = !selectRouting }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = if (isRoutingDownloaded) true else selectRouting,
                                onCheckedChange = { if (!isRoutingDownloaded) selectRouting = it },
                                enabled = !isRoutingDownloaded,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = DesertGold,
                                    checkmarkColor = DesertDark,
                                    uncheckedColor = Color(0xFF8B6914)
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "مسار السيارة والبر (BRouter)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isRoutingDownloaded) Color(0xFF8B6914) else DesertTan
                                )
                                Text(
                                    text = if (isRoutingDownloaded) "محملة بالفعل" else RegionRegistry.formatSize(region.routingSizeBytes),
                                    fontSize = 11.sp,
                                    color = if (isRoutingDownloaded) Color(0xFF4CAF50) else Color(0xFF8B6914)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val canDownload = (selectMap && !isMapDownloaded) || (selectRouting && !isRoutingDownloaded)
                    val buttonText = when {
                        (selectMap && !isMapDownloaded) && (selectRouting && !isRoutingDownloaded) -> "تنزيل الخريطة والمسار"
                        selectMap && !isMapDownloaded -> "تنزيل الخريطة"
                        selectRouting && !isRoutingDownloaded -> "تنزيل بيانات المسار"
                        else -> "تنزيل"
                    }

                    Button(
                        onClick = {
                            if (canDownload) {
                                onDownload(region, selectMap && !isMapDownloaded, selectRouting && !isRoutingDownloaded)
                            }
                        },
                        enabled = canDownload,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DesertGold,
                            disabledContainerColor = DesertBrown
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = buttonText,
                            color = if (canDownload) Color.White else Color(0xFF8B6914),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    TextButton(onClick = onDismiss) {
                        Text("لاحقاً", color = Color(0xFF8B6914), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
