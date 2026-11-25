package com.navigationgps.desert.billing

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ساعة موثوقة لاحتساب مدة التجربة المجانية (٣ أيام) دون الاعتماد على ساعة الهاتف.
 *
 * المشكلة القديمة: كان الحساب = System.currentTimeMillis() - trial_start،
 * فمن يُرجع تاريخ الهاتف إلى الوراء تطول تجربته إلى ما لا نهاية.
 *
 * الآلية الجديدة:
 *  1. عند وجود إنترنت: نكتب last_seen = serverTimestamp() في وثيقة الجهاز في trials
 *     ثم نقرؤها من الخادم ← هذا هو «الوقت الحقيقي» من Google وليس من الهاتف.
 *  2. نربط هذا الوقت بعدّاد الإقلاع SystemClock.elapsedRealtime() الذي لا يتأثر
 *     بتغيير التاريخ أو الساعة أو المنطقة الزمنية. فطالما لم يُطفأ الهاتف:
 *        الآن = وقت_الخادم + (elapsedRealtime الآن − elapsedRealtime لحظة المزامنة)
 *  3. بعد إعادة تشغيل الهاتف دون إنترنت: نستخدم ساعة الهاتف لكن لا نسمح للوقت
 *     أن يرجع خلف «أعلى وقت موثوق شوهد» (high-water mark) المحفوظ مشفّراً.
 *     أي أن إرجاع التاريخ لا يُطيل التجربة أبداً.
 *  4. إذا رأينا مرة أن التجربة انتهت (بوقت موثوق) نحفظ علامة دائمة trial_expired_seen
 *     فلا تعود التجربة فعّالة حتى لو تلاعب المستخدم بالساعة لاحقاً.
 */
