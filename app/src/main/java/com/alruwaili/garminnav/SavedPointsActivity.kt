package com.alruwaili.garminnav

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Date

/**
 * قائمة النقاط المحفوظة (الفكرة مأخوذة من شاشة MyLocationsActivity في قارمن،
 * شيفرة أصلية بالكامل): الضغط على نقطة يُرجع إحداثياتها للشاشة الرئيسية
 * لتتوسّط الخريطة عليها، وأيقونة السلة تحذفها.
 */
class SavedPointsActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(GTheme.BEZEL)
        root.setPadding(dp(24), dp(30), dp(24), dp(16))

        val title = TextView(this)
        title.text = "النقاط المحفوظة"
        title.textDirection = View.TEXT_DIRECTION_RTL
        title.textSize = 22f
        title.setTextColor(GTheme.TEXT)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, dp(20))
        root.addView(title)

        emptyText = TextView(this)
        emptyText.text = "لا توجد نقاط محفوظة بعد — اضغط زر «📍 علامة» في الشاشة الرئيسية لحفظ موقعك الحالي"
        emptyText.textDirection = View.TEXT_DIRECTION_RTL
        emptyText.textSize = 15f
        emptyText.setTextColor(GTheme.MUTED)
        emptyText.gravity = Gravity.CENTER
        emptyText.visibility = View.GONE
        root.addView(emptyText)

        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun reload() {
        listBox.removeAllViews()
        val points = SavedData.listPoints(this)
        emptyText.visibility = if (points.isEmpty()) View.VISIBLE else View.GONE
        for (p in points) listBox.addView(pointRow(p))
    }

    private fun pointRow(p: SavedPoint): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutDirection = View.LAYOUT_DIRECTION_RTL
        row.setPadding(dp(14), dp(16), dp(14), dp(16))
        row.setBackgroundColor(GTheme.BAR_BG)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(6)
        row.layoutParams = lp

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val nameTv = TextView(this)
        nameTv.text = p.name
        nameTv.textDirection = View.TEXT_DIRECTION_RTL
        nameTv.textSize = 17f
        nameTv.setTextColor(GTheme.TEXT)
        col.addView(nameTv)

        val dateTv = TextView(this)
        dateTv.text = DateFormat.format("yyyy-MM-dd HH:mm", Date(p.timeMs)).toString()
        dateTv.textSize = 12f
        dateTv.setTextColor(GTheme.MUTED)
        col.addView(dateTv)

        col.setOnClickListener {
            val data = Intent()
            data.putExtra("lon", p.lon)
            data.putExtra("lat", p.lat)
            setResult(Activity.RESULT_OK, data)
            finish()
        }

        val delTv = TextView(this)
        delTv.text = "🗑"
        delTv.textSize = 20f
        delTv.setPadding(dp(16), 0, dp(4), 0)
        delTv.setOnClickListener {
            SavedData.deletePoint(this, p)
            reload()
        }

        row.addView(col)
        row.addView(delTv)
        return row
    }
}
