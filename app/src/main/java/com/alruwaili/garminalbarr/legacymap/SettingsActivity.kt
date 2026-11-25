package com.alruwaili.garminalbarr.legacymap

import com.alruwaili.garminalbarr.R

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alruwaili.garminalbarr.legacymap.nav.RoadNetworkCache
import com.alruwaili.garminalbarr.legacymap.nav.TerrainTileCache
import org.maplibre.android.offline.OfflineRegion

/**
 * شاشة "إدارة الخرائط" — أُعيدت كتابتها بالكامل لتطابق تصميم شاشة الخرائط في «الدليلة بلس»
 * المرجعية: بطاقة لكل دولة/منطقة (علم + اسم عربي—إنجليزي + حجم حقيقي + زر تحميل/حذف)، بنفس
 * التقسيم الحرفي الذي طلبه المستخدم من قائمة الخرائط في تطبيق «خرائط الصحراء الدليلة»: لكل دولة
 * ثلاث فئات — خريطة عادية، تضاريس، مسار السيارة.
 *
 * تحديث (٢٥ نوفمبر): أُضيف تبويب "تضاريس" حقيقي (TERRAIN) — تنزيل فعلي لبلاطات ارتفاع AWS
 * Terrarium عبر [TerrainTileCache]/[OfflineRegionsController.downloadTerrain]، وليس تبويباً
 * مزيَّفاً. تنبيه صدق: لا تُحمَّل كل مستويات التكبير (حتى 15)، بل فقط 6–10 (انظر التعليق على
 * TERRAIN_MIN_ZOOM/TERRAIN_MAX_ZOOM في OfflineRegionsController) تفادياً لحجم تنزيل غير عملي
 * لمساحة دولة كاملة؛ عند تكبير أعلى من ذلك دون اتصال، قد لا تظهر طبقة التظليل في تلك البقعة.
 * تبويب "ثلاثي الأبعاد" وحده لا يزال غير موجود هنا فعلياً (لا علاقة له بهذه الشاشة أصلاً حسب
 * طلب المستخدم الصريح في تطبيق «الدليلة بلس» — زر 3D مستقل تماماً عن قائمة إدارة الخرائط).
 *
 * حجم بلاطات الخريطة (تبويب "خريطة") غير معروف مسبقاً قبل اكتمال التحميل فعلياً (لا توفّر مكتبة
 * MapLibre تقديراً مسبقاً لحجم منطقة Offline)، فنعرض له دائماً نص "الحجم يُحدَّد أثناء التحميل"
 * بدل اختراع رقم. أما حجم بيانات مسار السيارة والتضاريس فحجم حقيقي فعلي (RoadNetworkCache./
 * TerrainTileCache.cachedSizeMb — قراءة حجم ملفات فعلية على القرص)، وليس تقديراً.
 */
class SettingsActivity : AppCompatActivity() {

    private enum class Tab { MAP_TILES, TERRAIN, ROAD_NETWORK }

    private lateinit var offlineController: OfflineRegionsController
    private lateinit var tabMapTiles: Button
    private lateinit var tabTerrain: Button
    private lateinit var tabRoadNetwork: Button
    private lateinit var cardsContainer: LinearLayout

