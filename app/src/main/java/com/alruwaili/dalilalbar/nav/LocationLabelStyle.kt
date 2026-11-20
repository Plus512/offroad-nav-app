package com.alruwaili.dalilalbar.nav

import kotlin.math.ln

/**
 * مواصفة ظهور تسميات المواقع (قاعدة بيانات المستخدم locations.db)، مأخوذة حرفياً من كود «الدليلة بلس»
 * (Map/MapCanvasView.swift) كما اتُّفق عليه: نفس الخط، نفس حدّ التكبير، نفس التباين — لا تخمين في القيم
 * التي وُجدت صريحة في ذلك الكود؛ ما لم يُسجَّل بدقة (نقطتا بداية/نهاية تحجيم النص على محور التكبير)
 * اتُّخذ له افتراض معقول مذكور أدناه بصراحة لتبقى الفجوة بين "المؤكَّد" و"المقدَّر" واضحة.
 */
object LocationLabelStyle {

    /** الخط المستخدم في iOS: UIFont.systemFont(weight: .heavy). أقرب خط متاح فعلاً في style_dalil.json. */
    const val FONT_STACK_PRIMARY = "Noto Sans Bold"

    /** حجم النص الأساس (نقطة) عند حدّ ظهور الاسم الأول — من MapCanvasView.swift حرفياً: 15.5pt. */
    const val BASE_TEXT_SIZE = 15.5f

    /** أقصى تحجيم عند التكبير العالي — من MapCanvasView.swift حرفياً: 1.35× الحجم الأساس (≈20.9). */
    const val MAX_TEXT_SIZE = BASE_TEXT_SIZE * 1.35f

    /**
     * درجة تكبير MapLibre (القياس المعياري لمربّعات 256px، وهو نفس أساس MapMath.swift في الدليلة بلس
     * كما تأكّد من فحص ذلك الملف) التي تقابل: deskZoom = zoom + log2(2.75) >= 12 → zoom >= 12 - log2(2.75).
     * هذه معادلة محسوبة من الصيغة المسجَّلة حرفياً، لا رقم مُخمَّن.
     */
    val MIN_VISIBLE_ZOOM: Float = (12.0 - (ln(2.75) / ln(2.0))).toFloat()

    /**
     * [تقدير غير مؤكَّد من كود iOS حرفياً]: درجة التكبير التي يصل عندها التحجيم إلى MAX_TEXT_SIZE لم
     * تكن مسجَّلة بدقة في الملاحظات المحفوظة من فحص MapCanvasView.swift — فقط النسبة (1.35×) والحجم
     * الأساس. استُخدم هنا تكبير 18 كنهاية معقولة للتحجيم التدريجي (وهو أيضاً سقف تكبير مستخدم فعلاً في
     * نقاط أخرى من هذا التطبيق). إن كان للمستخدم الرقم الدقيق من الكود الأصلي، يجب تصحيحه هنا.
     */
    const val MAX_TEXT_SIZE_ZOOM = 18.0f

    /** الحد الأقصى لعدد الأسماء المعروضة فعلياً في iOS: 60. لا يوجد مكافئ مباشر في مواصفة أنماط
     * MapLibre لعدّ الرموز المعروضة؛ أقرب تقريب هو تحديد عدد الصفوف المستعلمة من قاعدة البيانات نفسها
     * (انظر LocationsRepository.queryInBounds) بالإضافة إلى textAllowOverlap=false (منع تراكب الأسماء) —
     * وهذا تقريب مذكور بصراحة في README، ليس استبدالاً حرفياً للسلوك. */
    const val MAX_LABELS_QUERY_LIMIT = 60

    /** طول الاسم قبل القطع مع "…" — من MapCanvasView.swift حرفياً: 18 حرفاً. */
    const val TITLE_TRUNCATE_LENGTH = 18

    fun truncateTitle(title: String): String =
        if (title.length > TITLE_TRUNCATE_LENGTH) title.take(TITLE_TRUNCATE_LENGTH) + "…" else title
}
