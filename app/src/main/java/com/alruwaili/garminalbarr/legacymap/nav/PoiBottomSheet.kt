package com.alruwaili.garminalbarr.legacymap.nav

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.alruwaili.garminalbarr.legacymap.CompassArrowView
import com.alruwaili.garminalbarr.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.maplibre.android.geometry.LatLng
import java.util.Locale

/** أي خانة من الخانتين يجري تعديلها حالياً (انطلاق أم وصول). */
enum class SlotType { ORIGIN, DESTINATION }

/**
 * خانة طرف واحد من التوجيه (انطلاق أو وصول) — تقابل RouteSlot في PoiBottomSheet.kt الأصلية
 * (Compose) من تطبيق «خرائط الصحراء الدليلة»، بنفس الحقول بالضبط.
 */
data class RouteSlot(
    val title: String,
    val latLng: LatLng,
    val isCurrentLocation: Boolean = false
)

/**
 * شاشة التوجيه بخانتين (من هنا / إلى هنا) — نقل حرفي لبنية ومزايا PoiBottomSheet.kt (844 سطر
 * Jetpack Compose) من تطبيق «خرائط الصحراء الدليلة» إلى XML تقليدي يطابق بنية دليل البر نفسها
 * (BottomSheetDialog من Material Components، وهي متوفرة أصلاً في build.gradle دون أي اعتماد
 * جديد)، بنفس المزايا: خانتا انطلاق/وصول قابلتان للتبديل والتغيير (موقعي الحالي أو الاختيار من
 * الخريطة)، سهم بوصلة مصغّر حي (بإعادة استخدام CompassArrowView نفسها بدل ترحيل MiniTargetArrow
 * من Compose)، أزرار حفظ/نسخ/مشاركة، بطاقة اتجاه حية (بإعادة استخدام GeoMath نفسها)، وزرا مسار
 * مستقيم/سيارة بمسافة حية — لكن بألوان وأنماط دليل البر النيون الخضراء الحالية بدل لوحة "الصحراء"
 * الذهبية الأصلية، لأن المستخدم اختار صراحةً إبقاء هوية دليل البر البصرية ("افعل الأسهل والمنطقي").
 *
 * هذا الصف واجهة عرض بحتة فقط: لا يتعامل مباشرة مع حساسات الجهاز أو حالة الخريطة في MainActivity؛
 * كل تفاعل يمرَّر عبر دوال رد نداء (callbacks) كي تبقى MainActivity هي المصدر الوحيد للحالة
 * (الخانتان الحاليتان، وضع "بانتظار اختيار من الخريطة")، فلا يُخمَّن أي سلوك لملف لم يُعدَّل بعد.
 */
