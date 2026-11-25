package com.alruwaili.garminalbarr.mylocations.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.alruwaili.garminalbarr.LocationData
import com.alruwaili.garminalbarr.LocationEntity
import com.alruwaili.garminalbarr.R
import com.alruwaili.garminalbarr.map.MapNavigationHelper
import com.alruwaili.garminalbarr.map.NavigationMath
import com.alruwaili.garminalbarr.track.TrackEntity
import com.alruwaili.garminalbarr.track.ui.SavedTracksTabContent
import com.alruwaili.garminalbarr.network.SignalPointEntity
import com.alruwaili.garminalbarr.network.ui.SignalPointsTabContent
import java.util.Locale

// Desert Theme Palette
private val DesertDark = Color(0xFF1A1209)
private val DesertBg = Color(0xFF1F1A16)
private val DesertCard = Color(0xFF2C2013)
private val DesertCardBorder = Color(0xFF3D2B1F)
private val DesertBrown = Color(0xFF3D2B1F)
private val DesertGold = Color(0xFFD4A017)
private val DesertTan = Color(0xFFF5E6C8)
private val DesertMuted = Color(0xFF9E9E9E)
private val StatusRed = Color(0xFFE53935)

@Composable
fun MyLocationsScreen(
    locations: List<LocationEntity>,
    currentLocation: LocationData?,
    isRestoring: Boolean,
    onBack: () -> Unit,
    onLocationClick: (LocationEntity) -> Unit,
    onFavoriteToggle: (LocationEntity) -> Unit,
    onShareLocation: (LocationEntity) -> Unit,
    onDeleteLocalOnly: (LocationEntity) -> Unit,
    onDeleteBoth: (LocationEntity) -> Unit,
    onAddLocation: (title: String, lat: Double, lon: Double) -> Unit,
    onRestoreFromCloud: () -> Unit,
    tracks: List<TrackEntity> = emptyList(),
    initialTab: Int = 0,
    onTrackClick: (TrackEntity) -> Unit = {},
    onToggleTrackVisibility: (TrackEntity) -> Unit = {},
    onBacktrack: (TrackEntity) -> Unit = {},
    onExportGpx: (TrackEntity) -> Unit = {},
    onDeleteTrack: (TrackEntity) -> Unit = {},
    signalPoints: List<SignalPointEntity> = emptyList(),
    onSignalPointGoTo: (SignalPointEntity) -> Unit = {},
    onSignalPointRename: (SignalPointEntity, String?) -> Unit = { _, _ -> },
    onSignalPointDelete: (SignalPointEntity) -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(initialTab) }
    var isSpeedDialOpen by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }
    var locationPendingDelete by remember { mutableStateOf<LocationEntity?>(null) }

    val fabRotation by animateFloatAsState(
        targetValue = if (isSpeedDialOpen) 45f else 0f,
        label = "fab_rotation"
    )

    BackHandler(enabled = isSpeedDialOpen) {
        isSpeedDialOpen = false
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DesertBg)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Header Bar
                Surface(
                    color = DesertDark,
                    shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 36.dp, bottom = 12.dp, start = 8.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "رجوع",
                                tint = DesertGold
                            )
                        }

                        Text(
                            text = "مواقعي",
                            color = DesertGold,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )

                        // Count Badge
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DesertCard,
                            border = BorderStroke(1.dp, DesertCardBorder)
                        ) {
                            Text(
                                text = "${locations.size} موقع",
                                color = DesertTan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                // Segmented Tab Selector [ المواقع المحفوظة | المسارات المسجلة ]
                Surface(
                    color = DesertDark,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(DesertCard)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (selectedTab == 0) DesertGold else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedTab = 0 }
                        ) {
                            Text(
                                text = "المواقع (${locations.size})",
                                color = if (selectedTab == 0) Color.Black else DesertTan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 7.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (selectedTab == 1) DesertGold else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedTab = 1 }
                        ) {
                            Text(
                                text = "المسارات المسجلة (${tracks.size})",
                                color = if (selectedTab == 1) Color.Black else DesertTan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 7.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (selectedTab == 2) DesertGold else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedTab = 2 }
                        ) {
                            Text(
                                text = "نقاط الشبكة (${signalPoints.size})",
                                color = if (selectedTab == 2) Color.Black else DesertTan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 7.dp)
                            )
                        }
                    }
                }

                if (selectedTab == 0) {
                    // Main Content List or Empty State
                    if (locations.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
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
                                        imageVector = Icons.Default.Place,
                                        contentDescription = null,
                                        tint = DesertGold.copy(alpha = 0.6f),
                                        modifier = Modifier.size(38.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "لا توجد مواقع محفوظة بعد",
                                    color = DesertTan,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = "أضف مواقعك الخاصة عبر زر (+) أو استعدها من السحابة",
                                    color = DesertMuted,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(locations, key = { "${it.id}_${it.title}_${it.latitude}" }) { item ->
                                val distanceMeters = if (currentLocation != null && item.latitude != null && item.longitude != null) {
                                    NavigationMath.distanceMeters(
                                        currentLocation.latitude,
                                        currentLocation.longitude,
                                        item.latitude,
                                        item.longitude
                                    )
                                } else null

                                val distanceText = if (distanceMeters != null) {
                                    NavigationMath.formatRemaining(distanceMeters)
                                } else {
                                    "جاري تحديد المسافة…"
                                }

                                LocationCard(
                                    location = item,
                                    distanceText = distanceText,
                                    onCardClick = { onLocationClick(item) },
                                    onFavoriteToggle = { onFavoriteToggle(item) },
                                    onShare = { onShareLocation(item) },
                                    onDelete = { locationPendingDelete = item }
                                )
                            }
                            // Bottom spacing for FAB
                            item {
                                Spacer(modifier = Modifier.height(80.dp))
                            }
                        }
                    }
                } else if (selectedTab == 1) {
                    SavedTracksTabContent(
                        tracks = tracks,
                        onTrackClick = onTrackClick,
                        onToggleVisibility = onToggleTrackVisibility,
                        onBacktrack = onBacktrack,
                        onExportGpx = onExportGpx,
                        onDeleteTrack = onDeleteTrack,
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    )
                } else {
                    SignalPointsTabContent(
                        points = signalPoints,
                        currentLocation = currentLocation,
                        onGoTo = onSignalPointGoTo,
                        onRename = onSignalPointRename,
                        onDelete = onSignalPointDelete,
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    )
                }
            }

            // Speed Dial FABs (Visible only on Locations tab)
            if (selectedTab == 0) {
                // Scrim overlay when Speed Dial is open
                if (isSpeedDialOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                isSpeedDialOpen = false
                            }
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AnimatedVisibility(
                        visible = isSpeedDialOpen,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Restore from cloud option
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = DesertDark,
                                    border = BorderStroke(1.dp, DesertCardBorder),
                                    shadowElevation = 4.dp
                                ) {
                                    Text(
                                        text = "استعادة من السحابة",
                                        color = DesertTan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                                SmallFloatingActionButton(
                                    onClick = {
                                        isSpeedDialOpen = false
                                        showRestoreConfirmDialog = true
                                    },
                                    containerColor = DesertCard,
                                    contentColor = DesertGold,
                                    shape = CircleShape,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_cloud_download),
                                        contentDescription = "استعادة من السحابة",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // Add location option
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = DesertDark,
                                    border = BorderStroke(1.dp, DesertCardBorder),
                                    shadowElevation = 4.dp
                                ) {
                                    Text(
                                        text = "إضافة موقع",
                                        color = DesertTan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                                SmallFloatingActionButton(
                                    onClick = {
                                        isSpeedDialOpen = false
                                        showAddDialog = true
                                    },
                                    containerColor = DesertGold,
                                    contentColor = Color.White,
                                    shape = CircleShape,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Place,
                                        contentDescription = "إضافة موقع",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Main FAB
                    FloatingActionButton(
                        onClick = { isSpeedDialOpen = !isSpeedDialOpen },
                        containerColor = DesertGold,
                        contentColor = Color.White,
                        shape = CircleShape,
                        elevation = FloatingActionButtonDefaults.elevation(6.dp),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "قائمة الإجراءات",
                            modifier = Modifier
                                .size(28.dp)
                                .rotate(fabRotation)
                        )
                    }
                }
            }

            // Add Location Dialog
            if (showAddDialog) {
                AddLocationDialog(
                    currentLocation = currentLocation,
                    onDismiss = { showAddDialog = false },
                    onSave = { title, lat, lon ->
                        onAddLocation(title, lat, lon)
                        showAddDialog = false
                    }
                )
            }

            // Restore Confirmation Dialog
            if (showRestoreConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showRestoreConfirmDialog = false },
                    containerColor = DesertCard,
                    shape = RoundedCornerShape(20.dp),
                    title = {
                        Text(
                            text = "استعادة المواقع من السحابة",
                            color = DesertGold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    },
                    text = {
                        Text(
                            text = "سيتم استعادة المواقع المحفوظة في السحابة ودمجها مع مواقعك الحالية على هذا الجهاز. هل تريد المتابعة؟",
                            color = DesertTan,
                            fontSize = 14.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showRestoreConfirmDialog = false
                                onRestoreFromCloud()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DesertGold)
                        ) {
                            Text(text = "استعادة", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRestoreConfirmDialog = false }) {
                            Text(text = "إلغاء", color = DesertMuted)
                        }
                    }
                )
            }

            // Delete Options Dialog
            locationPendingDelete?.let { item ->
                AlertDialog(
                    onDismissRequest = { locationPendingDelete = null },
                    containerColor = DesertCard,
                    shape = RoundedCornerShape(20.dp),
                    title = {
                        Text(
                            text = "حذف الموقع",
                            color = StatusRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    },
                    text = {
                        Text(
                            text = "اختر طريقة حذف الموقع \"${item.title ?: "موقع محدد"}\":",
                            color = DesertTan,
                            fontSize = 14.sp
                        )
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val target = locationPendingDelete
                                    locationPendingDelete = null
                                    if (target != null) onDeleteBoth(target)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = StatusRed)
                            ) {
                                Text(text = "حذف من الجهاز والسحابة", color = Color.White, fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    val target = locationPendingDelete
                                    locationPendingDelete = null
                                    if (target != null) onDeleteLocalOnly(target)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = DesertBrown)
                            ) {
                                Text(text = "من الجهاز فقط", color = DesertTan, fontSize = 12.sp)
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { locationPendingDelete = null }) {
                            Text(text = "إلغاء", color = DesertMuted)
                        }
                    }
                )
            }

            // Restore in-progress Loading Overlay
            if (isRestoring) {
                Dialog(onDismissRequest = {}) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = DesertCard,
                        border = BorderStroke(1.dp, DesertGold),
                        shadowElevation = 10.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                color = DesertGold,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = "جاري استعادة المواقع من السحابة…",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationCard(
    location: LocationEntity,
    distanceText: String,
    onCardClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = DesertCard,
        border = BorderStroke(1.dp, DesertCardBorder),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pin Icon Container
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(DesertGold.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Place,
                    contentDescription = null,
                    tint = DesertGold,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Information Column
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = location.title ?: "بدون عنوان",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "المسافة: $distanceText",
                    color = DesertTan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(2.dp))

                val coords = String.format(
                    Locale.US,
                    "%.5f° N, %.5f° E",
                    location.latitude ?: 0.0,
                    location.longitude ?: 0.0
                )
                Text(
                    text = coords,
                    color = DesertMuted,
                    fontSize = 11.sp
                )
            }

            // Actions: Favorite + Overflow Menu
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onFavoriteToggle) {
                    Icon(
                        imageVector = if (location.isFav == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "المفضلة",
                        tint = if (location.isFav == true) StatusRed else DesertMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "خيارات إضافية",
                            tint = DesertMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(DesertCard)
                    ) {
                        DropdownMenuItem(
                            text = { Text("الذهاب إلى الخريطة", color = Color.White) },
                            leadingIcon = {
                                Icon(Icons.Default.Place, contentDescription = null, tint = DesertGold)
                            },
                            onClick = {
                                menuExpanded = false
                                onCardClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("مشاركة", color = Color.White) },
                            leadingIcon = {
                                Icon(Icons.Default.Share, contentDescription = null, tint = DesertTan)
                            },
                            onClick = {
                                menuExpanded = false
                                onShare()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("حذف الموقع", color = StatusRed) },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = StatusRed)
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddLocationDialog(
    currentLocation: LocationData?,
    onDismiss: () -> Unit,
    onSave: (title: String, lat: Double, lon: Double) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var latText by remember { mutableStateOf("") }
    var lonText by remember { mutableStateOf("") }
    var isUseCurrentLocation by remember { mutableStateOf(false) }
    var titleError by remember { mutableStateOf(false) }
    var coordsError by remember { mutableStateOf(false) }

    // GPS Auto-fill Handling with Fallback:
    // When checkbox is ticked, if currentLocation is available, populate & lock immediately.
    // If still null/acquiring, notify via Toast and do NOT lock fields until a valid fix arrives.
    LaunchedEffect(isUseCurrentLocation, currentLocation) {
        if (isUseCurrentLocation) {
            if (currentLocation != null && MapNavigationHelper.isValidLatLon(currentLocation.latitude, currentLocation.longitude)) {
                latText = String.format(Locale.US, "%.6f", currentLocation.latitude)
                lonText = String.format(Locale.US, "%.6f", currentLocation.longitude)
                coordsError = false
            }
        }
    }

    val isGpsLocked = isUseCurrentLocation && currentLocation != null &&
            MapNavigationHelper.isValidLatLon(currentLocation.latitude, currentLocation.longitude)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DesertCard,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Place,
                    contentDescription = null,
                    tint = DesertGold,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "إضافة موقع جديد",
                    color = DesertGold,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        if (it.isNotBlank()) titleError = false
                    },
                    label = { Text("اسم الموقع") },
                    singleLine = true,
                    isError = titleError,
                    supportingText = if (titleError) {
                        { Text("يرجى إدخال اسم الموقع", color = StatusRed) }
                    } else null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DesertGold,
                        unfocusedBorderColor = DesertCardBorder,
                        focusedLabelColor = DesertGold,
                        unfocusedLabelColor = DesertMuted,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = DesertGold
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // "Use Current Location" Checkbox
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val nextChecked = !isUseCurrentLocation
                            isUseCurrentLocation = nextChecked
                            if (nextChecked) {
                                if (currentLocation == null || !MapNavigationHelper.isValidLatLon(currentLocation.latitude, currentLocation.longitude)) {
                                    Toast.makeText(
                                        context,
                                        "جاري التقاط إحداثيات موقعك…",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } else {
                                latText = ""
                                lonText = ""
                            }
                        }
                ) {
                    Checkbox(
                        checked = isUseCurrentLocation,
                        onCheckedChange = { checked ->
                            isUseCurrentLocation = checked
                            if (checked) {
                                if (currentLocation == null || !MapNavigationHelper.isValidLatLon(currentLocation.latitude, currentLocation.longitude)) {
                                    Toast.makeText(
                                        context,
                                        "جاري التقاط إحداثيات موقعك…",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } else {
                                latText = ""
                                lonText = ""
                            }
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = DesertGold,
                            uncheckedColor = DesertMuted,
                            checkmarkColor = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "استخدام الموقع الحالي",
                        color = if (isGpsLocked) DesertGold else DesertTan,
                        fontSize = 13.sp,
                        fontWeight = if (isGpsLocked) FontWeight.Bold else FontWeight.Normal
                    )
                }

                // Latitude Field
                OutlinedTextField(
                    value = latText,
                    onValueChange = {
                        latText = it
                        coordsError = false
                    },
                    label = { Text("خط العرض (Latitude)") },
                    singleLine = true,
                    enabled = !isGpsLocked,
                    isError = coordsError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DesertGold,
                        unfocusedBorderColor = DesertCardBorder,
                        disabledBorderColor = DesertCardBorder.copy(alpha = 0.5f),
                        disabledTextColor = DesertTan,
                        focusedLabelColor = DesertGold,
                        unfocusedLabelColor = DesertMuted,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = DesertGold
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Longitude Field
                OutlinedTextField(
                    value = lonText,
                    onValueChange = {
                        lonText = it
                        coordsError = false
                    },
                    label = { Text("خط الطول (Longitude)") },
                    singleLine = true,
                    enabled = !isGpsLocked,
                    isError = coordsError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = if (coordsError) {
                        { Text("يرجى إدخال إحداثيات صالحة", color = StatusRed) }
                    } else null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DesertGold,
                        unfocusedBorderColor = DesertCardBorder,
                        disabledBorderColor = DesertCardBorder.copy(alpha = 0.5f),
                        disabledTextColor = DesertTan,
                        focusedLabelColor = DesertGold,
                        unfocusedLabelColor = DesertMuted,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = DesertGold
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanTitle = title.trim()
                    if (cleanTitle.isEmpty()) {
                        titleError = true
                        return@Button
                    }

                    val lat = latText.trim().toDoubleOrNull()
                    val lon = lonText.trim().toDoubleOrNull()

                    if (!MapNavigationHelper.isValidLatLon(lat, lon)) {
                        coordsError = true
                        return@Button
                    }

                    onSave(cleanTitle, lat!!, lon!!)
                },
                colors = ButtonDefaults.buttonColors(containerColor = DesertGold)
            ) {
                Text(text = "حفظ", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "إلغاء", color = DesertMuted)
            }
        }
    )
}
