package com.navigationgps.desert.map.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import com.navigationgps.desert.map.LocationShareHelper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigationgps.desert.R
import com.navigationgps.desert.map.NavigationMath
import com.navigationgps.desert.map.PoiDetails
import java.util.Locale

data class RouteSlot(
    val title: String,
    val latitude: Double,
    val longitude: Double,
    val isCurrentLocation: Boolean = false
)

enum class SlotType {
    ORIGIN, DESTINATION
}

private val DesertDark = Color(0xFF1F1A16)
private val DesertCard = Color(0xFF2E2620)
private val DesertCardBorder = Color(0xFF42372E)
private val DesertGold = Color(0xFFDDA01E)
private val DesertTextPrimary = Color(0xFFFFFFFF)
private val DesertTextSecondary = Color(0xFFA89F91)
private val DesertTextMuted = Color(0xFF756A5E)
private val DesertHandle = Color(0xFF5C4F44)
private val DesertGreen = Color(0xFF4CAF50)
private val DesertRed = Color(0xFFE53935)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoiBottomSheet(
    poi: PoiDetails,
    originSlot: RouteSlot,
    destSlot: RouteSlot,
    activePickerSlot: SlotType?,
    onActivePickerSlotChanged: (SlotType?) -> Unit,
    onOriginChanged: (RouteSlot) -> Unit,
    onDestinationChanged: (RouteSlot) -> Unit,
    onSwapSlots: () -> Unit,
    userLat: Double,
    userLon: Double,
    onDismiss: () -> Unit,
    onPickFromMap: (SlotType) -> Unit,
    onCompassNavigation: (origin: RouteSlot, destination: RouteSlot) -> Unit,
    onCarRouteNavigation: (origin: RouteSlot, destination: RouteSlot) -> Unit,
    azimuthState: State<Float>,
    onOpenTargetCompass: (RouteSlot) -> Unit,
    carRouteDistanceKm: Double? = null,
    onShareLocation: ((title: String, lat: Double, lon: Double) -> Unit)? = null,
    onSaveLocation: ((title: String, lat: Double, lon: Double) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveNameInput by remember(destSlot) {
        mutableStateOf(
            if (destSlot.title.isNotBlank() && destSlot.title != "موقع محدد") {
                destSlot.title
            } else {
                "${String.format(Locale.US, "%.5f", destSlot.latitude)}, ${String.format(Locale.US, "%.5f", destSlot.longitude)}"
            }
        )
    }

    val categoryName = when (poi.category) {
        "mountain" -> "جبل"
        "well" -> "بئر"
        "valley" -> "وادي"
        "dune" -> "كثبان رملية"
        "qalma" -> "قلمة"
        "ravine" -> "شعيب"
        "meadow" -> "روضة"
        "mosque" -> "مسجد"
        "station" -> "محطة"
        "house" -> "بيت"
        "village" -> "قرية"
        "plain" -> "قاع"
        "spring" -> "فيضان"
        "center" -> "مركز"
        "user_location" -> "موقع محدد"
        else -> "نقطة اهتمام"
    }

    val distanceKm = remember(originSlot, destSlot) {
        NavigationMath.distanceMeters(
            originSlot.latitude, originSlot.longitude,
            destSlot.latitude, destSlot.longitude
        ) / 1000.0
    }
    // Bearing: live from user GPS when origin is current location, static when custom origin
    val bearing = if (originSlot.isCurrentLocation) {
        remember(userLat, userLon, destSlot) {
            NavigationMath.bearingDeg(
                userLat, userLon,
                destSlot.latitude, destSlot.longitude
            ).toDouble()
        }
    } else {
        remember(originSlot, destSlot) {
            NavigationMath.bearingDeg(
                originSlot.latitude, originSlot.longitude,
                destSlot.latitude, destSlot.longitude
            ).toDouble()
        }
    }
    val bearingDirection = remember(bearing) { NavigationMath.arabicDirection(bearing.toFloat()) }

    val coordsString = "${String.format(Locale.US, "%.5f", destSlot.latitude)}, ${String.format(Locale.US, "%.5f", destSlot.longitude)}"

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = DesertCard,
            titleContentColor = DesertTextPrimary,
            textContentColor = DesertTextSecondary,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = "حفظ الموقع في مواقفي",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = DesertTextPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "اسم الموقع:",
                        fontSize = 13.sp,
                        color = DesertTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = saveNameInput,
                        onValueChange = { saveNameInput = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = DesertGold,
                            unfocusedBorderColor = DesertCardBorder,
                            focusedTextColor = DesertTextPrimary,
                            unfocusedTextColor = DesertTextPrimary,
                            cursorColor = DesertGold,
                            focusedContainerColor = DesertDark,
                            unfocusedContainerColor = DesertDark
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalName = saveNameInput.trim().ifEmpty { coordsString }
                        showSaveDialog = false
                        onSaveLocation?.invoke(finalName, destSlot.latitude, destSlot.longitude)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DesertGold,
                        contentColor = Color(0xFF1F1A16)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "حفظ",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F1A16)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text(
                        text = "إلغاء",
                        color = DesertTextSecondary
                    )
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DesertDark,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = DesertHandle)
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            if (activePickerSlot != null) {
                SheetPickerContent(
                    slotToPick = activePickerSlot,
                    originSlot = originSlot,
                    destSlot = destSlot,
                    userLat = userLat,
                    userLon = userLon,
                    onOriginChanged = onOriginChanged,
                    onDestinationChanged = onDestinationChanged,
                    onActivePickerSlotChanged = onActivePickerSlotChanged,
                    onPickFromMap = onPickFromMap
                )

                Spacer(modifier = Modifier.height(24.dp))
            } else {
            // Header Row: Category Badge + Mini Arrow + Save Action + Copy Action + Share Action
            DetailHeaderRow(
                categoryName = categoryName,
                destSlot = destSlot,
                bearing = bearing.toFloat(),
                azimuthState = azimuthState,
                onSaveLocation = {
                    saveNameInput = if (destSlot.title.isNotBlank() && destSlot.title != "موقع محدد") {
                        destSlot.title
                    } else {
                        coordsString
                    }
                    showSaveDialog = true
                },
                onCopyCoords = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Coordinates", coordsString)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "تم نسخ الإحداثيات", Toast.LENGTH_SHORT).show()
                },
                onShareCoords = {
                    if (onShareLocation != null) {
                        onShareLocation(destSlot.title, destSlot.latitude, destSlot.longitude)
                    } else {
                        LocationShareHelper.shareLocation(context, destSlot.title, destSlot.latitude, destSlot.longitude)
                    }
                },
                onOpenTargetCompass = onOpenTargetCompass
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Two-Slot Routing Container
            SlotRoutingCard(
                originSlot = originSlot,
                destSlot = destSlot,
                onActivePickerSlotChanged = onActivePickerSlotChanged,
                onSwapSlots = onSwapSlots
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Centered Bearing Info Box (Redundant distance card removed)
            BearingCard(
                bearing = bearing,
                bearingDirection = bearingDirection
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Primary Navigation CTA Button (Golden Mustard)
            Button(
                onClick = { onCompassNavigation(originSlot, destSlot) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = DesertGold,
                    contentColor = Color(0xFF1F1A16)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "مسار مستقيم",
                        tint = Color(0xFF1F1A16),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "مسار مستقيم - ${NavigationMath.formatRemaining(distanceKm * 1000.0)}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F1A16)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Secondary Button (Upcoming Car Route)
            CarRouteButton(
                originSlot = originSlot,
                destSlot = destSlot,
                carRouteDistanceKm = carRouteDistanceKm,
                onCarRouteNavigation = onCarRouteNavigation
            )

            Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SheetPickerContent(
    slotToPick: SlotType,
    originSlot: RouteSlot,
    destSlot: RouteSlot,
    userLat: Double,
    userLon: Double,
    onOriginChanged: (RouteSlot) -> Unit,
    onDestinationChanged: (RouteSlot) -> Unit,
    onActivePickerSlotChanged: (SlotType?) -> Unit,
    onPickFromMap: (SlotType) -> Unit
) {
    val isOppositeCurrentLocation =
        if (slotToPick == SlotType.ORIGIN) destSlot.isCurrentLocation else originSlot.isCurrentLocation

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (slotToPick == SlotType.ORIGIN) "اختر نقطة الانطلاق (من هنا)" else "اختر نقطة الوصول (إلى هنا)",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = DesertTextPrimary
        )
        TextButton(onClick = { onActivePickerSlotChanged(null) }) {
            Text(text = "إلغاء", color = DesertGold)
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    // Option 1: My Current Location
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isOppositeCurrentLocation) DesertCard.copy(alpha = 0.5f) else DesertCard,
        border = BorderStroke(1.dp, DesertCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isOppositeCurrentLocation) {
                val currentLocSlot = RouteSlot(
                    title = "موقعي الحالي",
                    latitude = userLat,
                    longitude = userLon,
                    isCurrentLocation = true
                )
                if (slotToPick == SlotType.ORIGIN) {
                    onOriginChanged(currentLocSlot)
                } else {
                    onDestinationChanged(currentLocSlot)
                }
                onActivePickerSlotChanged(null)
            }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isOppositeCurrentLocation) DesertTextMuted else DesertGreen)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "موقعي الحالي",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isOppositeCurrentLocation) DesertTextMuted else DesertTextPrimary
                )
                if (isOppositeCurrentLocation) {
                    Text(
                        text = "محدد بالفعل في النقطة المقابلة",
                        fontSize = 11.sp,
                        color = DesertTextMuted
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // Option 2: Pick from Map
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = DesertCard,
        border = BorderStroke(1.5.dp, DesertGold),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val currentSlot = slotToPick
                onActivePickerSlotChanged(null)
                onPickFromMap(currentSlot)
            }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Place,
                contentDescription = "تحديد من الخريطة",
                tint = DesertGold,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "تحديد من الخريطة...",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = DesertGold
            )
        }
    }
}

