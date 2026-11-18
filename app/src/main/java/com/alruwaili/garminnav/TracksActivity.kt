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
 * قائمة المسارات المسجَّلة سابقاً (الفكرة مأخوذة من شاشة Tracks في قارمن،
 * شيفرة أصلية بالكامل): تاريخ البدء، المدة، والمسافة لكل مسار. الضغط على مسار
 * يُرجع وقت بدايته (معرِّفه) للشاشة الرئيسية لتعرض خطّه على الخريطة.
 */
class TracksActivity : Activity() {

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
        title.text = "المسارات"
        title.textDirection = View.TEXT_DIRECTION_RTL
        title.textSize = 22f
        title.setTextColor(GTheme.TEXT)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, dp(20))
        root.addView(title)

        emptyText = TextView(this)
        emptyText.text = "لا توجد مسارات مسجَّلة بعد — اضغط زر «↪ مسار» في الشاشة الرئيسية لبدء التسجيل، ثم اضغطه مرة أخرى لإنهائه وحفظه"
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
        listBox.removeAllViews()
        val tracks = SavedData.listTracks(this)
        emptyText.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        for (t in tracks) listBox.addView(trackRow(t))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun trackRow(t: TrackSession): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.layoutDirection = View.LAYOUT_DIRECTION_RTL
        row.setPadding(dp(14), dp(16), dp(14), dp(16))
        row.setBackgroundColor(GTheme.BAR_BG)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(6)
        row.layoutParams = lp

        val dateTv = TextView(this)
        dateTv.text = DateFormat.format("yyyy-MM-dd HH:mm", Date(t.startMs)).toString()
        dateTv.textSize = 16f
        dateTv.setTextColor(GTheme.TEXT)
        row.addView(dateTv)

        val durationMin = (t.endMs - t.startMs) / 60000.0
        val infoTv = TextView(this)
        infoTv.text = "المسافة: ${Prefs.formatDistance(this, t.distanceM)} — المدة: ${String.format("%.0f دقيقة", durationMin)}"
        infoTv.textSize = 13f
        infoTv.setTextColor(GTheme.MUTED)
        row.addView(infoTv)

        row.setOnClickListener {
            val data = Intent()
            data.putExtra("viewTrackStartMs", t.startMs)
            setResult(Activity.RESULT_OK, data)
            finish()
        }

        return row
    }
}
