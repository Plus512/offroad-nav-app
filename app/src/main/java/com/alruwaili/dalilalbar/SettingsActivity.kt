package com.alruwaili.dalilalbar

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
 * 1) إعادة إظهار قائمة كل الدول للتحميل دون انترنت (علم shown_initial_offline_list في SharedPreferences).
 * 2) قائمة الخرائط دون انترنت المحمَّلة فعلاً حالياً، مع إمكانية حذف كل واحدة (OfflineRegionsController).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var offlineController: OfflineRegionsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)

        offlineController = OfflineRegionsController(applicationContext)

        findViewById<Button>(R.id.btnShowOfflineListAgain).setOnClickListener {
            getSharedPreferences("dalilalbar_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("shown_initial_offline_list", false)
                .apply()
            Toast.makeText(this, R.string.show_offline_list_again_done, Toast.LENGTH_SHORT).show()
        }

        loadDownloadedMaps()
    }

    private fun loadDownloadedMaps() {
        val container = findViewById<LinearLayout>(R.id.downloadedMapsContainer)
        offlineController.listDownloadedRegions { regions ->
            runOnUiThread {
                container.removeAllViews()
                if (regions.isEmpty()) {
                    val empty = TextView(this).apply {
                        text = getString(R.string.no_downloaded_maps)
                        setTextColor(0xFF666666.toInt())
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
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val deleteBtn = Button(this).apply {
                        text = getString(R.string.delete)
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