class TrustedClock(
    private val context: Context,
    private val prefsProvider: () -> SharedPreferences
) {

    companion object {
        private const val TAG = "TrustedClock"
        private const val COLLECTION = "trials"
        private const val SYNC_TIMEOUT_MS = 6000L

        private const val KEY_ANCHOR_SERVER_MS = "tc_anchor_server_ms"
        private const val KEY_ANCHOR_ELAPSED_MS = "tc_anchor_elapsed_ms"
        private const val KEY_ANCHOR_BOOT_COUNT = "tc_anchor_boot_count"
        private const val KEY_HIGH_WATER_MS = "tc_high_water_ms"
        private const val KEY_LAST_SERVER_SYNC_MS = "tc_last_server_sync_ms"
        private const val KEY_TAMPER_COUNT = "tc_tamper_count"

        /** فرق مسموح بين ساعة الهاتف والوقت الموثوق قبل اعتباره تلاعباً (١٠ دقائق). */
        private const val TAMPER_TOLERANCE_MS = 10L * 60 * 1000

        /** لا نحدّث high-water في التخزين أكثر من مرة كل دقيقة لتقليل الكتابة. */
        private const val HIGH_WATER_PERSIST_STEP_MS = 60L * 1000
        // مرساة محلية لكل تشغيل للجوال: تجعل الوقت يتقدم فعلياً حتى لو أُرجعت ساعة الجوال بعد إعادة التشغيل
        private const val KEY_LOCAL_BASE_MS = "tc_local_base_ms"
        private const val KEY_LOCAL_ELAPSED_MS = "tc_local_elapsed_ms"
        private const val KEY_LOCAL_BOOT_COUNT = "tc_local_boot_count"
        private const val TIME_SYNC_COLLECTION = "time_sync"
    }

    @Volatile
    private var cachedHighWater: Long = -1L

    private fun prefs() = prefsProvider()

    private fun bootCount(): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) {
        -1
    }

    /**
     * يجلب وقت الخادم الحقيقي من Firestore ويثبّته مع عدّاد الإقلاع.
     * @return وقت الخادم بالملّي ثانية، أو null إن تعذّر (لا إنترنت/خطأ).
     */
    suspend fun syncWithServer(): Long? {
        if (!TrialRepository.isNetworkAvailable(context)) return null
        return withTimeoutOrNull(SYNC_TIMEOUT_MS) {
            try {
                val androidId = TrialRepository.getAndroidId(context)
                val ref = FirebaseFirestore.getInstance().collection(COLLECTION).document(androidId)

                // كتابة last_seen فقط إذا كانت الوثيقة موجودة (لا ننشئ وثيقة تجربة من هنا)
                val existing = ref.get(Source.SERVER).await()
                val serverNow: Long? = if (existing.exists()) {
                    // last_seen + رقم نسخة التطبيق (لصفحة «التحديثات» في لوحة التحكم)
                    ref.set(mapOf(
                        "last_seen" to FieldValue.serverTimestamp(),
                        "app_version" to com.navigationgps.desert.BuildConfig.VERSION_NAME,
                        "app_version_code" to com.navigationgps.desert.BuildConfig.VERSION_CODE
                    ), SetOptions.merge()).await()
                    ref.get(Source.SERVER).await().getTimestamp("last_seen")?.toDate()?.time
                } else {
                    // جهاز بلا سجل باسم ANDROID_ID (مثلاً تعرّفنا عليه بـ App Set ID بعد إعادة ضبط المصنع):
                    // نأخذ وقت الخادم من مستند مستقل حتى لا يبقى الوقت معتمداً على ساعة الجوال.
                    val ts = FirebaseFirestore.getInstance().collection(TIME_SYNC_COLLECTION).document(androidId)
                    ts.set(mapOf("t" to FieldValue.serverTimestamp())).await()
                    ts.get(Source.SERVER).await().getTimestamp("t")?.toDate()?.time
                }

                if (serverNow != null) {
                    anchor(serverNow)
                    Log.d(TAG, "Server time synced: $serverNow (device=${System.currentTimeMillis()})")
                }
                serverNow
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Server time sync failed: ${e.message}")
                null
            }
        }
    }

    /** يثبّت وقتاً موثوقاً معروفاً (مثلاً trial_start القادم من الخادم لحظة الإنشاء). */
    fun anchor(serverNowMs: Long) {
        // وقت الخادم هو المرجع: يصحّح أي قفزة سابقة لساعة الجوال إلى المستقبل
        // (لا يفيد المتحايل لأن الرجوع للخلف ما زال ممنوعاً بعد هذه النقطة).
        val hw = serverNowMs
        cachedHighWater = hw
        prefs().edit()
            .putLong(KEY_ANCHOR_SERVER_MS, serverNowMs)
            .putLong(KEY_ANCHOR_ELAPSED_MS, SystemClock.elapsedRealtime())
            .putInt(KEY_ANCHOR_BOOT_COUNT, bootCount())
            .putLong(KEY_LAST_SERVER_SYNC_MS, serverNowMs)
            .putLong(KEY_HIGH_WATER_MS, hw)
            .apply()
    }

    /** يرفع الحد الأدنى للوقت (لا يُخفّضه أبداً) — مثلاً بقيمة trial_start. */
    fun raiseFloor(timeMs: Long) {
        if (timeMs > highWater()) {
            cachedHighWater = timeMs
            prefs().edit().putLong(KEY_HIGH_WATER_MS, timeMs).apply()
        }
    }

    private fun highWater(): Long {
        if (cachedHighWater < 0) cachedHighWater = prefs().getLong(KEY_HIGH_WATER_MS, 0L)
        return cachedHighWater
    }

    /** الوقت المرتبط بالخادم إن كان الهاتف لم يُعد تشغيله منذ آخر مزامنة، وإلا null. */
    private fun anchoredNow(): Long? {
        val p = prefs()
        val serverMs = p.getLong(KEY_ANCHOR_SERVER_MS, -1L)
        val elapsedAt = p.getLong(KEY_ANCHOR_ELAPSED_MS, -1L)
        if (serverMs <= 0 || elapsedAt < 0) return null
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed < elapsedAt) return null // أُعيد تشغيل الهاتف
        val savedBoot = p.getInt(KEY_ANCHOR_BOOT_COUNT, -1)
        val boot = bootCount()
        if (savedBoot != -1 && boot != -1 && savedBoot != boot) return null
        return serverMs + (nowElapsed - elapsedAt)
    }

    /**
     * «الآن» الموثوق. لا يرجع إلى الخلف أبداً، ولا يتأثر بإرجاع تاريخ الهاتف.
     */
    private fun localAnchoredNow(baseIfNew: Long): Long {
        val p = prefs()
        val nowElapsed = SystemClock.elapsedRealtime()
        val boot = bootCount()
        val base = p.getLong(KEY_LOCAL_BASE_MS, -1L)
        val elapsedAt = p.getLong(KEY_LOCAL_ELAPSED_MS, -1L)
        val savedBoot = p.getInt(KEY_LOCAL_BOOT_COUNT, -1)
        val valid = base > 0 && elapsedAt >= 0 && nowElapsed >= elapsedAt &&
            !(savedBoot != -1 && boot != -1 && savedBoot != boot)
        if (valid) return base + (nowElapsed - elapsedAt)
        p.edit()
            .putLong(KEY_LOCAL_BASE_MS, baseIfNew)
            .putLong(KEY_LOCAL_ELAPSED_MS, nowElapsed)
            .putInt(KEY_LOCAL_BOOT_COUNT, boot)
            .apply()
        return baseIfNew
    }

    fun now(): Long {
        val device = System.currentTimeMillis()
        val hw = highWater()
        val anchored = anchoredNow()

        val result = if (anchored != null) {
            if (device < anchored - TAMPER_TOLERANCE_MS) noteTamper("device clock behind server-anchored time")
            maxOf(anchored, hw)
        } else {
            if (device < hw - TAMPER_TOLERANCE_MS) noteTamper("device clock behind high-water mark")
            // بدون مرساة خادم (بعد إعادة تشغيل الجوال وبدون إنترنت): الوقت يتقدم من آخر وقت معروف
            // بعدّاد الجوال الداخلي، فلا "يتجمّد" إذا أُرجعت الساعة للخلف.
            maxOf(device, hw, localAnchoredNow(maxOf(device, hw)))
        }

        if (result - hw >= HIGH_WATER_PERSIST_STEP_MS) {
            cachedHighWater = result
            prefs().edit().putLong(KEY_HIGH_WATER_MS, result).apply()
        }
        return result
    }

    private fun noteTamper(reason: String) {
        val count = prefs().getInt(KEY_TAMPER_COUNT, 0) + 1
        prefs().edit().putInt(KEY_TAMPER_COUNT, count).apply()
        Log.w(TAG, "Clock tamper suspected ($reason), count=$count")
    }

    /** هل تمت مزامنة وقت الخادم ولو مرة واحدة على هذا الجهاز؟ */
    /** true عندما يكون الوقت الحالي محسوباً من آخر مزامنة مع الخادم (وليس من ساعة الجوال). */
    val isServerAnchored: Boolean
        get() = anchoredNow() != null

    val hasEverSynced: Boolean
        get() = prefs().getLong(KEY_LAST_SERVER_SYNC_MS, -1L) > 0
}
