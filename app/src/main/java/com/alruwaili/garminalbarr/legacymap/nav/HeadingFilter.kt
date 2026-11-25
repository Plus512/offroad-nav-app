package com.alruwaili.garminalbarr.legacymap.nav

import kotlin.math.abs

/**
 * نقل حرفي لمرشِّح تنعيم البوصلة (HeadingFilter) من تطبيق «خرائط الصحراء الدليلة»
 * (com.navigationgps.desert.map.HeadingFilter) — رياضيات Kotlin خالصة بلا أي اعتماد على
 * Android (قابلة للنقل دون أي تعديل في المنطق نفسه، فقط تغيير حزمة الملف). هذا اكتشاف جديد
 * بعد قراءة الملف كاملاً: لم يكن ملف إعادة-تخطيط إحداثيات فقط (ذاك مُصلَح أصلاً عند
 * دليل البر)، بل هو مرشِّح تنعيم فعلي (EMA تكيّفي) يمنع ارتعاش إبرة البوصلة عند السكون
 * ويمنحها استجابة فورية عند الدوران السريع — ميزة ناقصة فعلياً عند دليل البر (كانت تُستخدَم
 * زاوية الجهاز الخام مباشرة بلا أي تنعيم). لم يُنقَل كائن HeadingFlags (علم التفعيل الأصلي)
 * لأنه يُفعِّل التبديل بين هذا المرشِّح ومسار "legacy" قديم (SmoothLocationOverlay) غير
 * موجود أصلاً في دليل البر، فلا معنى لنقل علمٍ يُبدِّل إلى مسار لا وجود له.
 *
 * السلوك الفيزيائي:
 * - دوران سريع (omega > w1DegPerSec): alpha → 1، تتبّع فوري خلال عيّنة أو عيّنتين.
 * - سكون (omega < w0DegPerSec و|الفرق| < deadbandStable): يتجمَّد ويُعيد null، فيتخطى
 *   المستدعي أي إعادة رسم (صفر ارتعاش).
 * - انجراف بطيء: alpha ضمن [alphaMin, 1] عبر منحنى smoothstep.
 * كل التنعيم يسير عبر أقصر قوس (shortestArcDelta) كي لا يلتف السهم ٣٥٨° عكسياً بدل
 * قطع درجتين فقط عند عبور حد ٣٥٩°↔٠°.
 */
class HeadingFilter(
    var deadbandStable: Float = 0.9f,
    var deadbandHyst: Float = 0.35f,
    var alphaMin: Float = 0.06f,
    var w0DegPerSec: Float = 8f,
    var w1DegPerSec: Float = 90f,
    var snapThreshold: Float = 25f,
    var dtRefSec: Float = 0.02f,
) {
    var current: Float = 0f
        private set
    var frozen: Boolean = true
        private set

    /** ١٫٠ دقة عادية، ٠٫٦ دقة منخفضة، ٠٫٣٥ غير موثوقة (قرب معدن). */
    var accuracyScale: Float = 1f

    fun snapTo(deg: Float) {
        current = ((deg % 360f) + 360f) % 360f
        frozen = true
    }

    /**
     * @return الزاوية المنعَّمة الجديدة، أو null إن كانت متجمِّدة (على المستدعي تخطي أي تحديث/رسم).
     */
    fun update(targetDeg: Float, dtSec: Float): Float? {
        val dt = dtSec.coerceIn(0.008f, 0.10f)
        val diff = shortestArcDelta(targetDeg, current)
        val absDiff = abs(diff)
        val omega = absDiff / dt

        if (frozen) {
            if (absDiff < deadbandStable + deadbandHyst) return null
            frozen = false
        } else {
            if (absDiff < deadbandStable && omega < w0DegPerSec) {
                frozen = true
                return null
            }
        }
        if (absDiff >= snapThreshold / accuracyScale.coerceAtLeast(0.35f)) {
            current = ((targetDeg % 360f) + 360f) % 360f
            return current
        }
        var alpha = alphaMin + (1f - alphaMin) * smoothstep(w0DegPerSec, w1DegPerSec, omega)
        alpha *= accuracyScale
        val refSteps = dt / dtRefSec
        alpha = 1f - Math.pow((1f - alpha).toDouble(), refSteps.toDouble()).toFloat()
        alpha = alpha.coerceIn(alphaMin * accuracyScale, 1f)

        current = ((current + diff * alpha) % 360f + 360f) % 360f
        return current
    }

    companion object {
        /** فرق أقصر قوس موقَّع ضمن [-180, 180). موجب = باتجاه عقارب الساعة نحو الهدف. */
        fun shortestArcDelta(target: Float, current: Float): Float =
            ((target - current + 540f) % 360f) - 180f

        fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            if (x <= e0) return 0f
            if (x >= e1) return 1f
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