class PoiBottomSheet(
    private val activity: Activity,
    private var origin: RouteSlot,
    private var destination: RouteSlot,
    categoryLabel: String,
    initialHeadingDegrees: Float,
    /** المستخدم اختار "اختيار من الخريطة" لهذه الخانة — الشاشة تُغلَق فوراً كي يضغط على الخريطة. */
    private val onPickFromMap: (SlotType) -> Unit,
    /** المستخدم اختار "موقعي الحالي" لهذه الخانة — الشاشة تبقى مفتوحة، والمستدعي يُحدِّث الخانة
     *  عبر updateSlot() فوراً بعد هذا النداء إن توفَّر موقع حالي. */
    private val onUseCurrentLocation: (SlotType) -> Unit,
    private val onSwap: (origin: RouteSlot, destination: RouteSlot) -> Unit,
    private val onSave: (RouteSlot) -> Unit,
    private val onShare: (RouteSlot) -> Unit,
    private val onNavigate: (origin: RouteSlot, destination: RouteSlot, mode: NavMode) -> Unit,
    /** يُستدعى عند إغلاق الشاشة بأي طريقة (زر، سحب للأسفل، أو dismiss() برمجياً) — كي يُسقِط
     *  المستدعي مرجعه المحفوظ إليها (مثل activeBottomSheet في MainActivity) ولا يبقى معلَّقاً. */
    private val onDismissed: () -> Unit = {}
) {
    private val dialog = BottomSheetDialog(activity)
    private val sheetView = activity.layoutInflater.inflate(R.layout.bottom_sheet_poi, null)

    private val miniArrow: CompassArrowView = sheetView.findViewById(R.id.miniArrow)
    private val tvTitle: TextView = sheetView.findViewById(R.id.tvPoiTitle)
    private val tvOrigin: TextView = sheetView.findViewById(R.id.tvOriginTitle)
    private val tvDestination: TextView = sheetView.findViewById(R.id.tvDestinationTitle)
    private val tvBearing: TextView = sheetView.findViewById(R.id.tvBearingValue)
    private val btnNavStraight: Button = sheetView.findViewById(R.id.btnNavStraight)
    private val btnNavCar: Button = sheetView.findViewById(R.id.btnNavCar)

    init {
        dialog.setContentView(sheetView)
        dialog.setOnDismissListener { onDismissed() }

        tvTitle.text = if (categoryLabel.isBlank()) destination.title else "$categoryLabel: ${destination.title}"
        miniArrow.headingDegrees = initialHeadingDegrees
        miniArrow.pointsToDestination = true

        sheetView.findViewById<Button>(R.id.btnPoiSave).setOnClickListener { onSave(destination) }
        sheetView.findViewById<Button>(R.id.btnPoiCopy).setOnClickListener { copyCoords(destination) }
        sheetView.findViewById<Button>(R.id.btnPoiShare).setOnClickListener { onShare(destination) }

        sheetView.findViewById<Button>(R.id.btnOriginChange).setOnClickListener { showPickChoice(SlotType.ORIGIN) }
        sheetView.findViewById<Button>(R.id.btnDestinationChange).setOnClickListener { showPickChoice(SlotType.DESTINATION) }

        sheetView.findViewById<Button>(R.id.btnSwapSlots).setOnClickListener {
            val newOrigin = destination
            val newDestination = origin
            origin = newOrigin
            destination = newDestination
            refreshAll()
            onSwap(origin, destination)
        }

        btnNavStraight.setOnClickListener {
            dialog.dismiss()
            onNavigate(origin, destination, NavMode.STRAIGHT)
        }
        btnNavCar.setOnClickListener {
            dialog.dismiss()
            onNavigate(origin, destination, NavMode.CAR)
        }

        refreshAll()
    }

    fun show() { if (!dialog.isShowing) dialog.show() }
    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }

    /** يحدِّث زاوية السهم المصغّر حياً أثناء بقاء الشاشة مفتوحة (يُستدعى من مستمع الحساس في MainActivity). */
    fun updateHeading(bearingDegrees: Float) {
        miniArrow.headingDegrees = bearingDegrees
    }

    /** يحدِّث إحدى الخانتين دون إغلاق الشاشة (مسار "موقعي الحالي" بعد تحديد الموقع الحي). */
    fun updateSlot(slot: SlotType, newSlot: RouteSlot) {
        when (slot) {
            SlotType.ORIGIN -> origin = newSlot
            SlotType.DESTINATION -> destination = newSlot
        }
        refreshAll()
    }

    private fun refreshAll() {
        tvOrigin.text = origin.title
        tvDestination.text = destination.title

        val distance = GeoMath.distanceMeters(origin.latLng, destination.latLng)
        val bearing = GeoMath.bearingDegrees(origin.latLng, destination.latLng)
        val label = GeoMath.compassLabelArabic(bearing)
        tvBearing.text = "$label (${Math.round(bearing)}°)"

        val distanceText = GeoMath.formatDistance(distance)
        btnNavStraight.text = "${activity.getString(R.string.mode_straight)} — $distanceText"
        btnNavCar.text = "${activity.getString(R.string.mode_car)} — $distanceText"
    }

    private fun copyCoords(slot: RouteSlot) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = String.format(Locale.US, "%.6f, %.6f", slot.latLng.latitude, slot.latLng.longitude)
        clipboard.setPrimaryClip(ClipData.newPlainText(slot.title, text))
        Toast.makeText(activity, activity.getString(R.string.coords_copied), Toast.LENGTH_SHORT).show()
    }

    private fun showPickChoice(slot: SlotType) {
        val slotLabel = activity.getString(
            if (slot == SlotType.ORIGIN) R.string.slot_origin_label else R.string.slot_destination_label
        )
        AlertDialog.Builder(activity, R.style.Theme_DalilAlBar_NeonDialog)
            .setTitle(activity.getString(R.string.pick_slot_title, slotLabel))
            .setItems(
                arrayOf(
                    activity.getString(R.string.current_location_label),
                    activity.getString(R.string.pick_from_map)
                )
            ) { _, which ->
                if (which == 1) {
                    dismiss()
                    onPickFromMap(slot)
                } else {
                    onUseCurrentLocation(slot)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
