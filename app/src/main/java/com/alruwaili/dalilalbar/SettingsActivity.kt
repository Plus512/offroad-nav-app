package com.alruwaili.dalilalbar

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * شاشة الإعدادات: تُفتح من زر القائمة (fabMenu) أسفل يسار الشاشة الرئيسية.
 * تعرض فقط إعدادات حقيقية موجودة فعلاً في منطق التطبيق (لا إعدادات مُتخيَّلة):
 * قائمة الخرائط دون انترنت المحمَّلة فعلاً حالياً، مع إمكانية حذف كل واحدة (OfflineRegionsController).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var offlineController: OfflineRegionsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)

        offlineController = OfflineRegionsController(applicationContext)

        loadDownloadedMaps()

        // طُلب صراحةً (٢٣ نوفمبر): لم يكن هناك أي زر لإعادة محاولة التحميل يدوياً من هذه الشاشة —
        // فقط عرض/حذف لما تم تحميله فعلاً. هذا الزر يعيد فتح نافذة الاختيار نفسها في MainActivity
        // (تحميل الخريطة / تحميل بيانات مسار السيارة) لكل المناطق المعرَّفة، وليس فقط منطقة GPS
        // الحالية. CLEAR_TOP تعيد استخدام نسخة MainActivity الموجودة فعلاً أسفل هذه الشاشة في نفس
        // المهمة (task) وتُسلّمها الـIntent عبر onNewIntent، بدل إنشاء نسخة جديدة.
        findViewById<Button>(R.id.btnDownloadNewMap).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SHOW_DOWNLOAD_PICKER, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            finish()
        }
    }

    private fun loadDownloadedMaps() {
        val container = findViewById<LinearLayout>(R.id.downloadedMapsContainer)
        offlineController.listDownloadedRegions { regions ->
            runOnUiThread {
                container.removeAllViews()
                if (regions.isEmpty()) {
                    val empty = TextView(this).apply {
                        text = getString(R.string.no_downloaded_maps)
                        setTextColor(resources.getColor(R.color.neon_green_dim, theme))
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                    container.addView(empty)
                    return@runOnUiThread
                }
                regions.forEach { (id, region) ->
                    val def = OfflineRegionDef.byId(id)
                    val name = def?.arabicName ?: id
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 8, 0, 8)
                    }
                    val label = TextView(this).apply {
                        text = name
                        textSize = 15f
                        setTextColor(resources.getColor(R.color.neon_green, theme))
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val deleteBtn = Button(this, null, 0, R.style.Widget_DalilAlBar_NeonDialogButton).apply {
                        text = getString(R.string.delete)
                        setBackgroundResource(R.drawable.bg_neon_tab_active)
                        setOnClickListener {
                            offlineController.deleteRegion(region) { success ->
                                runOnUiThread {
                                    if (success) {
                                        Toast.makeText(
                                            this@SettingsActivity,
                                            getString(R.string.map_deleted, name),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        loadDownloadedMaps()
                                    }
                                }
                            }
                        }
                    }
                    row.addView(label)
                    row.addView(deleteBtn)
                    container.addView(row)
                }
            }
        }
    }
}
