package com.alruwaili.garminalbarr.map.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DesertCard = Color(0xFF2C2013)
private val DesertGold = Color(0xFFDDA01E)
private val DesertTan = Color(0xFFF5E6C8)

@Composable
fun DiscardTrackDialog(
    onConfirmDiscard: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, Color(0xFF3D2B1F), RoundedCornerShape(16.dp)),
        title = {
            Text(
                text = "إلغاء تسجيل المسار",
                color = DesertGold,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        },
        text = {
            Text(
                text = "هل أنت متأكد من إلغاء وحذف أثر الرحلة الحالي؟ لن يتم حفظ أي نقاط مسجلة.",
                color = DesertTan,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmDiscard,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "نعم، إلغاء وحذف",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "تراجع ومتابعة", color = DesertTan)
            }
        },
        containerColor = DesertCard,
        shape = RoundedCornerShape(16.dp)
    )
}
