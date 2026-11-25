package com.alruwaili.garminalbarr.desertmind.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alruwaili.garminalbarr.LocationLightweight
import com.alruwaili.garminalbarr.desertmind.DesertMindEngine
import com.alruwaili.garminalbarr.map.PoiDetails
import com.alruwaili.garminalbarr.map.routing.BRouterOfflineEngine
import kotlinx.coroutines.launch

private val DesertCard = Color(0xFF2C2013)
private val DesertCardBorder = Color(0xFF3D2B1F)
private val DesertGold = Color(0xFFD4A017)
private val DesertTan = Color(0xFFF5E6C8)
private val DesertMuted = Color(0xFF9E9E9E)
private val DesertDark = Color(0xFF1F1A16)

/**
 * "العقل الصحراوي الذكي" — واجهة الأسئلة الثابتة. كل الأزرار تُحسب محليًا وبشكل فوري
 * تقريبًا (باستثناء زر "هل أصل بسيارتي" الذي يستدعي محرك المسارات المحلي)، دون أي
 * اتصال إنترنت أو ذكاء اصطناعي حقيقي — انظر توثيق [DesertMindEngine].
 */
@Composable
fun DesertMindDialog(
    currentLat: Double?,
    currentLon: Double?,
    allLocations: List<LocationLightweight>,
    brouterEngine: BRouterOfflineEngine?,
    selectedPoi: PoiDetails?,
    azimuthDeg: Float,
    tripStartLat: Double?,
    tripStartLon: Double?,
    onGoTo: (lat: Double, lon: Double, title: String) -> Unit,
    onDismiss: () -> Unit
) {
    var answerText by remember { mutableStateOf<String?>(null) }
    var answerTarget by remember { mutableStateOf<Triple<Double, Double, String>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun show(answer: DesertMindEngine.DesertMindAnswer) {
        answerText = answer.text
        answerTarget = if (answer.targetLat != null && answer.targetLon != null) {
            Triple(answer.targetLat, answer.targetLon, answer.targetTitle ?: "الهدف")
        } else null
    }

    fun requireLocation(block: (Double, Double) -> DesertMindEngine.DesertMindAnswer) {
        if (currentLat == null || currentLon == null) {
            answerText = "يتعذّر تحديد موقعك الحالي الآن (GPS غير متاح بعد)، يرجى المحاولة بعد قليل."
            answerTarget = null
            return
        }
        show(block(currentLat, currentLon))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DesertDark,
        title = {
            Text("العقل الصحراوي الذكي", color = DesertGold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp)) {
                Text(
                    text = "أسئلة ثابتة تُحسب بالكامل دون إنترنت من موقعك الحالي وقاعدة بيانات المواقع المحفوظة.",
                    color = DesertMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    item { SectionLabel("عامة") }
                    items(generalQuestions) { q ->
                        QuestionButton(q.label) {
                            when (q) {
                                Question.WHERE_AM_I -> requireLocation { lat, lon -> DesertMindEngine.whereAmI(lat, lon, allLocations) }
                                Question.WHERE_HEADING -> show(DesertMindEngine.whereAmIHeading(azimuthDeg))
                                Question.NEAREST_WATER -> requireLocation { lat, lon -> DesertMindEngine.nearestWaterSource(lat, lon, allLocations) }
                                Question.NEAREST_STATION -> requireLocation { lat, lon -> DesertMindEngine.nearestStation(lat, lon, allLocations) }
                                Question.NEAREST_SETTLEMENT -> requireLocation { lat, lon -> DesertMindEngine.nearestSettlement(lat, lon, allLocations) }
                                Question.NEAREST_CAIRN -> requireLocation { lat, lon -> DesertMindEngine.nearestCairn(lat, lon, allLocations) }
                                Question.NEAREST_RADMIYA -> requireLocation { lat, lon -> DesertMindEngine.nearestRadmiya(lat, lon, allLocations) }
                                Question.NEAREST_TOWER -> requireLocation { lat, lon -> DesertMindEngine.nearestTower(lat, lon, allLocations) }
                                Question.NEAREST_ROAD -> requireLocation { lat, lon -> DesertMindEngine.nearestDrivableRoad(lat, lon, brouterEngine) }
                                Question.BEST_RETURN -> requireLocation { lat, lon -> DesertMindEngine.bestReturnDirection(lat, lon, tripStartLat, tripStartLon) }
                                else -> Unit
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(4.dp)) }
                    item { SectionLabel(if (selectedPoi != null) "عن الموقع المحدد: ${selectedPoi.name}" else "عن الموقع المحدد (اختر موقعًا على الخريطة أولًا)") }
                    items(contextQuestions) { q ->
                        QuestionButton(q.label, enabled = selectedPoi != null) {
                            val dest = selectedPoi ?: return@QuestionButton
                            when (q) {
                                Question.GUIDE_ME -> show(DesertMindEngine.guideMeAnswer(dest))
                                Question.CAN_REACH_BY_CAR -> {
                                    if (currentLat == null || currentLon == null) {
                                        answerText = "يتعذّر تحديد موقعك الحالي الآن (GPS غير متاح بعد)، يرجى المحاولة بعد قليل."
                                        answerTarget = null
                                    } else {
                                        isLoading = true
                                        scope.launch {
                                            val result = DesertMindEngine.canReachByCar(currentLat, currentLon, dest, brouterEngine)
                                            isLoading = false
                                            show(result)
                                        }
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }

                if (isLoading) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = DesertGold, modifier = Modifier.height(16.dp).padding(end = 8.dp))
                        Text("جارٍ الحساب...", color = DesertMuted, fontSize = 12.sp)
                    }
                }

                answerText?.let { txt ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = DesertCard,
                        border = BorderStroke(1.dp, DesertCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(txt, color = DesertTan, fontSize = 13.sp, lineHeight = 19.sp)
                            val target = answerTarget
                            if (target != null) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = { onGoTo(target.first, target.second, target.third) },
                                    colors = ButtonDefaults.buttonColors(containerColor = DesertGold, contentColor = Color(0xFF1F1A16)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("اذهب إلى \"${target.third}\"", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق", color = DesertMuted) }
        }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, color = DesertGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun QuestionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) DesertCard else DesertCard.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, DesertCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (enabled) DesertTan else DesertMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

private enum class Question(val label: String) {
    WHERE_AM_I("أين أنا بالضبط؟"),
    WHERE_HEADING("أين أتجه؟"),
    NEAREST_WATER("أقرب مصدر مياه (بئر/فيض/مي/غدير)"),
    NEAREST_STATION("أقرب محطة"),
    NEAREST_SETTLEMENT("أقرب تجمع سكاني (قرية/بلدة)"),
    NEAREST_CAIRN("أقرب رجم"),
    NEAREST_RADMIYA("أقرب ردمية"),
    NEAREST_TOWER("أقرب برج اتصال"),
    NEAREST_ROAD("أقرب طريق معبّد"),
    BEST_RETURN("أفضل اتجاه للعودة"),
    GUIDE_ME("أرشدني إلى هذا الموقع"),
    CAN_REACH_BY_CAR("هل أصل بسيارتي إلى هذا الموقع؟")
}

private val generalQuestions = listOf(
    Question.WHERE_AM_I,
    Question.WHERE_HEADING,
    Question.NEAREST_WATER,
    Question.NEAREST_STATION,
    Question.NEAREST_SETTLEMENT,
    Question.NEAREST_CAIRN,
    Question.NEAREST_RADMIYA,
    Question.NEAREST_TOWER,
    Question.NEAREST_ROAD,
    Question.BEST_RETURN
)

private val contextQuestions = listOf(
    Question.GUIDE_ME,
    Question.CAN_REACH_BY_CAR
)
