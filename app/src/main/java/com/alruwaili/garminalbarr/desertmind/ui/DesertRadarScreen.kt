package com.alruwaili.garminalbarr.desertmind.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import com.alruwaili.garminalbarr.desertmind.DesertRadarEngine
import com.alruwaili.garminalbarr.map.NavigationMath
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

private val DesertDark = Color(0xFF1F1A16)
private val DesertCard = Color(0xFF2C2013)
private val DesertCardBorder = Color(0xFF3D2B1F)
private val DesertGold = Color(0xFFD4A017)
private val DesertTan = Color(0xFFF5E6C8)
private val DesertMuted = Color(0xFF9E9E9E)
private val RadarGreen = Color(0xFF39FF6A)

/**
 * "الرادار الصحراوي" — خطوة 1: اختيار نطاق البحث (5–50 كم)، يدويًا (رقم) أو بتمرير (Slider).
 */
@Composable
fun DesertRadarRadiusDialog(
    onStart: (radiusKm: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var radiusKm by remember { mutableFloatStateOf(DesertRadarEngine.DEFAULT_RADIUS_KM.toFloat()) }
    var textValue by remember { mutableStateOf(DesertRadarEngine.DEFAULT_RADIUS_KM.toString()) }

    // إصلاح 2026-11-26: الدالة القديمة كانت تُطبّق coerceIn فورًا على كل ضغطة حرف وتكتب
    // النتيجة المحصورة مباشرة إلى نفس حالة النص المعروض (textValue) — فإذا كتب المستخدم رقمًا
    // جزئيًا أصغر من الحد الأدنى أثناء الكتابة (مثل "1" في طريقه لكتابة "15")، كان يُحصر فورًا
    // إلى 5 ويُعاد ضبط الحقل بالكامل، فيبدو الأمر وكأن الكتابة لا تعمل إطلاقًا. الإصلاح: فصل
    // حالة النص المعروض عن القيمة المحصورة فعليًا؛ أثناء الكتابة تُحدَّث radiusKm (لتحريك
    // الشريط تزامنًا) لكن بلا حصر صارم للرقم الظاهر، والحصر الفعلي (coerceIn) يُطبَّق فقط
    // عند مغادرة الحقل أو الضغط على "بدء المسح" — وليس على كل حرف.
    fun setRadiusFromSlider(v: Float) {
        val clamped = v.coerceIn(DesertRadarEngine.MIN_RADIUS_KM.toFloat(), DesertRadarEngine.MAX_RADIUS_KM.toFloat())
        radiusKm = clamped
        textValue = clamped.toInt().toString()
    }

    fun onTextFieldChanged(input: String) {
        // اسمح بأي إدخال رقمي جزئي بلا حصر فوري، كي لا يُعاد ضبط الحقل أثناء الكتابة.
        if (input.isEmpty() || input.all { it.isDigit() }) {
            textValue = input
            input.toFloatOrNull()?.let { radiusKm = it }
        }
    }

    fun commitTextFieldValue() {
        // يُستدعى عند مغادرة الحقل أو بدء المسح: الآن فقط يُطبَّق الحصر على الرقم الظاهر.
        val parsed = textValue.toFloatOrNull() ?: DesertRadarEngine.DEFAULT_RADIUS_KM.toFloat()
        val clamped = parsed.coerceIn(DesertRadarEngine.MIN_RADIUS_KM.toFloat(), DesertRadarEngine.MAX_RADIUS_KM.toFloat())
        radiusKm = clamped
        textValue = clamped.toInt().toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DesertDark,
        title = { Text("الرادار الصحراوي", color = DesertGold, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column {
                Text(
                    text = "اختر نطاق البحث (من ${DesertRadarEngine.MIN_RADIUS_KM} إلى ${DesertRadarEngine.MAX_RADIUS_KM} كم)",
                    color = DesertMuted,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { input -> onTextFieldChanged(input) },
                        singleLine = true,
                        modifier = Modifier
                            .width(80.dp)
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused) commitTextFieldValue()
                            }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("كم", color = DesertTan, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Slider(
                    value = radiusKm.coerceIn(DesertRadarEngine.MIN_RADIUS_KM.toFloat(), DesertRadarEngine.MAX_RADIUS_KM.toFloat()),
                    onValueChange = { setRadiusFromSlider(it) },
                    valueRange = DesertRadarEngine.MIN_RADIUS_KM.toFloat()..DesertRadarEngine.MAX_RADIUS_KM.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = DesertGold,
                        activeTrackColor = DesertGold,
                        inactiveTrackColor = DesertCardBorder
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    commitTextFieldValue()
                    onStart(radiusKm.toInt().coerceIn(DesertRadarEngine.MIN_RADIUS_KM, DesertRadarEngine.MAX_RADIUS_KM))
                },
                colors = ButtonDefaults.buttonColors(containerColor = DesertGold, contentColor = Color(0xFF1F1A16))
            ) {
                Text("بدء المسح", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء", color = DesertMuted) }
        }
    )
}

/**
 * "الرادار الصحراوي" — خطوة 2: شاشة المسح الراداري كاملة الشاشة.
 *
 * خط مسح دوّار مستمر (دورة كاملة كل 4 ثوانٍ، كرادارات الطائرات)، والشمال دائمًا للأعلى
 * (متوافق مع تعطيل وضع Course-Up في باقي التطبيق). كل موقع محفوظ ضمن النطاق يظهر فقط
 * بعد أن يمر عليه خط المسح لأول مرة — وليس جميعها دفعة واحدة — ثم يبقى ظاهرًا. التفاعل
 * (الذهاب إلى موقع) يتم عبر القائمة أسفل الدائرة، وليس بالنقر المباشر على الدائرة، لتفادي
 * حسابات نقر دقيقة غير موثوقة على رسم حر (Canvas).
 */
@Composable
fun DesertRadarSweepOverlay(
    targets: List<DesertRadarEngine.RadarTarget>,
    radiusMeters: Double,
    onGoTo: (lat: Double, lon: Double, title: String) -> Unit,
    onClose: () -> Unit
) {
    val sweepAngleState = remember { mutableFloatStateOf(0f) }
    val revealedIds = remember(targets) { mutableStateListOf<Int>() }
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()

    LaunchedEffect(targets) {
        revealedIds.clear()
        var angle = 0f
        val degPerMs = 360f / 4000f // دورة كاملة كل 4 ثوانٍ
        var lastNanos = withFrameNanos { it }
        while (isActive) {
            val nowNanos = withFrameNanos { it }
            val deltaMs = (nowNanos - lastNanos) / 1_000_000f
            lastNanos = nowNanos
            val prevAngle = angle
            angle = (angle + degPerMs * deltaMs) % 360f
            sweepAngleState.floatValue = angle

            for (target in targets) {
                if (revealedIds.contains(target.id)) continue
                val crossed = if (angle >= prevAngle) {
                    target.bearingDeg in prevAngle..angle
                } else {
                    // التفَّ المسح من 360 إلى 0
                    target.bearingDeg >= prevAngle || target.bearingDeg <= angle
                }
                if (crossed) revealedIds.add(target.id)
            }
        }
    }

    // تكبير/تصغير أسماء المواقع عند الضغط على الدائرة (طلب المستخدم 2026-11-26) — لا تكبير
    // فعلي للخريطة نفسها، فقط حجم خط التسميات داخل الدائرة، فيسهل معرفة المكان بالضبط.
    var labelsExpanded by remember { mutableStateOf(false) }

    // لا خلفية صلبة لكامل الشاشة هنا عمداً (بخلاف التصميم السابق): هذه الشاشة تُرسم فوق
    // نفس شاشة الخريطة الحية (DesertMapActivity)، فترك الخلفية شفافة خارج عناصر الواجهة
    // المطلوبة يسمح بظهور الخريطة الفعلية خلفها مباشرة؛ الدائرة نفسها تُقصّ من الخريطة
    // عبر "ثقب" في طبقة التعتيم (scrim) أدناه، وليس عبر خريطة ثانية منفصلة.
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = DesertCard.copy(alpha = 0.92f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "الرادار الصحراوي — نطاق ${(radiusMeters / 1000.0).toInt()} كم",
                            color = DesertGold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        TextButton(onClick = onClose) { Text("إغلاق", color = DesertMuted) }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // شارة "تم رصد الإشارة" بطابع رادار واقعي (مستوحاة من الصورة المرجعية
                        // التي أرسلها المستخدم)، تضيء فقط بعد ظهور أول موقع.
                        if (revealedIds.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(RadarGreen, shape = androidx.compose.foundation.shape.CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = "ظهر حتى الآن ${revealedIds.size} من ${targets.size} موقعًا ضمن النطاق",
                            color = DesertMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clickable { labelsExpanded = !labelsExpanded },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val radiusPx = size.minDimension / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)

                    // طبقة تعتيم مع "ثقب" دائري: كل ما هو خارج دائرة الرادار يُعتَّم بلون
                    // داكن شبه شفاف، وداخل الدائرة يبقى صافياً فتظهر الخريطة الفعلية الحية
                    // (المرسومة أصلاً خلف هذه الشاشة بالكامل) بشكل دائري، بدل خلفية سوداء
                    // مجردة كما كان سابقاً.
                    val scrimPath = Path().apply {
                        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                        addOval(androidx.compose.ui.geometry.Rect(
                            center.x - radiusPx, center.y - radiusPx,
                            center.x + radiusPx, center.y + radiusPx
                        ))
                    }
                    drawPath(path = scrimPath, color = DesertDark.copy(alpha = 0.82f))

                    // إطار الدائرة
                    drawCircle(color = DesertGold, radius = radiusPx, center = center, style = Stroke(width = 2.dp.toPx()))

                    // حلقات المدى (25% - 50% - 75% - 100%)
                    for (fraction in listOf(0.25f, 0.5f, 0.75f, 1f)) {
                        drawCircle(
                            color = DesertGold.copy(alpha = 0.3f),
                            radius = radiusPx * fraction,
                            center = center,
                            style = Stroke(width = 1.5f)
                        )
                    }

                    // الخط الشمالي المرجعي (أعلى الدائرة = شمال)
                    drawLine(
                        color = DesertGold.copy(alpha = 0.4f),
                        start = center,
                        end = Offset(center.x, center.y - radiusPx),
                        strokeWidth = 1.5f
                    )

                    val sweepDeg = sweepAngleState.floatValue

                    // ذيل خافت خلف خط المسح (أثر الرادار)
                    val trailSteps = 24
                    for (i in 0 until trailSteps) {
                        val trailAngle = sweepDeg - i * 1.2f
                        val alpha = (1f - i / trailSteps.toFloat()) * 0.25f
                        val rad = Math.toRadians((trailAngle - 90.0).toDouble())
                        val end = Offset(
                            center.x + (radiusPx * cos(rad)).toFloat(),
                            center.y + (radiusPx * sin(rad)).toFloat()
                        )
                        drawLine(
                            color = RadarGreen.copy(alpha = alpha),
                            start = center,
                            end = end,
                            strokeWidth = 2f
                        )
                    }

                    // خط المسح الحالي
                    val mainRad = Math.toRadians((sweepDeg - 90.0).toDouble())
                    val sweepEnd = Offset(
                        center.x + (radiusPx * cos(mainRad)).toFloat(),
                        center.y + (radiusPx * sin(mainRad)).toFloat()
                    )
                    drawLine(
                        color = RadarGreen,
                        start = center,
                        end = sweepEnd,
                        strokeWidth = 2.5f
                    )

                    // المواقع التي ظهرت حتى الآن (بعد مرور خط المسح عليها)، كل واحدة بنقطة
                    // ملوّنة بحسب نوعها (نفس تصنيف العقل الصحراوي الذكي: أزرق للماء، كهرماني
                    // لمحطات الوقود، بنفسجي للقرى/البيوت، أحمر للجبال/المعالم) + مسافتها
                    // بالكيلومتر/المتر + اسمها — بدل نقطة ذهبية مجردة بلا تمييز كما كان سابقاً.
                    val labelFontSize = if (labelsExpanded) 13.sp else 9.sp
                    for (target in targets) {
                        if (!revealedIds.contains(target.id)) continue
                        val distFraction = (target.distanceMeters / radiusMeters).coerceIn(0.0, 1.0)
                        val pointRad = Math.toRadians((target.bearingDeg - 90.0).toDouble())
                        val point = Offset(
                            center.x + (radiusPx * distFraction * cos(pointRad)).toFloat(),
                            center.y + (radiusPx * distFraction * sin(pointRad)).toFloat()
                        )
                        val (_, categoryColor) = PlaceCategoryIcons.iconFor(target.title)
                        drawCircle(color = categoryColor, radius = if (labelsExpanded) 6f else 4f, center = point)
                        drawCircle(color = categoryColor, radius = if (labelsExpanded) 6f else 4f, center = point, style = Stroke(width = 1f))
                        val labelText = "${target.title}\n${NavigationMath.formatRemaining(target.distanceMeters)}"
                        val layout = textMeasurer.measure(
                            labelText,
                            androidx.compose.ui.text.TextStyle(
                                color = DesertTan,
                                fontSize = labelFontSize,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(point.x + 7f, point.y - layout.size.height / 2f)
                        )
                    }

                    // حروف الاتجاهات الأساسية على حافة الدائرة (شمال/شرق/جنوب/غرب) — ثابتة
                    // بلا دوران (الشمال دائمًا للأعلى، متوافق مع تعطيل Course-Up)، بنفس الطابع
                    // المرئي للصورة المرجعية (حروف عند أطراف القرص الراداري).
                    val cardinalStyle = androidx.compose.ui.text.TextStyle(
                        color = RadarGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    val cardinals = listOf(
                        "شمال" to Offset(center.x, center.y - radiusPx - 16f),
                        "شرق" to Offset(center.x + radiusPx + 16f, center.y),
                        "جنوب" to Offset(center.x, center.y + radiusPx + 16f),
                        "غرب" to Offset(center.x - radiusPx - 16f, center.y)
                    )
                    for ((label, pos) in cardinals) {
                        val layout = textMeasurer.measure(label, cardinalStyle)
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(pos.x - layout.size.width / 2f, pos.y - layout.size.height / 2f)
                        )
                    }

                    // نقطة المستخدم نفسه: خضراء واضحة في المنتصف تمامًا، فوق كل شيء آخر.
                    drawCircle(color = RadarGreen, radius = 7f, center = center)
                    drawCircle(color = Color.White, radius = 3f, center = center)
                }
            }
            Text(
                text = if (labelsExpanded) "اضغط على الدائرة لتصغير الأسماء" else "اضغط على الدائرة لتكبير أسماء المواقع",
                color = DesertMuted,
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = DesertCard.copy(alpha = 0.92f),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(text = "المواقع الظاهرة:", color = DesertGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    val revealedTargets = targets.filter { revealedIds.contains(it.id) }
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(revealedTargets, key = { it.id }) { target ->
                            RadarResultRow(target = target, onGoTo = onGoTo)
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadarResultRow(
    target: DesertRadarEngine.RadarTarget,
    onGoTo: (lat: Double, lon: Double, title: String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = DesertCard,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (icon, iconColor) = PlaceCategoryIcons.iconFor(target.title)
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(target.title, color = DesertTan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "${NavigationMath.formatRemaining(target.distanceMeters)} — ${NavigationMath.arabicDirection(target.bearingDeg)}",
                    color = DesertMuted,
                    fontSize = 11.sp
                )
            }
            TextButton(onClick = { onGoTo(target.latitude, target.longitude, target.title) }) {
                Text("اذهب إليه", color = DesertGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
