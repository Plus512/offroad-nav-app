package com.alruwaili.garminalbarr.map.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alruwaili.garminalbarr.R

private val ControlBg = Color(0xFF2C2013)
private val ControlIcon = Color.White
private val DesertGold = Color(0xFFDDA01E)
private val DockBg = Color(0xE62C2013)

/**
 * Hot-path overload: takes [State] so only [DockCompassButton] recomposes at ~15 Hz.
 * Prefer this from sensor-driven screens (see DesertMapActivity).
 */
@Composable
fun MapControlsOverlay(
    azimuthState: State<Float>,
    scaleText: String,
    onCompassClick: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onGpsClick: () -> Unit,
    onSavedClick: () -> Unit,
    onMenuClick: () -> Unit,
    onSearchClick: () -> Unit = {},
    onMyLocationsClick: () -> Unit = {},
    isRecenterVisible: Boolean = false,
    onRecenterClick: () -> Unit = {},
    isCourseUp: Boolean = false,
    onOrientationToggle: () -> Unit = onCompassClick,
    modifier: Modifier = Modifier
) {
    MapControlsLayout(
        compassState = azimuthState,
        onCompassClick = onCompassClick,
        scaleText = scaleText,
        onZoomIn = onZoomIn,
        onZoomOut = onZoomOut,
        onGpsClick = onGpsClick,
        onSavedClick = onSavedClick,
        onMenuClick = onMenuClick,
        onSearchClick = onSearchClick,
        onMyLocationsClick = onMyLocationsClick,
        isRecenterVisible = isRecenterVisible,
        onRecenterClick = onRecenterClick,
        isCourseUp = isCourseUp,
        onOrientationToggle = onOrientationToggle,
        modifier = modifier
    )
}

@Composable
fun MapControlsOverlay(
    azimuth: Float,
    scaleText: String,
    onCompassClick: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onGpsClick: () -> Unit,
    onSavedClick: () -> Unit,
    onMenuClick: () -> Unit,
    onSearchClick: () -> Unit = {},
    onMyLocationsClick: () -> Unit = {},
    isRecenterVisible: Boolean = false,
    onRecenterClick: () -> Unit = {},
    isCourseUp: Boolean = false,
    onOrientationToggle: () -> Unit = onCompassClick,
    modifier: Modifier = Modifier
) {
    // Compat path: wrap the snapshot value so the compass leaf stays isolated.
    // Note: callers passing a raw Float still recompose here — migrate hot paths
    // to the State overload above.
    val azimuthState = remember { mutableFloatStateOf(azimuth) }
    // Sync outside of composition to avoid an extra recomposition pass.
    androidx.compose.runtime.SideEffect { azimuthState.floatValue = azimuth }
    MapControlsOverlay(
        azimuthState = azimuthState,
        scaleText = scaleText,
        onCompassClick = onCompassClick,
        onZoomIn = onZoomIn,
        onZoomOut = onZoomOut,
        onGpsClick = onGpsClick,
        onSavedClick = onSavedClick,
        onMenuClick = onMenuClick,
        onSearchClick = onSearchClick,
        onMyLocationsClick = onMyLocationsClick,
        isRecenterVisible = isRecenterVisible,
        onRecenterClick = onRecenterClick,
        isCourseUp = isCourseUp,
        onOrientationToggle = onOrientationToggle,
        modifier = modifier
    )
}

