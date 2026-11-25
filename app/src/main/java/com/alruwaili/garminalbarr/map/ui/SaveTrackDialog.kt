package com.alruwaili.garminalbarr.map.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DesertCard = Color(0xFF2C2013)
private val DesertCardBorder = Color(0xFF3D2B1F)
private val DesertGold = Color(0xFFDDA01E)
private val DesertTan = Color(0xFFF5E6C8)

val TRACK_COLORS = listOf(
    "#E53935", // Red
    "#FFB300", // Gold
    "#00E5FF", // Cyan
    "#00E676", // Green
    "#FF6D00", // Orange
    "#D500F9"  // Purple
)

@Composable
fun SaveTrackDialog(
    initialDistanceMeters: Double,
    initialDurationSeconds: Long,
    onSave: (title: String, description: String?, colorHex: String) -> Unit,
    onResumeRecording: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit
) {
    val defaultTitle = remember {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
        "أثر " + sdf.format(Date())
    }

    var title by remember { mutableStateOf(defaultTitle) }
    var description by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(TRACK_COLORS[0]) }

    val formattedDist = if (initialDistanceMeters >= 1000.0) {
        String.format(Locale.US, "%.1f كم", initialDistanceMeters / 1000.0)
    } else {
        String.format(Locale.US, "%.0f م", initialDistanceMeters)
    }

    val h = initialDurationSeconds / 3600
    val m = (initialDurationSeconds % 3600) / 60
    val s = initialDurationSeconds % 60
    val formattedTime = if (h > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = DesertCard,
            border = BorderStroke(1.5.dp, DesertGold),
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "حفظ أثر الرحلة",
                    color = DesertGold,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "$formattedDist | $formattedTime",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Title Input
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("اسم المسار", color = DesertTan) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = DesertGold,
                        unfocusedBorderColor = DesertCardBorder,
                        focusedLabelColor = DesertGold,
                        unfocusedLabelColor = DesertTan
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Description Input
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("الوصف / ملاحظات (اختياري)", color = DesertTan) },
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = DesertGold,
                        unfocusedBorderColor = DesertCardBorder,
                        focusedLabelColor = DesertGold,
                        unfocusedLabelColor = DesertTan
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Color Selector Row
                Text(
                    text = "لون المسار على الخريطة",
                    color = DesertTan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (colorHex in TRACK_COLORS) {
                        val color = Color(android.graphics.Color.parseColor(colorHex))
                        val isSelected = selectedColor == colorHex

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) Color.White else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = colorHex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                Button(
                    onClick = {
                        val finalTitle = title.trim().ifEmpty { defaultTitle }
                        val finalDesc = description.trim().ifEmpty { null }
                        onSave(finalTitle, finalDesc, selectedColor)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DesertGold),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "حفظ المسار",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onResumeRecording) {
                        Text(text = "متابعة التسجيل", color = DesertTan, fontSize = 13.sp)
                    }

                    TextButton(onClick = onDiscard) {
                        Text(text = "إلغاء وحذف", color = Color(0xFFE57373), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
