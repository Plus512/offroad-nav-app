package com.alruwaili.garminnav

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val REQ_IMPORT_MAP = 2001
private const val REQ_PICK_FOLDER = 2002
private const val REQ_SEARCH = 2003
private const val REQ_POINTS = 2004

/**
 * رابط ملف خريطتك على GitHub (raw).
 * ملاحظة مهمة: raw.githubusercontent.com لا يُقدّم الملف لتطبيق غير مسجَّل دخول إذا كان
 * المستودع خاصاً (private) — لهذا يجب أن يكون مستودع offroad-nav-app نفسه عاماً (public)
 * من إعدادات GitHub (Settings ← Danger Zone ← Change visibility)، وإلا سيستمر خطأ ٤٠٤.
 */
const val MAP_DOWNLOAD_URL =
    "https://raw.githubusercontent.com/Plus512/offroad-nav-app/main/maps/almisharIMAP.img"

/** القائمة الرئيسية بأسلوب قارمن: شبكة ٣×٣ أيقونات. */
class MenuActivity : Activity() {

    private lateinit var hostRoot: FrameLayout
    private var progressOverlay: FrameLayout? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var progressTitle: TextView

    /** الإجراء المؤجَّل ريثما يختار المستخدم مجلد الحفظ الدائم ("download" أو "import"). */
    private var pendingAction: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(GTheme.BEZEL)
        root.setPadding(dp(30), dp(40), dp(30), dp(40))