@Composable
private fun MapControlsLayout(
    compassState: State<Float>,
    onCompassClick: () -> Unit,
    scaleText: String,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onGpsClick: () -> Unit,
    onSavedClick: () -> Unit,
    onMenuClick: () -> Unit,
    onSearchClick: () -> Unit,
    onMyLocationsClick: () -> Unit,
    isRecenterVisible: Boolean = false,
    onRecenterClick: () -> Unit = {},
    isCourseUp: Boolean = false,
    onOrientationToggle: () -> Unit = onCompassClick,
    modifier: Modifier = Modifier
) {
    var isDialExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        // Transparent outside-tap dismiss scrim: mounted ONLY when the dial is expanded.
        // When collapsed, omitted completely so 100% of gestures reach the map canvas.
        if (isDialExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            isDialExpanded = false
                        }
                    }
            )
        }

        // Bidirectional Expandable Right-Side Control Dial strictly centered at Alignment.CenterEnd
        ExpandableRightControlDial(
            isExpanded = isDialExpanded,
            onExpandedChange = { isDialExpanded = it },
            azimuthState = compassState,
            isCourseUp = isCourseUp,
            onOrientationToggle = {
                onOrientationToggle()
                isDialExpanded = false
            },
            onSearchClick = {
                onSearchClick()
                isDialExpanded = false
            },
            onGpsClick = {
                onGpsClick()
                isDialExpanded = false
            },
            onZoomIn = {
                onZoomIn()
                isDialExpanded = false
            },
            onZoomOut = {
                onZoomOut()
                isDialExpanded = false
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
        )

        // Graphical Scale Bar
        if (scaleText.isNotEmpty()) {
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xCC1F1A16),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x6642372E)),
                shadowElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 80.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = scaleText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .height(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(Color.White)
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .width(2.dp)
                                .height(6.dp)
                                .background(Color.White)
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(2.dp)
                                .height(6.dp)
                                .background(Color.White)
                        )
                    }
                }
            }
        }

        // Floating Recenter FAB: appears when user pans the map during active driving navigation.
        if (isRecenterVisible) {
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF2C2013),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, DesertGold),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 84.dp)
                    .clickable(
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClickLabel = "إعادة التوسيط",
                        onClick = onRecenterClick
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = DesertGold,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "إعادة التوسيط",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp, start = 12.dp, end = 12.dp)
                .shadow(8.dp, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(DockBg)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 380.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockCompassButton(
                azimuthState = compassState,
                label = "البوصلة",
                onClick = onCompassClick
            )
            DockButton(
                icon = Icons.Default.Star,
                label = "المحفوظات",
                onClick = onSavedClick
            )
            DockButton(
                icon = Icons.Default.Place,
                label = "مواقعي",
                onClick = onMyLocationsClick
            )
            DockButton(
                icon = Icons.Default.Menu,
                label = "الخرائط",
                onClick = onMenuClick
            )
        }
    }
}

/**
 * Isolated compass leaf in dock style.
 * Uses graphicsLayer { rotationZ = -azimuthState.value } to bypass both
 * composition and layout passes: 0 recompositions at 60Hz.
 * Click resets the map to True North + recenters on the user (static North-Up).
 */
@Composable
fun DockCompassButton(
    azimuthState: State<Float>,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(min = 44.dp)
            .clickable(
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick
            )
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = label,
            tint = ControlIcon,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { rotationZ = -azimuthState.value }
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 9.sp,
            maxLines = 1,
            softWrap = false,
            color = ControlIcon
        )
    }
}

@Composable
private fun ZoomButton(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(ControlBg)
            .clickable(
                role = androidx.compose.ui.semantics.Role.Button,
                onClickLabel = text,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = ControlIcon
        )
    }
}

@Composable
private fun DockButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(min = 44.dp)
            .clickable(
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = ControlIcon,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 9.sp,
            maxLines = 1,
            softWrap = false,
            color = ControlIcon
        )
    }
}

/**
 * Bidirectional Expandable Right-Side Control Dial.
 * Centered strictly at [Alignment.CenterEnd].
 *
 * When collapsed, occupies only a single 48dp master FAB with zero gesture interference.
 * When tapped, rotates 135° into an ✕ close icon and animates 5 action buttons:
 * - 3 Buttons Expanding Upward:
 *   1. My Location / GPS recenter (-50dp)
 *   2. POI Search (-100dp)
 *   3. Map Orientation Toggle: North-Up vs Course-Up (-150dp)
 * - 2 Buttons Expanding Downward:
 *   4. Zoom In (+50dp)
 *   5. Zoom Out (+100dp)
 */
