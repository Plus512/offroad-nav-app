package com.alruwaili.garminalbarr.legacymap

import com.alruwaili.garminalbarr.R

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.alruwaili.garminalbarr.legacymap.nav.GeoMath
import com.alruwaili.garminalbarr.legacymap.nav.SavedPlacesStore
import com.alruwaili.garminalbarr.legacymap.nav.SavedTracksStore
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.maplibre.android.geometry.LatLng

/**
 * شاشة "المحفوظات" (مواقعي / مساراتي) — أُعيد بناؤها بالكامل كشاشة مستقلة (بدل النافذة المنبثقة
 * السابقة) لتطابق تصميم وسلوك التطبيق الآخر للمستخدم نفسه «خرائط الصحراء - الدليلة»
 * (com.navigationgps.desert)، بناءً على صوره الفعلية المرسلة وطلبه الصريح:
 * - تبويبان علويان: "المواقع (N)" و"المسارات المسجَّلة (N)".
 * - بطاقة لكل عنصر: اسم، مسافة، إحداثيات، أيقونة دبوس، قلب "مفضّلة"، وقائمة خيارات (⋮).
 * - قائمة المواقع: الذهاب إلى الخريطة / مشاركة / حذف الموقع.
 * - قائمة المسارات: حذف / مشاركة / إظهار على الخريطة / إخفاء / الذهاب إلى.
 * - زر "+" عائم لإضافة موقع يدوياً.
 * - حالة فارغة نصّية مطابقة لما ظهر فعلياً في لقطات الشاشة المرسلة.
 *
 * يُستدعى عبر Intent من MainActivity (زر fabSavedItems) ومن مسار حفظ تسجيل المسار (TrackRecorder)،
 * مع تمرير موقع المستخدم الحالي اختيارياً (EXTRA_USER_LAT/LON) لحساب المسافة الحيّة لكل عنصر، ومفتاح
 * التبويب المطلوب فتحه مبدئياً (EXTRA_SHOW_PLACES).
 */
class SavedItemsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SHOW_PLACES = "show_places"
        const val EXTRA_USER_LAT = "user_lat"
        const val EXTRA_USER_LON = "user_lon"
    }

    private lateinit var placesStore: SavedPlacesStore
    private lateinit var tracksStore: SavedTracksStore
    private var showingPlaces = true
    private var userLocation: LatLng? = null

    private lateinit var countChip: TextView
    private lateinit var tabPlaces: TextView
    private lateinit var tabTracks: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyIcon: TextView
    private lateinit var emptyTitle: TextView
    private lateinit var emptyDesc: TextView
    private lateinit var emptyContainer: LinearLayout
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        placesStore = SavedPlacesStore(this)
        tracksStore = SavedTracksStore(this)
        showingPlaces = intent.getBooleanExtra(EXTRA_SHOW_PLACES, true)
        val lat = intent.getDoubleExtra(EXTRA_USER_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_USER_LON, Double.NaN)
        userLocation = if (!lat.isNaN() && !lon.isNaN()) LatLng(lat, lon) else null

        setContentView(buildRootView())
        refreshList()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun neon(colorRes: Int) = resources.getColor(colorRes, theme)

    // ===================== بناء الواجهة برمجياً (بدون XML إضافي) =====================

    private fun buildRootView(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(neon(R.color.neon_bg))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(column)

        column.addView(buildHeaderRow())
        column.addView(buildTabsRow())

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(96))
        }
        scrollView.addView(listContainer)
        column.addView(scrollView)

        // حالة فارغة: أيقونة + عنوان + وصف، مطابقة لما ظهر في لقطة الشاشة الفعلية للمسارات الفارغة.
        emptyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(48), dp(32), dp(32))
            visibility = View.GONE
        }
        emptyIcon = TextView(this).apply {
            text = "〰"
            textSize = 30f
            setTextColor(neon(R.color.neon_green))
            gravity = Gravity.CENTER
        }
        emptyTitle = TextView(this).apply {
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(neon(R.color.white))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }
        emptyDesc = TextView(this).apply {
            textSize = 13f
            setTextColor(neon(R.color.neon_green_dim))
            gravity = Gravity.CENTER
        }
        emptyContainer.addView(emptyIcon)
        emptyContainer.addView(emptyTitle)
        emptyContainer.addView(emptyDesc)
        column.addView(emptyContainer)

        val fab = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            setOnClickListener { showAddManualLocationDialog() }
        }
        val fabParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            marginEnd = dp(20)
            bottomMargin = dp(24)
        }
        root.addView(fab, fabParams)

        return root
    }

    private fun buildHeaderRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }

        countChip = TextView(this).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setTextColor(neon(R.color.neon_green))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(neon(R.color.neon_card_bg))
                setStroke(dp(1), neon(R.color.neon_border_teal))
            }
        }
        row.addView(countChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val spacer = View(this)
        row.addView(spacer, LinearLayout.LayoutParams(0, 0, 1f))

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { finish() }
        }
        val backArrow = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_rew)
            setColorFilter(neon(R.color.neon_green))
        }
        val title = TextView(this).apply {
            text = getString(R.string.saved_items_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(neon(R.color.neon_green))
            setPadding(dp(8), 0, dp(8), 0)
        }
        titleRow.addView(title)
        titleRow.addView(backArrow)
        row.addView(titleRow)

        return row
    }

    private fun buildTabsRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(12))
        }

        tabPlaces = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { selectTab(places = true) }
        }
        tabTracks = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { selectTab(places = false) }
        }

        // ترتيب الإضافة يتبع اتجاه RTL تلقائياً (المحتوى المُضاف أولاً يظهر في جهة البداية/اليمين)،
        // مطابقاً لِما في لقطة الشاشة الفعلية: تبويب "المواقع" على اليمين، و"المسارات" على اليسار.
        row.addView(tabPlaces, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        row.addView(tabTracks, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })

        return row
    }

    private fun pillBackground(active: Boolean): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            if (active) {
                setColor(neon(R.color.neon_green_dim))
            } else {
                setColor(neon(R.color.neon_card_bg))
            }
        }

    private fun selectTab(places: Boolean) {
        showingPlaces = places
        refreshList()
    }

    // ===================== تحديث المحتوى =====================

    private fun refreshList() {
        val places = placesStore.list()
        val tracks = tracksStore.list()

        tabPlaces.text = getString(R.string.tab_places_format, places.size)
        tabTracks.text = getString(R.string.tab_tracks_format, tracks.size)
        tabPlaces.background = pillBackground(showingPlaces)
        tabPlaces.setTextColor(if (showingPlaces) neon(R.color.neon_bg) else neon(R.color.neon_green_dim))
        tabTracks.background = pillBackground(!showingPlaces)
        tabTracks.setTextColor(if (!showingPlaces) neon(R.color.neon_bg) else neon(R.color.neon_green_dim))

        countChip.text = if (showingPlaces) {
            getString(R.string.count_chip_places, places.size)
        } else {
            getString(R.string.count_chip_tracks, tracks.size)
        }

        listContainer.removeAllViews()

        if (showingPlaces) {
            if (places.isEmpty()) {
                showEmptyState(getString(R.string.no_saved_places_title), getString(R.string.no_saved_places_desc))
            } else {
                hideEmptyState()
                places.forEach { place -> listContainer.addView(buildPlaceCard(place)) }
            }
        } else {
            if (tracks.isEmpty()) {
                showEmptyState(getString(R.string.no_saved_tracks_title), getString(R.string.no_saved_tracks_desc))
            } else {
                hideEmptyState()
                tracks.forEach { track -> listContainer.addView(buildTrackCard(track)) }
            }
        }
    }

    private fun showEmptyState(title: String, desc: String) {
        scrollView.visibility = View.GONE
        emptyContainer.visibility = View.VISIBLE
        emptyTitle.text = title
        emptyDesc.text = desc
    }

    private fun hideEmptyState() {
        emptyContainer.visibility = View.GONE
        scrollView.visibility = View.VISIBLE
    }

    private fun cardBackground(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(neon(R.color.neon_card_bg))
        }

    private fun pinBadge(): ImageView = ImageView(this).apply {
        setImageResource(android.R.drawable.ic_menu_mylocation)
        setColorFilter(neon(R.color.neon_bg))
        val size = dp(44)
        layoutParams = LinearLayout.LayoutParams(size, size)
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(neon(R.color.neon_green_dim))
        }
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    // ----- بطاقة موقع محفوظ -----

    private fun buildPlaceCard(place: SavedPlacesStore.SavedPlace): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = cardBackground()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        // عمود الأزرار (⋮ ثم القلب) على الجهة المقابلة للدبوس، كما في الصورة المرجعية بالضبط.
        val actionsCol = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val menuBtn = ImageButton(this).apply {
            setImageResource(com.alruwaili.garminalbarr.R.drawable.ic_menu_hamburger)
            background = null
            setColorFilter(neon(R.color.neon_green_dim))
            setOnClickListener { showPlaceMenu(it, place) }
        }
        val heartBtn = ImageButton(this).apply {
            setImageResource(if (place.favorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
            background = null
            setColorFilter(if (place.favorite) neon(R.color.neon_green_bright) else neon(R.color.neon_green_dim))
            setOnClickListener {
                placesStore.toggleFavorite(place.id)
                refreshList()
            }
        }
        actionsCol.addView(menuBtn)
        actionsCol.addView(heartBtn)
        card.addView(actionsCol)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10); marginEnd = dp(10)
            }
        }
        textCol.addView(TextView(this).apply {
            text = place.name
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(neon(R.color.white))
            gravity = Gravity.END
        })
        val distanceText = userLocation?.let { GeoMath.formatDistance(GeoMath.distanceMeters(it, place.latLng)) }
        textCol.addView(TextView(this).apply {
            text = if (distanceText != null) getString(R.string.distance_to_point, distanceText) else coordsText(place.latLng)
            textSize = 13f
            setTextColor(neon(R.color.neon_green))
            gravity = Gravity.END
        })
        if (distanceText != null) {
            textCol.addView(TextView(this).apply {
                text = coordsText(place.latLng)
                textSize = 11f
                setTextColor(neon(R.color.neon_green_dim))
                gravity = Gravity.END
            })
        }
        card.addView(textCol)

        card.addView(pinBadge())

        card.setOnClickListener {
            mapLibreGoTo(place.latLng)
        }
        return card
    }

    private fun coordsText(latLng: LatLng): String =
        "N, %.5f°  E %.5f°".format(java.util.Locale.US, latLng.latitude, latLng.longitude)

    private fun showPlaceMenu(anchor: View, place: SavedPlacesStore.SavedPlace) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.menu_go_to_map)
        popup.menu.add(0, 2, 1, R.string.menu_share)
        popup.menu.add(0, 3, 2, R.string.menu_delete_location)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { mapLibreGoTo(place.latLng); true }
                2 -> { sharePlace(place); true }
                3 -> { confirmDeletePlace(place); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun sharePlace(place: SavedPlacesStore.SavedPlace) {
        val text = getString(
            R.string.share_place_text, place.name,
            place.latLng.latitude.toString(), place.latLng.longitude.toString()
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.menu_share)))
    }

    private fun confirmDeletePlace(place: SavedPlacesStore.SavedPlace) {
        AlertDialog.Builder(this, R.style.Theme_DalilAlBar_NeonDialog)
            .setTitle(R.string.menu_delete_location)
            .setMessage(place.name)
            .setPositiveButton(R.string.delete) { _, _ ->
                placesStore.delete(place.id)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ----- بطاقة مسار مسجَّل -----

    private fun buildTrackCard(track: SavedTracksStore.SavedTrack): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = cardBackground()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val menuBtn = ImageButton(this).apply {
            setImageResource(com.alruwaili.garminalbarr.R.drawable.ic_menu_hamburger)
            background = null
            setColorFilter(neon(R.color.neon_green_dim))
            setOnClickListener { showTrackMenu(it, track) }
        }
        card.addView(menuBtn)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10); marginEnd = dp(10)
            }
        }
        textCol.addView(TextView(this).apply {
            text = track.name
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(neon(R.color.white))
            gravity = Gravity.END
        })
        val points = tracksStore.readPoints(track.file)
        val distance = trackDistanceMeters(points)
        textCol.addView(TextView(this).apply {
            text = getString(R.string.distance_to_point, GeoMath.formatDistance(distance))
            textSize = 13f
            setTextColor(neon(R.color.neon_green))
            gravity = Gravity.END
        })
        textCol.addView(TextView(this).apply {
            text = if (track.visible) getString(R.string.menu_show_on_map) else getString(R.string.menu_hide_from_map)
            textSize = 11f
            setTextColor(neon(R.color.neon_green_dim))
            gravity = Gravity.END
        })
        card.addView(textCol)

        val pin = pinBadge().apply { setImageResource(android.R.drawable.ic_menu_directions) }
        card.addView(pin)

        card.setOnClickListener {
            points.firstOrNull()?.let { mapLibreGoTo(it, zoom = 13.0) }
        }
        return card
    }

    private fun trackDistanceMeters(points: List<LatLng>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            total += GeoMath.distanceMeters(points[i - 1], points[i])
        }
        return total
    }

    private fun showTrackMenu(anchor: View, track: SavedTracksStore.SavedTrack) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.menu_delete_location).setTitle(getString(R.string.delete))
        popup.menu.add(0, 2, 1, R.string.menu_share)
        popup.menu.add(0, 3, 2, R.string.menu_show_on_map)
        popup.menu.add(0, 4, 3, R.string.menu_hide_from_map)
        popup.menu.add(0, 5, 4, R.string.menu_go_to)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { confirmDeleteTrack(track); true }
                2 -> { shareTrack(track); true }
                3 -> { tracksStore.setVisible(track.file.name, true); refreshSavedTracksLayerExternally(); refreshList(); true }
                4 -> { tracksStore.setVisible(track.file.name, false); refreshSavedTracksLayerExternally(); refreshList(); true }
                5 -> {
                    tracksStore.readPoints(track.file).firstOrNull()?.let { mapLibreGoTo(it, zoom = 13.0) }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun shareTrack(track: SavedTracksStore.SavedTrack) {
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", track.file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.menu_share)))
        } catch (e: Exception) {
            // تعذّر إنشاء Uri مشاركة لسبب ما (نادر): لا نُسقط الشاشة، فقط لا تتم المشاركة.
        }
    }

    private fun confirmDeleteTrack(track: SavedTracksStore.SavedTrack) {
        AlertDialog.Builder(this, R.style.Theme_DalilAlBar_NeonDialog)
            .setTitle(R.string.delete)
            .setMessage(track.name)
            .setPositiveButton(R.string.delete) { _, _ ->
                tracksStore.delete(track.file)
                refreshSavedTracksLayerExternally()
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ===================== إضافة موقع يدوياً =====================

    private fun showAddManualLocationDialog() {
        val base = userLocation
        if (base == null) {
            android.widget.Toast.makeText(this, R.string.need_user_location, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val input = android.widget.EditText(this).apply { hint = getString(R.string.save_location_name_hint) }
        AlertDialog.Builder(this, R.style.Theme_DalilAlBar_NeonDialog)
            .setTitle(R.string.add_manual_location)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                placesStore.add(name, base)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ===================== أثر هذه الشاشة على الخريطة الفعلية =====================
    // لا تملك هذه الشاشة مرجعاً مباشراً لكائن MapLibreMap (هو في MainActivity)، فننهي الشاشة ونعيد
    // النتيجة (الوجهة المطلوب الذهاب إليها) إلى MainActivity عبر setResult، بدل محاولة التحكم بالخريطة
    // من نشاط مختلف تماماً — وهذا هو الأسلوب الصحيح في أندرويد، وليس تخميناً.

    private fun mapLibreGoTo(target: LatLng, zoom: Double = 14.0) {
        val result = Intent().apply {
            putExtra("go_to_lat", target.latitude)
            putExtra("go_to_lon", target.longitude)
            putExtra("go_to_zoom", zoom)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    /** تحديث طبقة المسارات المرئية في الخريطة الفعلية يحدث في MainActivity عند استئنافها (onResume)
     * لأن هذا النشاط لا يملك وصولاً مباشراً لـStyle الخريطة؛ هذا الاستدعاء هنا بلا تأثير مباشر عمداً
     * ومجرد توثيق للنيّة، والتحديث الفعلي يتم عبر إعادة قراءة SavedTracksStore في onResume بـMainActivity. */
    private fun refreshSavedTracksLayerExternally() { /* انظر التعليق أعلاه: التحديث الفعلي في MainActivity.onResume */ }
}