@Composable
private fun DetailHeaderRow(
    categoryName: String,
    destSlot: RouteSlot,
    bearing: Float,
    azimuthState: State<Float>,
    onSaveLocation: () -> Unit,
    onCopyCoords: () -> Unit,
    onShareCoords: () -> Unit,
    onOpenTargetCompass: (RouteSlot) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(DesertGold.copy(alpha = 0.15f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = categoryName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = DesertGold
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Live mini target arrow: hidden for the (0,0) placeholder.
            if (destSlot.latitude != 0.0 || destSlot.longitude != 0.0) {
                val azimuth by azimuthState
                val headingRelative = bearing - azimuth
                MiniTargetArrow(
                    targetBearing = headingRelative,
                    onClick = { onOpenTargetCompass(destSlot) }
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DesertCard,
                border = BorderStroke(1.dp, DesertCardBorder),
                modifier = Modifier.clickable(
                    enabled = destSlot.latitude != 0.0 || destSlot.longitude != 0.0,
                    onClick = onSaveLocation
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "حفظ",
                        fontSize = 12.sp,
                        color = DesertTextSecondary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(id = R.drawable.ic_bookmark),
                        contentDescription = "حفظ الموقع",
                        tint = DesertGold,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DesertCard,
                border = BorderStroke(1.dp, DesertCardBorder),
                modifier = Modifier.clickable(
                    enabled = destSlot.latitude != 0.0 || destSlot.longitude != 0.0,
                    onClick = onCopyCoords
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "نسخ",
                        fontSize = 12.sp,
                        color = DesertTextSecondary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = "نسخ الإحداثيات",
                        tint = DesertGold,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DesertCard,
                border = BorderStroke(1.dp, DesertCardBorder),
                modifier = Modifier.clickable(
                    enabled = destSlot.latitude != 0.0 || destSlot.longitude != 0.0,
                    onClick = onShareCoords
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "مشاركة",
                        fontSize = 12.sp,
                        color = DesertTextSecondary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "مشاركة الموقع",
                        tint = DesertGold,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotRoutingCard(
    originSlot: RouteSlot,
    destSlot: RouteSlot,
    onActivePickerSlotChanged: (SlotType?) -> Unit,
    onSwapSlots: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DesertCard,
        border = BorderStroke(1.dp, DesertCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            SlotRow(
                dotColor = DesertGreen,
                label = "من هنا (نقطة الانطلاق)",
                title = originSlot.title,
                onClick = { onActivePickerSlotChanged(SlotType.ORIGIN) }
            )

            // Divider with Swap Action
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(
                    color = DesertCardBorder,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(DesertDark)
                        .clickable(
                            role = androidx.compose.ui.semantics.Role.Button,
                            onClickLabel = "تبديل النقطتين",
                            onClick = onSwapSlots
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = DesertGold,
                        modifier = Modifier.size(16.dp)
                    )
                }
                HorizontalDivider(
                    color = DesertCardBorder,
                    modifier = Modifier.weight(1f)
                )
            }

            SlotRow(
                dotColor = DesertRed,
                label = "إلى هنا (نقطة الوصول)",
                title = destSlot.title,
                onClick = { onActivePickerSlotChanged(SlotType.DESTINATION) }
            )
        }
    }
}

@Composable
private fun SlotRow(
    dotColor: Color,
    label: String,
    title: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = DesertTextSecondary
                )
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = DesertTextPrimary,
                    maxLines = 1
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(DesertDark)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "تغيير",
                    fontSize = 11.sp,
                    color = DesertGold
                )
            }
        }
    }
}

@Composable
private fun BearingCard(
    bearing: Double,
    bearingDirection: String
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = DesertCard,
        border = BorderStroke(1.dp, DesertCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "الاتجاه",
                fontSize = 12.sp,
                color = DesertTextSecondary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$bearingDirection (${bearing.toInt()}°)",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = DesertTextPrimary
            )
        }
    }
}