    private var activeTab: Tab = Tab.MAP_TILES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)

        offlineController = OfflineRegionsController(applicationContext)

        tabMapTiles = findViewById(R.id.tabMapTiles)
        tabTerrain = findViewById(R.id.tabTerrain)
        tabRoadNetwork = findViewById(R.id.tabRoadNetwork)
        cardsContainer = findViewById(R.id.regionCardsContainer)

        tabMapTiles.setOnClickListener { switchTab(Tab.MAP_TILES) }
        tabTerrain.setOnClickListener { switchTab(Tab.TERRAIN) }
        tabRoadNetwork.setOnClickListener { switchTab(Tab.ROAD_NETWORK) }

        switchTab(Tab.MAP_TILES)
    }

    private fun switchTab(tab: Tab) {
        activeTab = tab
        // التبويب النشط: خلفية bg_neon_tab_active المرجعية (حدّ أخضر). غير النشط: بطاقة محايدة
        // bg_neon_card الموجودة فعلاً في التطبيق — وليس رسماً جديداً مُفبركاً لهذه الحالة.
        tabMapTiles.setBackgroundResource(
            if (tab == Tab.MAP_TILES) R.drawable.bg_neon_tab_active else R.drawable.bg_neon_card
        )
        tabTerrain.setBackgroundResource(
            if (tab == Tab.TERRAIN) R.drawable.bg_neon_tab_active else R.drawable.bg_neon_card
        )
        tabRoadNetwork.setBackgroundResource(
            if (tab == Tab.ROAD_NETWORK) R.drawable.bg_neon_tab_active else R.drawable.bg_neon_card
        )
        renderCards()
    }

    private fun renderCards() {
        when (activeTab) {
            Tab.MAP_TILES -> offlineController.listDownloadedRegions { pairs ->
                runOnUiThread { renderMapTilesCards(pairs) }
            }
            Tab.TERRAIN -> renderTerrainCards()
            Tab.ROAD_NETWORK -> renderRoadNetworkCards()
        }
    }

    private fun renderMapTilesCards(downloaded: List<Pair<String, OfflineRegion>>) {
        cardsContainer.removeAllViews()
        val byId = downloaded.toMap()
        OfflineRegionDef.entries.forEach { def ->
            val region = byId[def.id]
            addRegionCard(
                def = def,
                sizeText = getString(R.string.size_unknown),
                isDownloaded = region != null,
                onDownloadClick = { startMapTileDownload(def) },
                onDeleteClick = { region?.let { deleteMapTile(def, it) } }
            )
        }
    }

    private fun renderRoadNetworkCards() {
        cardsContainer.removeAllViews()
        OfflineRegionDef.entries.forEach { def ->
            val cached = RoadNetworkCache.isCached(this, def.id)
            val sizeMb = if (cached) RoadNetworkCache.cachedSizeMb(this, def.id) else null
            val sizeText = if (cached && sizeMb != null) {
                getString(R.string.size_mb_format, sizeMb)
            } else {
                getString(R.string.size_unknown)
            }
            addRegionCard(
                def = def,
                sizeText = sizeText,
                isDownloaded = cached,
                onDownloadClick = { startRoadNetworkDownload(def) },
                onDeleteClick = { deleteRoadNetworkCache(def) }
            )
        }
    }

    /** بطاقة واحدة بتصميم مطابق لبطاقات الشاشة المرجعية: علم + اسم عربي—إنجليزي + سطر الحجم + زر. */
    private fun addRegionCard(
        def: OfflineRegionDef,
        sizeText: String,
        isDownloaded: Boolean,
        onDownloadClick: () -> Unit,
        onDeleteClick: () -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_neon_card)
            setPadding(16, 14, 16, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10 }
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameLine = TextView(this).apply {
            text = "${def.flagEmoji}  ${def.arabicName} — ${def.englishName}"
            textSize = 15f
            setTextColor(resources.getColor(R.color.neon_green, theme))
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        }
        val sizeLine = TextView(this).apply {
            text = sizeText
            textSize = 12f
            setTextColor(resources.getColor(R.color.neon_green_dim, theme))
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 4, 0, 0)
        }
        textColumn.addView(nameLine)
        textColumn.addView(sizeLine)

        val actionBtn = Button(this, null, 0, R.style.Widget_DalilAlBar_NeonDialogButton).apply {
            text = if (isDownloaded) getString(R.string.delete) else getString(R.string.action_download)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // سطر تشخيص مؤقت (سيُحذف بعد تحديد سبب عدم عمل التحميل): يؤكد وصول الضغطة فعلاً
                // لمستمع الزر نفسه قبل أي منطق آخر، كي نعرف أين بالضبط ينقطع التنفيذ.
                Toast.makeText(
                    this@SettingsActivity,
                    "[تشخيص] ضُغِط: ${def.arabicName} — ${if (isDownloaded) "حذف" else "تحميل"} — تبويب $activeTab",
                    Toast.LENGTH_SHORT
                ).show()
                if (isDownloaded) onDeleteClick() else onDownloadClick()
            }
        }

        card.addView(textColumn)
        card.addView(actionBtn)
        cardsContainer.addView(card)
    }

    // ---------- تبويب "خريطة" (بلاطات MapLibre) ----------

    private fun startMapTileDownload(def: OfflineRegionDef) {
      try {
        val dialogView = layoutInflater.inflate(R.layout.dialog_download_progress, null)
        val tvName = dialogView.findViewById<TextView>(R.id.tvDownloadRegionName)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBarDownload)
        val tvPercent = dialogView.findViewById<TextView>(R.id.tvDownloadPercent)
        val btnPause = dialogView.findViewById<Button>(R.id.btnDownloadPause)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnDownloadCancel)
        val btnBackground = dialogView.findViewById<Button>(R.id.btnDownloadBackground)
        tvName.text = def.arabicName

        var currentRegion: OfflineRegion? = null
        var isPaused = false

        val dialog = AlertDialog.Builder(this, R.style.Theme_DalilAlBar_NeonDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnPause.setOnClickListener {
            val region = currentRegion ?: return@setOnClickListener
            isPaused = !isPaused
            if (isPaused) {
                offlineController.pauseDownload(region)
                btnPause.text = getString(R.string.download_resume)
            } else {
                offlineController.resumeDownload(region)
                btnPause.text = getString(R.string.download_pause)
            }
        }
        btnCancel.setOnClickListener {
            val region = currentRegion
            dialog.dismiss()
            if (region != null) {
                offlineController.cancelDownload(region) { renderCards() }
            } else {
                renderCards()
            }
            Toast.makeText(this, getString(R.string.download_cancelled, def.arabicName), Toast.LENGTH_SHORT).show()
        }
        btnBackground.setOnClickListener {
            // لا يوقف ولا يُلغي شيئاً — فقط يُخفي النافذة؛ التحميل يتابع فعلياً في الخلفية.
            dialog.dismiss()
        }

        dialog.show()

        offlineController.startDownload(
            def,
            object : OfflineRegionsController.ProgressListener {
                override fun onProgress(d: OfflineRegionDef, percent: Int, region: OfflineRegion) {
                    currentRegion = region
                    progressBar.progress = percent
                    tvPercent.text = "$percent%"
                }

                override fun onComplete(d: OfflineRegionDef) {
                    if (dialog.isShowing) dialog.dismiss()
                    Toast.makeText(this@SettingsActivity, "تم تحميل خريطة ${d.arabicName}", Toast.LENGTH_SHORT).show()
                    renderCards()
                }

                override fun onDownloadError(d: OfflineRegionDef, message: String) {
                    if (dialog.isShowing) dialog.dismiss()
                    Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                    renderCards()
                }
            }
        )
      } catch (e: Exception) {
          Toast.makeText(this, "[تشخيص] استثناء في startMapTileDownload: ${e.javaClass.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
      }
    }

    private fun deleteMapTile(def: OfflineRegionDef, region: OfflineRegion) {
        offlineController.deleteRegion(region) { success ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, getString(R.string.map_deleted, def.arabicName), Toast.LENGTH_SHORT).show()
                    renderCards()
                }
            }
        }
    }

    // ---------- تبويب "تضاريس" (بلاطات ارتفاع AWS Terrarium المحفوظة محلياً) ----------

    private fun renderTerrainCards() {
        cardsContainer.removeAllViews()
        OfflineRegionDef.entries.forEach { def ->
            val cached = TerrainTileCache.isCached(this, def.id)
            val sizeMb = if (cached) TerrainTileCache.cachedSizeMb(this, def.id) else null
            val sizeText = if (cached && sizeMb != null) {
                getString(R.string.size_mb_format, sizeMb)
            } else {
                getString(R.string.size_unknown)
            }
            addRegionCard(
                def = def,
                sizeText = sizeText,
                isDownloaded = cached,
                onDownloadClick = { startTerrainDownload(def) },
                onDeleteClick = { deleteTerrainCache(def) }
            )
        }
    }

    private fun startTerrainDownload(def: OfflineRegionDef) {
        // تشخيص مؤقت: لف الدالة كلها بـ try/catch كي تظهر أي استثناء كرسالة واضحة بدل أن يختفي
        // بصمت (أو يتسبب بتوقف غير مُفسَّر). سيُحذف هذا بعد تحديد سبب "لا شيء يعمل أبداً".
        try {
            val dialogView = layoutInflater.inflate(R.layout.dialog_download_progress, null)
            val tvName = dialogView.findViewById<TextView>(R.id.tvDownloadRegionName)
            val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBarDownload)
            val tvPercent = dialogView.findViewById<TextView>(R.id.tvDownloadPercent)
            val btnPause = dialogView.findViewById<Button>(R.id.btnDownloadPause)
            val btnCancel = dialogView.findViewById<Button>(R.id.btnDownloadCancel)
            val btnBackground = dialogView.findViewById<Button>(R.id.btnDownloadBackground)
            tvName.text = "تضاريس: ${def.arabicName}"
            btnPause.visibility = View.GONE // لا يوجد استئناف جزئي حقيقي لهذا المسار، كما في طرق السيارة

            var cancelled = false
            val dialog = AlertDialog.Builder(this, R.style.Theme_DalilAlBar_NeonDialog)
                .setView(dialogView)
                .setCancelable(false)
                .create()
            btnCancel.setOnClickListener {
                // كما في تحميل طرق السيارة: لا يوقف اتصالات الشبكة الجارية فعلياً فوراً، فقط يُخفي
                // الواجهة ويتجاهل نتيجتها — صدقاً دون ادّعاء إلغاء شبكي فوري غير موجود فعلاً.
                cancelled = true
                dialog.dismiss()
                renderCards()
            }
            btnBackground.setOnClickListener { dialog.dismiss() }
            dialog.show()

            offlineController.downloadTerrain(
                def,
                object : OfflineRegionsController.TerrainProgressListener {
                    override fun onProgress(d: OfflineRegionDef, percent: Int) {
                        if (cancelled) return
                        progressBar.progress = percent
                        tvPercent.text = "$percent%"
                    }

                    override fun onComplete(d: OfflineRegionDef, failedTiles: Int, totalTiles: Int) {
                        if (cancelled) return
                        if (dialog.isShowing) dialog.dismiss()
                        val msg = if (failedTiles > 0) {
                            "تم حفظ تضاريس ${d.arabicName} جزئياً (فشلت $failedTiles من $totalTiles بلاطة — بعض البقع ستعتمد على الإنترنت المباشر)"
                        } else {
                            "تم حفظ تضاريس ${d.arabicName} كاملة دون انترنت"
                        }
                        Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                        renderCards()
                    }

                    override fun onDownloadError(d: OfflineRegionDef, message: String) {
                        if (cancelled) return
                        if (dialog.isShowing) dialog.dismiss()
                        Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                        renderCards()
                    }
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, "[تشخيص] استثناء في startTerrainDownload: ${e.javaClass.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun deleteTerrainCache(def: OfflineRegionDef) {
        val success = TerrainTileCache.deleteCache(this, def.id)
        if (success) {
            Toast.makeText(this, getString(R.string.map_deleted, def.arabicName), Toast.LENGTH_SHORT).show()
        }
        renderCards()
    }

    // ---------- تبويب "طرق (سيارة)" (بيانات Overpass المحفوظة محلياً) ----------

    private fun startRoadNetworkDownload(def: OfflineRegionDef) {
      try {
        val dialogView = layoutInflater.inflate(R.layout.dialog_download_progress, null)
        val tvName = dialogView.findViewById<TextView>(R.id.tvDownloadRegionName)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBarDownload)
        val tvPercent = dialogView.findViewById<TextView>(R.id.tvDownloadPercent)
        val btnPause = dialogView.findViewById<Button>(R.id.btnDownloadPause)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnDownloadCancel)
        val btnBackground = dialogView.findViewById<Button>(R.id.btnDownloadBackground)
        tvName.text = "بيانات مسار السيارة: ${def.arabicName}"
        btnPause.visibility = View.GONE // لا يوجد استئناف جزئي حقيقي لاستعلامات Overpass هنا

        var cancelled = false
        val dialog = AlertDialog.Builder(this, R.style.Theme_DalilAlBar_NeonDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        btnCancel.setOnClickListener {
            // لا يملك هذا المسار آلية حقيقية لإيقاف طلبات الشبكة الجارية فعلياً؛ هذا يوقف الواجهة
            // والاستجابة لنتيجته فقط (صدقاً، لا ندّعي إلغاءً فورياً للاتصال الشبكي نفسه).
            cancelled = true
            dialog.dismiss()
            renderCards()
        }
        btnBackground.setOnClickListener { dialog.dismiss() }
        dialog.show()

        offlineController.downloadRoadNetwork(
            def,
            object : OfflineRegionsController.RoadNetworkProgressListener {
                override fun onProgress(d: OfflineRegionDef, percent: Int) {
                    if (cancelled) return
                    progressBar.progress = percent
                    tvPercent.text = "$percent%"
                }

                override fun onComplete(d: OfflineRegionDef, failedCells: Int, totalCells: Int) {
                    if (cancelled) return
                    if (dialog.isShowing) dialog.dismiss()
                    val msg = if (failedCells > 0) {
                        "تم الحفظ جزئياً لمنطقة ${d.arabicName} (فشلت $failedCells من $totalCells خلية جغرافية — ستعتمد بعض المناطق الفرعية على الإنترنت المباشر وقت التنقّل)"
                    } else {
                        "تم حفظ بيانات مسار السيارة كاملة لمنطقة ${d.arabicName}"
                    }
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                    renderCards()
                }

                override fun onDownloadError(d: OfflineRegionDef, message: String) {
                    if (cancelled) return
                    if (dialog.isShowing) dialog.dismiss()
                    Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                    renderCards()
                }
            }
        )
      } catch (e: Exception) {
          Toast.makeText(this, "[تشخيص] استثناء في startRoadNetworkDownload: ${e.javaClass.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
      }
    }

    private fun deleteRoadNetworkCache(def: OfflineRegionDef) {
        val success = RoadNetworkCache.deleteCache(this, def.id)
        if (success) {
            Toast.makeText(this, getString(R.string.map_deleted, def.arabicName), Toast.LENGTH_SHORT).show()
        }
        renderCards()
    }
}
