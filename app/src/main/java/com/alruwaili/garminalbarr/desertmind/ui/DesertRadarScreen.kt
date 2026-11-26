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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

    // إعادة تصميم 2026-11-26 (طلب المستخدم): لم تعد هذه الشاشة "ثقبًا" يُظهر الخريطة الحية
    // خلف الدائرة — كانت أسماء الأماكن المرسومة من الخريطة الفعلية نفسها تتداخل بصريًا مع
    // أسماء المواقع التي يرسمها الرادار فوقها، فيبدو المنظر مزدحمًا وبدائيًا. الآن الشاشة
    // رادار رسمي مستقل تمامًا بخلفية داكنة صلبة (بلا خريطة خلفها إطلاقًا)، ولا يظهر فيها إلا
    // حلقات المدى وحروف الاتجاهات ونقاط المواقع الحقيقية وأسماؤها فقط.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DesertDark)
    ) {
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
                        // إصلاح 2026-11-26: زر "إغلاق" النصي الباهت كان يضيع وسط زحمة الأسماء
                        // المتداخلة في الواجهة القديمة فيبدو وكأنه لا يوجد زر رجوع/إغلاق إطلاقًا.
                        // الآن دائرة "✕" بارزة بخلفية صلبة في الزاوية، أوضح بصريًا ولا تُخطئها العين.
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(DesertCardBorder, shape = androidx.compose.foundation.shape.CircleShape)
                                .clickable(onClick = onClose),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✕", color = DesertTan, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
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

            // إعادة تصميم 2026-11-26 (طلب المستخدم — مطابقة للصورة المرجعية): بدل دائرة
            // الرادار فوق قائمة سفلية واحدة بعرض الشاشة، الآن ثلاثة أعمدة أفقية — أقرب
            // المواقع على اليمين، دائرة الرادار بالمنتصف، أقرب المواقع على اليسار؛ القائمة
            // الواحدة المرتّبة حسب البعد تُقسَّم بالتناوب (الأقرب لليمين، التالي لليسار...).
            // أول عنصر في Row هو الأقصى يمينًا بصريًا (التطبيق عربي/RTL).
            val sortedForColumns = targets
                .filter { revealedIds.contains(it.id) }
                .sortedBy { it.distanceMeters }
            val rightColumnTargets = sortedForColumns.filterIndexed { i, _ -> i % 2 == 0 }
            val leftColumnTargets = sortedForColumns.filterIndexed { i, _ -> i % 2 == 1 }

            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                NearbyColumn(
                    targets = rightColumnTargets,
                    onGoTo = onGoTo,
                    modifier = Modifier
                        .weight(0.8f)
                        .fillMaxHeight()
                )

                Box(
                    // لا aspectRatio هنا عمدًا: الدمج بين weight() وaspectRatio() في نفس Row غير
                    // موثوق (قيد قياس متعارض). الدائرة تبقى مُستديرة فعليًا بلا تمدّد لأن الرسم
                    // داخل Canvas يعتمد size.minDimension (أصغر بُعد) لا العرض/الارتفاع منفردين.
                    modifier = Modifier
                        .weight(1.3f)
                        .fillMaxHeight()
                        .clickable { labelsExpanded = !labelsExpanded },
                    contentAlignment = Alignment.Center
                ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val radiusPx = size.minDimension / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)

                    // قرص الرادار الرسمي: تعبئة داكنة صلبة كاملة (بلا أي خريطة خلفها)، مع
                    // حلقات مدى وخطوط شعاعية خفيفة تعطي الطابع الرسمي المطلوب (كما في الصورة
                    // المرجعية)، بدل "ثقب" يكشف الخريطة الحية كما كان سابقاً.
                    drawCircle(color = Color(0xFF14100B), radius = radiusPx, center = center)

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

                    // خطوط شعاعية خفيفة كل 30 درجة (طابع رادار رسمي، كالصورة المرجعية) — خفيفة
                    // بما يكفي كي لا تنافس أسماء المواقع على الوضوح.
                    for (spokeDeg in 0 until 360 step 30) {
                        val spokeRad = Math.toRadians((spokeDeg - 90.0).toDouble())
                        val spokeEnd = Offset(
                            center.x + (radiusPx * cos(spokeRad)).toFloat(),
                            center.y + (radiusPx * sin(spokeRad)).toFloat()
                        )
                        drawLine(
                            color = DesertGold.copy(alpha = 0.15f),
                            start = center,
                            end = spokeEnd,
                            strokeWidth = 1f
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

                    // إصلاح الازدحام (طلب المستخدم 2026-11-26): عند كثرة المواقع القريبة من
                    // بعضها، كانت كل الأسماء تُرسم في نفس الموضع النسبي من نقطتها فتتراكم
                    // فوق بعضها وتصبح غير مقروءة إطلاقًا. الحل: ترتيب المواقع من الأقرب
                    // للأبعد (الأقرب أولى بالحصول على اسم ظاهر)، ثم تجربة عدة مواضع حول كل
                    // نقطة (يمين، يسار، أعلى، أسفل) واختيار أول موضع لا يتداخل مع أي اسم
                    // سبق رسمه. إن لم يوجد موضع خالٍ لاسم معيّن، تُرسم نقطته الملوّنة فقط بلا
                    // نص (يبقى ظاهرًا ويمكن الوصول إليه من القائمة أسفل الدائرة) بدل حشر
                    // الاسم فوق أسماء أخرى.
                    val sortedRevealed = targets
                        .filter { revealedIds.contains(it.id) }
                        .sortedBy { it.distanceMeters }
                    val placedLabelRects = mutableListOf<androidx.compose.ui.geometry.Rect>()

                    // "أبرز المواقع" (طلب المستخدم 2026-11-26): أقرب 3 مواقع ظاهرة تُميَّز بخط
                    // واضح يصل من نقطتها إلى اسمها (لا مجرد تقريب الاسم من النقطة كبقية
                    // المواقع)، فيسهل تمييزها بصريًا كأبرز ما في النطاق الحالي.
                    val prominentCount = minOf(3, sortedRevealed.size)

                    sortedRevealed.forEachIndexed { index, target ->
                        val isProminent = index < prominentCount
                        val distFraction = (target.distanceMeters / radiusMeters).coerceIn(0.0, 1.0)
                        val pointRad = Math.toRadians((target.bearingDeg - 90.0).toDouble())
                        val point = Offset(
                            center.x + (radiusPx * distFraction * cos(pointRad)).toFloat(),
                            center.y + (radiusPx * distFraction * sin(pointRad)).toFloat()
                        )
                        val (_, categoryColor) = PlaceCategoryIcons.iconFor(target.title)
                        val dotRadius = if (isProminent) 7f else if (labelsExpanded) 6f else 4f
                        drawCircle(color = categoryColor, radius = dotRadius, center = point)
                        drawCircle(color = categoryColor, radius = dotRadius, center = point, style = Stroke(width = if (isProminent) 1.5f else 1f))

                        val labelText = "${target.title}\n${NavigationMath.formatRemaining(target.distanceMeters)}"
                        val layout = textMeasurer.measure(
                            labelText,
                            androidx.compose.ui.text.TextStyle(
                                color = DesertTan,
                                fontSize = labelFontSize,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        val w = layout.size.width.toFloat()
                        val h = layout.size.height.toFloat()
                        // المواقع البارزة تبتعد مسافة أكبر (24px) كي يظهر خط رابط واضح بينها
                        // وبين اسمها؛ بقية المواقع تبقى قريبة من نقطتها (7px) كالسابق.
                        val gap = if (isProminent) 24f else 7f
                        val candidates = listOf(
                            Offset(point.x + gap, point.y - h / 2f),      // يمين النقطة
                            Offset(point.x - w - gap, point.y - h / 2f),  // يسار النقطة
                            Offset(point.x - w / 2f, point.y - h - gap),  // أعلى النقطة
                            Offset(point.x - w / 2f, point.y + gap)       // أسفل النقطة
                        )
                        for (candidate in candidates) {
                            val rect = androidx.compose.ui.geometry.Rect(
                                candidate.x, candidate.y, candidate.x + w, candidate.y + h
                            )
                            if (placedLabelRects.none { it.overlaps(rect) }) {
                                if (isProminent) {
                                    // خط رابط بارز (أبرز المواقع) من النقطة إلى أقرب حافة لمستطيل النص.
                                    val labelCenter = Offset(candidate.x + w / 2f, candidate.y + h / 2f)
                                    drawLine(
                                        color = categoryColor,
                                        start = point,
                                        end = labelCenter,
                                        strokeWidth = 2f
                                    )
                                }
                                drawText(textLayoutResult = layout, topLeft = candidate)
                                placedLabelRects.add(rect)
                                break
                            }
                        }
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

                NearbyColumn(
                    targets = leftColumnTargets,
                    onGoTo = onGoTo,
                    modifier = Modifier
                        .weight(0.8f)
                        .fillMaxHeight()
                )
            }

            Text(
                text = if (labelsExpanded) "اضغط على الدائرة لتصغير الأسماء" else "اضغط على الدائرة لتكبير أسماء المواقع",
                color = DesertMuted,
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/**
 * عمود جانبي ضيّق (يمين أو يسار دائرة الرادار) يعرض أقرب المواقع إليه حسب البعد —
 * جزء من إعادة تصميم 2026-11-26 لمطابقة الصورة المرجعية (قوائم على الجانبين بدل عمود
 * سفلي واحد بعرض الشاشة).
 */
@Composable
private fun NearbyColumn(
    targets: List<DesertRadarEngine.RadarTarget>,
    onGoTo: (lat: Double, lon: Double, title: String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = DesertCard.copy(alpha = 0.92f),
        modifier = modifier
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(targets, key = { it.id }) { target ->
                CompactRadarResultRow(target = target, onGoTo = onGoTo)
            }
        }
    }
}

@Composable
private fun CompactRadarResultRow(
    target: DesertRadarEngine.RadarTarget,
    onGoTo: (lat: Double, lon: Double, title: String) -> Unit
) {
    val (icon, iconColor) = PlaceCategoryIcons.iconFor(target.title)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = DesertCardBorder.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onGoTo(target.latitude, target.longitude, target.title) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(target.title, color = DesertTan, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    text = NavigationMath.formatRemaining(target.distanceMeters),
                    color = DesertMuted,
                    fontSize = 10.sp
                )
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