@Composable
fun ExpandableRightControlDial(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    azimuthState: State<Float>,
    isCourseUp: Boolean,
    onOrientationToggle: () -> Unit,
    onSearchClick: () -> Unit,
    onGpsClick: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconProgress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "MasterIconMorph"
    )

    val dialProgress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "DialProgress"
    )

    Box(
        modifier = modifier
            .width(48.dp)
            .then(
                if (isExpanded) Modifier.height(360.dp) else Modifier.height(48.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        // Child action buttons (rendered only when expanding or expanded)
        if (isExpanded || dialProgress > 0.01f) {
            // 3 Buttons Expanding Upwards:
            // 1. My Location (closest above: -50dp)
            DialActionButton(
                yOffset = (-50).dp,
                progress = dialProgress,
                enabled = isExpanded,
                onClick = onGpsClick,
                contentDescription = "موقعي الحالي"
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "موقعي الحالي",
                    tint = DesertGold,
                    modifier = Modifier.size(22.dp)
                )
            }

            // 2. POI Search (middle above: -100dp)
            DialActionButton(
                yOffset = (-100).dp,
                progress = dialProgress,
                enabled = isExpanded,
                onClick = onSearchClick,
                contentDescription = "بحث"
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.search),
                    contentDescription = "بحث",
                    tint = DesertGold,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 3. Map Orientation Toggle (topmost above: -150dp)
            DialActionButton(
                yOffset = (-150).dp,
                progress = dialProgress,
                enabled = isExpanded,
                onClick = onOrientationToggle,
                contentDescription = if (isCourseUp) "مع اتجاه الحركة (Course-Up)" else "الشمال للأعلى (North-Up)",
                border = if (isCourseUp) BorderStroke(1.5.dp, DesertGold) else BorderStroke(1.dp, Color(0x66DDA01E))
            ) {
                DialCompassNeedle(
                    isCourseUp = isCourseUp,
                    azimuthState = azimuthState
                )
            }

            // 2 Buttons Expanding Downwards:
            // 4. Zoom In (closest below: +50dp)
            DialActionButton(
                yOffset = 50.dp,
                progress = dialProgress,
                enabled = isExpanded,
                onClick = onZoomIn,
                contentDescription = "تكبير"
            ) {
                Text(
                    text = "+",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // 5. Zoom Out (furthest below: +100dp)
            DialActionButton(
                yOffset = 100.dp,
                progress = dialProgress,
                enabled = isExpanded,
                onClick = onZoomOut,
                contentDescription = "تصغير"
            ) {
                Text(
                    text = "\u2212",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // Master Trigger FAB: 48dp circle, Desert Dark background with 1.5dp Desert Gold border
        Box(
            modifier = Modifier
                .size(48.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(ControlBg)
                .border(BorderStroke(1.5.dp, DesertGold), CircleShape)
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (isExpanded) "إغلاق عناصر التحكم" else "فتح عناصر التحكم",
                    onClick = { onExpandedChange(!isExpanded) }
                ),
            contentAlignment = Alignment.Center
        ) {
            MasterDialIcon(
                progress = iconProgress,
                color = DesertGold
            )
        }
    }
}

/**
 * Standalone overload for [ExpandableRightControlDial] managing its own expansion state.
 */
@Composable
fun ExpandableRightControlDial(
    azimuthState: State<Float>,
    isCourseUp: Boolean,
    onOrientationToggle: () -> Unit,
    onSearchClick: () -> Unit,
    onGpsClick: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    ExpandableRightControlDial(
        isExpanded = isExpanded,
        onExpandedChange = { isExpanded = it },
        azimuthState = azimuthState,
        isCourseUp = isCourseUp,
        onOrientationToggle = onOrientationToggle,
        onSearchClick = onSearchClick,
        onGpsClick = onGpsClick,
        onZoomIn = onZoomIn,
        onZoomOut = onZoomOut,
        modifier = modifier
    )
}

/**
 * Reusable animated action button for the control dial.
 */
@Composable
private fun DialActionButton(
    yOffset: Dp,
    progress: Float,
    onClick: () -> Unit,
    contentDescription: String,
    border: BorderStroke = BorderStroke(1.dp, Color(0x66DDA01E)),
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val currentY = yOffset * progress
    val currentScale = 0.3f + 0.7f * progress.coerceIn(0f, 1f)
    val currentAlpha = progress.coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .offset(y = currentY)
            .size(44.dp)
            .graphicsLayer {
                alpha = currentAlpha
                scaleX = currentScale
                scaleY = currentScale
            }
            .shadow(5.dp, CircleShape)
            .clip(CircleShape)
            .background(ControlBg)
            .border(border, CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * Orientation needle indicator for the dial toggle button.
 * In North-Up: static needle pointing straight UP (0° True North).
 * In Course-Up: dynamic needle rotating counter to azimuth so the red pointer tracks North.
 */
@Composable
private fun DialCompassNeedle(
    isCourseUp: Boolean,
    azimuthState: State<Float>,
    modifier: Modifier = Modifier
) {
    val northPath = remember { androidx.compose.ui.graphics.Path() }
    val southPath = remember { androidx.compose.ui.graphics.Path() }
    Box(
        modifier = modifier
            .size(24.dp)
            .graphicsLayer {
                rotationZ = if (isCourseUp) -azimuthState.value else 0f
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val halfWidth = 3.2.dp.toPx()
            val needleLength = 8.5.dp.toPx()

            // North pointer (vivid red #E53935)
            northPath.rewind()
            northPath.moveTo(cx, cy - needleLength)
            northPath.lineTo(cx + halfWidth, cy)
            northPath.lineTo(cx - halfWidth, cy)
            northPath.close()
            drawPath(northPath, color = Color(0xFFE53935))

            // South pointer (light grey #D4D4D4)
            southPath.rewind()
            southPath.moveTo(cx, cy + needleLength)
            southPath.lineTo(cx + halfWidth, cy)
            southPath.lineTo(cx - halfWidth, cy)
            southPath.close()
            drawPath(southPath, color = Color(0xFFD4D4D4))

            // Central pivot dot
            drawCircle(
                color = Color(0xFF2C2013),
                radius = 2.dp.toPx(),
                center = Offset(cx, cy)
            )
        }
    }
}

/**
 * Master FAB trigger icon that smoothly morphs between 3 horizontal bars (Menu / collapsed)
 * and an ✕ symbol (Close / expanded) using line translations and rotations on a Canvas.
 */
@Composable
fun MasterDialIcon(
    progress: Float,
    color: Color = DesertGold,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val barLength = 17.dp.toPx()
        val strokeW = 2.2.dp.toPx()
        val halfLen = barLength / 2f

        val idleTopY = 6.5.dp.toPx()
        val idleBottomY = 17.5.dp.toPx()
        val targetY = cy

        // Interpolated Y positions
        val topY = idleTopY + (targetY - idleTopY) * progress
        val bottomY = idleBottomY + (targetY - idleBottomY) * progress

        // Middle bar: fades and scales out horizontally
        val midAlpha = (1f - progress * 1.6f).coerceIn(0f, 1f)
        if (midAlpha > 0f) {
            val midHalfLen = halfLen * (1f - progress * 0.4f)
            drawLine(
                color = color.copy(alpha = midAlpha),
                start = Offset(cx - midHalfLen, cy),
                end = Offset(cx + midHalfLen, cy),
                strokeWidth = strokeW,
                cap = StrokeCap.Round
            )
        }

        // Top bar: rotates +45 degrees
        val topAngle = 45f * progress
        withTransform({
            rotate(degrees = topAngle, pivot = Offset(cx, topY))
        }) {
            drawLine(
                color = color,
                start = Offset(cx - halfLen, topY),
                end = Offset(cx + halfLen, topY),
                strokeWidth = strokeW,
                cap = StrokeCap.Round
            )
        }

        // Bottom bar: rotates -45 degrees
        val bottomAngle = -45f * progress
        withTransform({
            rotate(degrees = bottomAngle, pivot = Offset(cx, bottomY))
        }) {
            drawLine(
                color = color,
                start = Offset(cx - halfLen, bottomY),
                end = Offset(cx + halfLen, bottomY),
                strokeWidth = strokeW,
                cap = StrokeCap.Round
            )
        }
    }
}