@Composable
private fun CarRouteButton(
    originSlot: RouteSlot,
    destSlot: RouteSlot,
    carRouteDistanceKm: Double?,
    onCarRouteNavigation: (origin: RouteSlot, destination: RouteSlot) -> Unit
) {
    val distanceText = if (carRouteDistanceKm != null) {
        "مسار سيارة - ${NavigationMath.formatRemaining(carRouteDistanceKm * 1000.0)}"
    } else {
        "مسار سيارة"
    }
    Button(
        onClick = {
            onCarRouteNavigation(originSlot, destSlot)
        },
        enabled = destSlot.latitude != 0.0 || destSlot.longitude != 0.0,
        border = BorderStroke(1.5.dp, Color(0xFFFF6D00)),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF2C2013),
            contentColor = Color(0xFFFF6D00),
            disabledContainerColor = Color(0xFF2C2013).copy(alpha = 0.5f),
            disabledContentColor = Color(0xFFFF6D00).copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = distanceText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF6D00)
            )
        }
    }
}

/**
 * Mini target arrow: points towards the destination bearing.
 * Rotation is bound directly to [targetBearing].
 */
@Composable
private fun MiniTargetArrow(
    targetBearing: Float,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(DesertGold.copy(alpha = 0.15f))
            .clickable(onClickLabel = "فتح البوصلة", onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        TargetArrowMark(
            relative = targetBearing,
            modifier = Modifier.size(32.dp),
            tint = DesertGold,
            showShadow = false
        )
    }
}