        val title = TextView(this)
        title.text = "القائمة الرئيسية"
        title.textDirection = View.TEXT_DIRECTION_RTL
        title.textSize = 26f
        title.setTextColor(GTheme.TEXT)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, dp(30))
        root.addView(title)

        val grid = GridLayout(this)
        grid.columnCount = 3

        val items = listOf(
            "📍" to "النقاط المحفوظة",
            "↪" to "المسارات",
            "🗺" to "الخرائط",
            "🧭" to "البوصلة",
            "📶" to "الأقمار الصناعية",
            "⬇" to "استيراد خريطة",
            "🔍" to "بحث عن موقع",
            "🆘" to "الطوارئ",
            "⚙" to "الإعدادات",
        )

        // عرض كل خلية يُحسب من عرض الشاشة الفعلي حتى تظهر ٣ أعمدة دائماً، مهما كان حجم الشاشة.
        val screenWidthPx = resources.displayMetrics.widthPixels
        val sidePaddingPx = dp(30) * 2
        val marginPerCellPx = dp(8) * 2
        val cellWidthPx = (screenWidthPx - sidePaddingPx) / 3 - marginPerCellPx

        for ((icon, label) in items) {
            grid.addView(menuItem(icon, label, cellWidthPx))
        }
        root.addView(grid)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(GTheme.BEZEL)
        scroll.addView(root)

        // جذر عام (FrameLayout) يحوي القائمة + طبقة شريط التقدّم التي تظهر فوقها أثناء
        // التحميل/الاستيراد (تماماً كشاشة التحميل في تطبيقك «الدليلة +»).
        hostRoot = FrameLayout(this)
        hostRoot.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(hostRoot)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun menuItem(icon: String, label: String, cellWidthPx: Int): LinearLayout {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER
        cell.setBackgroundColor(GTheme.BAR_BG)
        cell.setPadding(dp(6), dp(20), dp(6), dp(20))

        val lp = GridLayout.LayoutParams()
        lp.width = cellWidthPx
        lp.height = dp(135)
        lp.setMargins(dp(8), dp(8), dp(8), dp(8))
        cell.layoutParams = lp

        val ic = TextView(this)
        ic.text = icon
        ic.textSize = 32f
        ic.setTextColor(GTheme.ACCENT)
        ic.gravity = Gravity.CENTER

        val tv = TextView(this)
        tv.text = label
        tv.textDirection = View.TEXT_DIRECTION_RTL
        tv.textSize = 12f
        tv.setTextColor(GTheme.TEXT)
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(8), 0, 0)

        cell.addView(ic)
        cell.addView(tv)

        cell.setOnClickListener {
            when (label) {
                "الإعدادات" -> startActivity(Intent(this, SettingsActivity::class.java))
                "البوصلة" -> startActivity(Intent(this, CompassActivity::class.java))
                "الأقمار الصناعية" -> startActivity(Intent(this, SatellitesActivity::class.java))
                "الطوارئ" -> startActivity(Intent(this, EmergencyActivity::class.java))
                "المسارات" -> startActivity(Intent(this, TracksActivity::class.java))
                "النقاط المحفوظة" -> startActivityForResult(Intent(this, SavedPointsActivity::class.java), REQ_POINTS)
                "بحث عن موقع" -> {
                    if (MapRepository.map == null) {
                        Toast.makeText(this, "لم تُحمَّل خريطة بعد — استورد أو نزّل الخريطة أولاً", Toast.LENGTH_LONG).show()
                    } else {
                        startActivityForResult(Intent(this, SearchActivity::class.java), REQ_SEARCH)
                    }
                }
                "استيراد خريطة" -> ensureFolderThen("import")
                "الخرائط" -> ensureFolderThen("download")
            }
        }
        return cell
    }

    /**
     * يتأكد من وجود مجلد حفظ دائم (SAF) مُختار قبل أي تنزيل/استيراد جديد، بحيث تبقى
     * الخريطة محفوظة حتى لو حُذف التطبيق لاحقاً. إن لم يوجد مجلد بعد، يطلب من
     * المستخدم اختيار واحد أولاً ثم يستكمل الإجراء المطلوب تلقائياً.
     */
    private fun ensureFolderThen(action: String) {
        if (MapStorage.hasPersistentFolder(this)) {
            if (action == "download") downloadMap() else pickMapFile()
            return
        }
        pendingAction = action
        Toast.makeText(
            this,
            "اختر مجلداً لحفظ الخريطة فيه بشكل دائم — بهذا تبقى الخريطة موجودة حتى لو حذفت التطبيق أو حدّثته، ولن تحتاج لتنزيلها مرة أخرى",
            Toast.LENGTH_LONG
        ).show()
        startActivityForResult(MapStorage.pickFolderIntent(), REQ_PICK_FOLDER)
    }

    // ---------- طبقة شريط التقدّم ----------

    private fun showProgress(titleText: String) {
        // يمنع الشاشة من الإطفاء أثناء التحميل — إطفاء الشاشة على بعض الأجهزة
        // (خصوصاً MIUI) يقطع الاتصالات الشبكية الجارية في الخلفية.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(0xEE1C1C1EL.toInt())

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.layoutDirection = View.LAYOUT_DIRECTION_RTL
        box.gravity = Gravity.CENTER
        val boxLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        boxLp.gravity = Gravity.CENTER
        box.layoutParams = boxLp
        box.setPadding(dp(40), dp(40), dp(40), dp(40))

        progressTitle = TextView(this)
        progressTitle.text = titleText
        progressTitle.textDirection = View.TEXT_DIRECTION_RTL
        progressTitle.textSize = 18f
        progressTitle.setTextColor(GTheme.TEXT)
        progressTitle.gravity = Gravity.CENTER
        progressTitle.setPadding(0, 0, 0, dp(20))
        box.addView(progressTitle)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.progress = 0
        val barLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
        box.addView(progressBar, barLp)

        progressText = TextView(this)
        progressText.text = "0%"
        progressText.textDirection = View.TEXT_DIRECTION_RTL
        progressText.textSize = 15f
        progressText.setTextColor(GTheme.MUTED)
        progressText.gravity = Gravity.CENTER
        progressText.setPadding(0, dp(15), 0, 0)
        box.addView(progressText)

        overlay.addView(box)
        hostRoot.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        progressOverlay = overlay
    }

    private fun updateProgress(doneBytes: Long, totalBytes: Long) {
        if (totalBytes > 0) {
            val pct = ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            progressBar.isIndeterminate = false
            progressBar.progress = pct
            progressText.text = "${pct}% — ${doneBytes / 1024 / 1024} / ${totalBytes / 1024 / 1024} ميجا"
        } else {
            progressBar.isIndeterminate = true
            progressText.text = "${doneBytes / 1024 / 1024} ميجا"
        }
    }

    private fun hideProgress() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progressOverlay?.let { hostRoot.removeView(it) }
        progressOverlay = null
    }

    // ---------- استيراد من ذاكرة الهاتف ----------

    /**
     * فتح منتقي ملفات الجهاز لاختيار ملف الخريطة (.img) مباشرة — بديل للتحميل من
     * الإنترنت، مفيد عندما تكون شبكة الجوال غير موثوقة لنقل ملف بهذا الحجم.
     */
    private fun pickMapFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_IMPORT_MAP)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_IMPORT_MAP -> {
                if (resultCode == Activity.RESULT_OK) {
                    val uri = data?.data
                    if (uri != null) importMapFile(uri)
                }
            }
            REQ_PICK_FOLDER -> {
                val action = pendingAction
                pendingAction = null
                val folderUri = data?.data
                if (resultCode == Activity.RESULT_OK && folderUri != null) {
                    MapStorage.onFolderPicked(this, folderUri)
                    when (action) {
                        "download" -> downloadMap()
                        "import" -> pickMapFile()
                    }
                } else {
                    Toast.makeText(this, "لم يُختر مجلد — تم إلغاء العملية", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_SEARCH, REQ_POINTS -> {
                // نتيجة البحث عن اسم أو اختيار نقطة محفوظة: نمررها للأعلى (شاشة الخريطة) دون أي تغيير.
                if (resultCode == Activity.RESULT_OK && data != null) {
                    setResult(Activity.RESULT_OK, data)
                    finish()
                }
            }
        }
    }

    private fun importMapFile(uri: Uri) {
        val main = Handler(Looper.getMainLooper())
        main.post { showProgress("جارٍ نسخ الخريطة من هاتفك…") }

        Thread {
            var tmpUri: Uri? = null
            try {
                val totalBytes = try {
                    contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                } catch (e: Exception) { -1L }

                val (tUri, out) = MapStorage.createTempOutputStream(this)
                    ?: throw Exception("تعذّر إنشاء ملف مؤقت في المجلد المحفوظ")
                tmpUri = tUri
                val input = contentResolver.openInputStream(uri)
                    ?: throw Exception("تعذّر فتح الملف المحدَّد")
                var copied = 0L
                var lastUpdate = 0L
                input.use { inp ->
                    out.use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            copied += n
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 150) {
                                lastUpdate = now
                                main.post { updateProgress(copied, totalBytes) }
                            }
                        }
                    }
                }
                if (!MapStorage.finalizeDownload(this, tUri)) throw Exception("تعذّر حفظ الملف النهائي")
                MapRepository.clear()
                main.post {
                    hideProgress()
                    Toast.makeText(
                        this,
                        "تم استيراد الخريطة (${MapStorage.mapSizeMb(this)} ميجا) — ستبقى محفوظة حتى لو حُذف التطبيق",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                tmpUri?.let { MapStorage.deleteTemp(this, it) }
                main.post {
                    hideProgress()
                    Toast.makeText(this, "فشل الاستيراد: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------- تحميل من الإنترنت ----------

    /**
     * تنزيل حقيقي لملف الخريطة من GitHub إلى تخزين التطبيق، مع شاشة تقدّم حقيقية
     * (نسبة مئوية + ميجا/ميجا) تبقى الشاشة مضاءة أثناءها لتفادي قطع الاتصال الذي
     * تسببه بعض الأجهزة (مثل MIUI) حين تُطفئ الشاشة أو يغادر المستخدم الشاشة أثناء
     * تنزيل في الخلفية. ملاحظة صريحة: هذا يضع الملف على الجهاز فقط. رسم الطرق
     * الفعلية منه لم يُفعَّل بعد (انظر MapCanvasView) — هذا عمل منفصل لم يكتمل.
     */
    private fun downloadMap() {
        val main = Handler(Looper.getMainLooper())
        main.post { showProgress("جارٍ تنزيل الخريطة…") }

        Thread {
            var lastError: Exception? = null
            var success = false
            var finalMb = 0L
            for (attempt in 1..5) {
                var tmpUri: Uri? = null
                try {
                    val conn = URL(MAP_DOWNLOAD_URL).openConnection() as HttpURLConnection
                    conn.connectTimeout = 30000
                    conn.readTimeout = 30000
                    conn.connect()
                    if (conn.responseCode != 200) {
                        throw Exception("رمز استجابة الخادم: ${conn.responseCode}")
                    }
                    val totalBytes = conn.contentLengthLong
                    val (tUri, out) = MapStorage.createTempOutputStream(this)
                        ?: throw Exception("تعذّر إنشاء ملف مؤقت في المجلد المحفوظ")
                    tmpUri = tUri
                    var copied = 0L
                    var lastUpdate = 0L
                    conn.inputStream.use { input ->
                        out.use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                copied += n
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 150) {
                                    lastUpdate = now
                                    main.post { updateProgress(copied, totalBytes) }
                                }
                            }
                        }
                    }
                    if (!MapStorage.finalizeDownload(this, tUri)) throw Exception("تعذّر حفظ الملف النهائي")
                    MapRepository.clear()
                    finalMb = MapStorage.mapSizeMb(this)
                    success = true
                    break
                } catch (e: Exception) {
                    lastError = e
                    tmpUri?.let { MapStorage.deleteTemp(this, it) }
                    if (attempt < 5) {
                        main.post {
                            progressTitle.text = "انقطع الاتصال، إعادة المحاولة (${attempt}/5)…"
                        }
                        Thread.sleep(2000)
                    }
                }
            }
            if (success) {
                main.post {
                    hideProgress()
                    Toast.makeText(
                        this,
                        "اكتمل التنزيل ($finalMb ميجا) — ستبقى الخريطة محفوظة حتى لو حُذف التطبيق",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                main.post {
                    hideProgress()
                    Toast.makeText(this, "فشل التنزيل بعد ٥ محاولات: ${lastError?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
