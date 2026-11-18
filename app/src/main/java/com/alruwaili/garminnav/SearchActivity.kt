package com.alruwaili.garminnav

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

private const val MAX_RESULTS_SHOWN = 150

/**
 * شاشة بحث عن اسم داخل الخريطة المحمَّلة حالياً (شعاب، رجوم، قرى، آبار...).
 * تبحث داخل فهرس كل الأسماء المفكوكة من قطاع LBL عبر الخريطة بأكملها (وليس فقط ما
 * يُعرض على الشاشة حالياً)، مبنيٍّ مرة واحدة فقط (MapRepository.ensureLabelIndex)،
 * وتعرض كل اسم يحتوي على النص المكتوب. الضغط على أي نتيجة يُرجع موقعها (خط
 * الطول/العرض بوحدات قارمن الخام) إلى الشاشة الرئيسية لتتوسّط الخريطة عليه.
 */
class SearchActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var resultsBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private val main = Handler(Looper.getMainLooper())

    private var indexReady = false
    private var pendingQuery: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(GTheme.BEZEL)
        root.setPadding(dp(24), dp(30), dp(24), dp(16))

        val title = TextView(this)
        title.text = "بحث عن موقع"
        title.textDirection = View.TEXT_DIRECTION_RTL
        title.textSize = 22f
        title.setTextColor(GTheme.TEXT)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, dp(16))
        root.addView(title)

        input = EditText(this)
        input.textDirection = View.TEXT_DIRECTION_RTL
        input.textSize = 18f
        input.hint = "اكتب اسماً، مثل: شعيب"
        input.setTextColor(GTheme.TEXT)
        input.setHintTextColor(GTheme.MUTED)
        input.setBackgroundColor(GTheme.BAR_BG)
        input.setPadding(dp(16), dp(14), dp(16), dp(14))
        root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        progress = ProgressBar(this)
        progress.visibility = View.GONE
        val progLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        progLp.gravity = Gravity.CENTER
        progLp.topMargin = dp(20)
        root.addView(progress, progLp)

        statusText = TextView(this)
        statusText.textDirection = View.TEXT_DIRECTION_RTL
        statusText.textSize = 14f
        statusText.setTextColor(GTheme.MUTED)
        statusText.gravity = Gravity.CENTER
        statusText.setPadding(0, dp(14), 0, dp(6))
        root.addView(statusText)

        resultsBox = LinearLayout(this)
        resultsBox.orientation = LinearLayout.VERTICAL

        val scroll = ScrollView(this)
        scroll.addView(resultsBox)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        buildIndexInBackground()

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { runSearch(s?.toString()?.trim() ?: "") }
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** يبني فهرس كل أسماء الخريطة مرة واحدة فقط (قد يستغرق بضع ثوانٍ لأول مرة). */
    private fun buildIndexInBackground() {
        if (MapRepository.labelIndex != null) { indexReady = true; return }
        progress.visibility = View.VISIBLE
        statusText.text = "جارٍ تحضير فهرس الأسماء لأول مرة…"
        thread {
            MapRepository.ensureLabelIndex()
            main.post {
                indexReady = true
                progress.visibility = View.GONE
                statusText.text = ""
                pendingQuery?.let { runSearch(it) }
            }
        }
    }

    private fun runSearch(query: String) {
        if (!indexReady) { pendingQuery = query; return }
        resultsBox.removeAllViews()
        if (query.length < 2) {
            statusText.text = if (query.isEmpty()) "" else "اكتب حرفين على الأقل"
            return
        }
        val idx = MapRepository.labelIndex ?: emptyList()
        val matches = idx.filter { it.label.contains(query) }
        if (matches.isEmpty()) {
            statusText.text = "لا توجد نتائج"
            return
        }
        val shown = matches.distinctBy { "${it.label}_${it.lon}_${it.lat}" }.take(MAX_RESULTS_SHOWN)
        statusText.text = if (matches.size > MAX_RESULTS_SHOWN)
            "${matches.size} نتيجة — يُعرض أول $MAX_RESULTS_SHOWN فقط، دقّق البحث لتضييقها"
        else "${matches.size} نتيجة"
        for (pt in shown) resultsBox.addView(resultRow(pt))
    }

    private fun resultRow(pt: LabeledPoint): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutDirection = View.LAYOUT_DIRECTION_RTL
        row.setPadding(dp(14), dp(16), dp(14), dp(16))
        row.setBackgroundColor(GTheme.BAR_BG)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(6)
        row.layoutParams = lp

        val tv = TextView(this)
        tv.text = pt.label
        tv.textDirection = View.TEXT_DIRECTION_RTL
        tv.textSize = 17f
        tv.setTextColor(GTheme.TEXT)
        row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        row.setOnClickListener {
            val data = Intent()
            data.putExtra("lon", pt.lon)
            data.putExtra("lat", pt.lat)
            setResult(Activity.RESULT_OK, data)
            finish()
        }
        return row
    }
}
