package com.alruwaili.garminalbarr.map.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import android.widget.Toast
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alruwaili.garminalbarr.LocationLightweight
import com.alruwaili.garminalbarr.R
import com.alruwaili.garminalbarr.map.LandmarkSearchViewModel
import java.util.Locale

private val ScrimColor = Color(0xB3000000)
private val DesertDark = Color(0xFF1F1A16)
private val DesertCard = Color(0xFF2C2013)
private val DesertCardBorder = Color(0xFF42372E)
private val DesertGold = Color(0xFFDDA01E)
private val DesertRed = Color(0xFFE53935)
private val DesertTextPrimary = Color(0xFFFFFFFF)
private val DesertTextSecondary = Color(0xFFA89F91)
private val DesertTextMuted = Color(0xFF756A5E)

/**
 * Isolated Compose overload that observes [viewModel] states internally so [DesertMapActivity]
 * does not recompose when search queries or results update.
 */
@Composable
fun LandmarkSearchOverlay(
    viewModel: LandmarkSearchViewModel,
    onResultSelected: (LocationLightweight) -> Unit,
    onDismiss: () -> Unit,
    userLat: Double? = null,
    userLon: Double? = null,
    onOpenCompass: ((LocationLightweight) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val results by viewModel.results.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val cacheReady by viewModel.cacheReady.collectAsState()

    LandmarkSearchOverlay(
        results = results,
        isSearching = isSearching,
        cacheReady = cacheReady,
        onQueryChanged = { viewModel.onQueryChanged(it) },
        onResultSelected = onResultSelected,
        onDismiss = onDismiss,
        onToggleFavorite = { viewModel.toggleFavorite(it) },
        userLat = userLat,
        userLon = userLon,
        onOpenCompass = onOpenCompass,
        modifier = modifier
    )
}

@Composable
fun LandmarkSearchOverlay(
    results: List<LocationLightweight>,
    isSearching: Boolean,
    cacheReady: Boolean,
    onQueryChanged: (String) -> Unit,
    onResultSelected: (LocationLightweight) -> Unit,
    onDismiss: () -> Unit,
    onToggleFavorite: ((LocationLightweight) -> Unit)? = null,
    userLat: Double? = null,
    userLon: Double? = null,
    onOpenCompass: ((LocationLightweight) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var queryText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val handleDismiss: () -> Unit = {
        focusManager.clearFocus()
        keyboardController?.hide()
        onDismiss()
    }

    val handleSelect: (LocationLightweight) -> Unit = { loc ->
        focusManager.clearFocus()
        keyboardController?.hide()
        onResultSelected(loc)
    }

    BackHandler(onBack = handleDismiss)

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ScrimColor)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                handleDismiss()
            }
    ) {
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        // Prevent dismiss when tapping inside the search content area
                    }
            ) {
                // Search Bar Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DesertCard,
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, DesertGold),
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            IconButton(
                                onClick = handleDismiss,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "رجوع",
                                    tint = DesertGold,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (queryText.isEmpty()) {
                                    Text(
                                        text = "ابحث بالاسم أو المعلم...",
                                        color = DesertTextSecondary,
                                        fontSize = 15.sp
                                    )
                                }
                                BasicTextField(
                                    value = queryText,
                                    onValueChange = {
                                        queryText = it
                                        onQueryChanged(it)
                                    },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = DesertTextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    cursorBrush = SolidColor(DesertGold),
                                    keyboardOptions = KeyboardOptions(
                                        imeAction = ImeAction.Search
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onSearch = {
                                            keyboardController?.hide()
                                        }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester)
                                )
                            }

                            if (queryText.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        queryText = ""
                                        onQueryChanged("")
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "مسح",
                                        tint = DesertTextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        if (isSearching) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp),
                                color = DesertGold,
                                trackColor = DesertDark
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Results Container Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DesertDark,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DesertCardBorder),
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                ) {
                    when {
                        !cacheReady -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        color = DesertGold,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "جاري تحميل بيانات المواقع...",
                                        color = DesertTextSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                        queryText.isBlank() -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "اكتب اسم المعلم أو الموقع للبحث في أكثر من 314 ألف موقع بري",
                                    color = DesertTextMuted,
                                    fontSize = 13.sp
                                )
                            }
                        }
                        results.isEmpty() && !isSearching -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "لا توجد نتائج مطابقة لـ \"$queryText\"",
                                    color = DesertTextSecondary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                itemsIndexed(
                                    items = results,
                                    key = { index, item -> "${item.title}_${item.latitude}_${item.longitude}_$index" }
                                ) { _, item ->
                                    SearchResultRow(
                                        location = item,
                                        userLat = userLat,
                                        userLon = userLon,
                                        onClick = { handleSelect(item) },
                                        onToggleFavorite = {
                                            onToggleFavorite?.invoke(item)
                                            val willBeFav = !(item.isFav ?: false)
                                            val msg = if (willBeFav) "تمت الإضافة للمفضلة" else "تمت الإزالة من المفضلة"
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        },
                                        onOpenCompass = { onOpenCompass?.invoke(item) }
                                    )
                                    HorizontalDivider(
                                        color = DesertCardBorder.copy(alpha = 0.6f),
                                        thickness = 0.5.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    location: LocationLightweight,
    userLat: Double?,
    userLon: Double?,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenCompass: () -> Unit
) {
    val isFav = location.isFav == true
    // 2026-11-26: بدل الإحداثيات الرقمية تحت الاسم، تُعرض المسافة من موقع المستخدم الحالي —
    // وبدل أيقونة دبوس GPS، سهم البوصلة نفسه المستخدم في بقية التطبيق، متجهًا نحو هذا الموقع.
    val distanceText: String? = if (userLat != null && userLon != null && location.latitude != null && location.longitude != null) {
        com.alruwaili.garminalbarr.map.NavigationMath.formatRemaining(
            com.alruwaili.garminalbarr.map.NavigationMath.distanceMeters(userLat, userLon, location.latitude, location.longitude)
        )
    } else null
    val bearingDeg: Float? = if (userLat != null && userLon != null && location.latitude != null && location.longitude != null) {
        com.alruwaili.garminalbarr.map.NavigationMath.bearingDeg(userLat, userLon, location.latitude, location.longitude)
    } else null

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // سهم البوصلة — أكبر قليلًا من الأيقونة السابقة، ومتجه نحو هذا الموقع تحديدًا؛
        // الضغط عليه يفتح البوصلة نحو هذا الموقع (وليس الوجهة النشطة الحالية).
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(DesertCard)
                .clickable(onClick = onOpenCompass),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Navigation,
                contentDescription = "فتح البوصلة نحو هذا الموقع",
                tint = DesertGold,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        // Icons.Default.Navigation مرسومة أصلاً مشيرة للأعلى (0°=شمال)، فتدويرها
                        // مباشرة بزاوية الاتجاه (bearingDeg) يكفي لتأشر فعليًا نحو هذا الموقع —
                        // نفس مبدأ سهم الهدف المستخدم في بقية شاشات التطبيق (TargetCompassOverlay).
                        rotationZ = bearingDeg ?: 0f
                    }
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = location.title ?: "موقع غير مسمى",
                color = DesertTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = distanceText ?: "المسافة غير معروفة",
                color = DesertTextSecondary,
                fontSize = 11.sp
            )
        }

        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFav) "إزالة من المفضلة" else "إضافة إلى المفضلة",
                tint = if (isFav) DesertRed else DesertTextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
