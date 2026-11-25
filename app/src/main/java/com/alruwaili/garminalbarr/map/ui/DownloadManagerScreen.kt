package com.alruwaili.garminalbarr.map.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

private val DesertDark = Color(0xFF1A1209)
private val DesertCard = Color(0xFF2C2013)
private val DesertGold = Color(0xFFD4A017)
private val DesertTan = Color(0xFFF5E6C8)
private val DesertBrown = Color(0xFF3D2B1F)
private val StatusGreen = Color(0xFF2E7D32)
private val StatusRed = Color(0xFFD32F2F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerScreen(
    regions: List<MapRegion>,
    downloadStates: Map<String, DownloadState>,
    downloadProgress: Map<String, Float>,
    routingStates: Map<String, DownloadState> = emptyMap(),
    routingProgress: Map<String, Float> = emptyMap(),
    storageUsedBytes: Long,
    routingStorageUsedBytes: Long = 0L,
    onDownload: (MapRegion) -> Unit,
    onDelete: (MapRegion) -> Unit,
    onDownloadRouting: (MapRegion) -> Unit = {},
    onDeleteRouting: (MapRegion) -> Unit = {},
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    MaterialTheme {
        Scaffold(
            containerColor = DesertDark,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "مدير التحميل والخرائط",
                            fontWeight = FontWeight.Bold,
                            color = DesertTan,
                            fontSize = 18.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "رجوع",
                                tint = DesertTan
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = DesertBrown
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(DesertDark)
            ) {
                // Storage Summary Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "إجمالي المساحة المستخدمة",
                            fontSize = 13.sp,
                            color = DesertTan
                        )
                        Text(
                            text = "الخرائط: ${RegionRegistry.formatSize(storageUsedBytes)} | المسارات: ${RegionRegistry.formatSize(routingStorageUsedBytes)}",
                            fontSize = 11.sp,
                            color = Color(0xFF8B6914)
                        )
                    }
                    Text(
                        text = RegionRegistry.formatSize(storageUsedBytes + routingStorageUsedBytes),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = DesertGold
                    )
                }

                // 2-Tab Segmented Control
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = DesertBrown,
                    contentColor = DesertGold,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = DesertGold,
                            height = 3.dp
                        )
                    }
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                text = "الخرائط المرئية (.map)",
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == 0) DesertGold else DesertTan,
                                fontSize = 13.sp
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                text = "بيانات مسار السيارة (.rd5)",
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == 1) DesertGold else DesertTan,
                                fontSize = 13.sp
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // List Content based on Selected Tab
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedTab == 0) {
                        items(regions, key = { "map_${it.id}" }) { region ->
                            RegionCard(
                                titleAr = region.nameAr,
                                subtitle = "${region.nameEn} • ${RegionRegistry.formatSize(region.sizeBytes)}",
                                badgeText = null,
                                state = downloadStates[region.id] ?: DownloadState.NOT_DOWNLOADED,
                                progress = downloadProgress[region.id] ?: 0f,
                                onDownload = { onDownload(region) },
                                onDelete = { onDelete(region) }
                            )
                        }
                    } else {
                        items(regions, key = { "routing_${it.id}" }) { region ->
                            val segCount = region.routingSegments.size
                            val badge = when (region.id) {
                                "bahrain" -> "يشترك مع قطر والسعودية"
                                "qatar" -> "يشترك مع البحرين والإمارات والسعودية"
                                "uae" -> "يشترك مع عُمان والسعودية"
                                "oman" -> "يشترك مع الإمارات واليمن"
                                "kuwait" -> "يشترك مع السعودية"
                                else -> null
                            }
                            RegionCard(
                                titleAr = region.nameAr,
                                subtitle = "$segCount مقاطع • ${RegionRegistry.formatSize(region.routingSizeBytes)}",
                                badgeText = badge,
                                state = routingStates[region.id] ?: DownloadState.NOT_DOWNLOADED,
                                progress = routingProgress[region.id] ?: 0f,
                                onDownload = { onDownloadRouting(region) },
                                onDelete = { onDeleteRouting(region) }
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RegionCard(
    titleAr: String,
    subtitle: String,
    badgeText: String?,
    state: DownloadState,
    progress: Float,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(DesertCard)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titleAr,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = DesertTan
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = Color(0xFF8B6914)
            )
            if (badgeText != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(DesertGold.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 10.sp,
                        color = DesertGold,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        when (state) {
            DownloadState.NOT_DOWNLOADED -> {
                Button(
                    onClick = onDownload,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DesertGold
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("تحميل", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            DownloadState.DOWNLOADING -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(32.dp),
                        color = DesertGold,
                        trackColor = DesertBrown
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        fontSize = 11.sp,
                        color = DesertGold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            DownloadState.DOWNLOADED -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(StatusGreen.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "محمل",
                            tint = StatusGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "حذف",
                            tint = StatusRed
                        )
                    }
                }
            }
            DownloadState.UPDATE_AVAILABLE -> {
                Button(
                    onClick = onDownload,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DesertGold
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("تحديث", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}
