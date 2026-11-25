package com.alruwaili.garminalbarr.billing

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.alruwaili.garminalbarr.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

/**
 * Manages trial lifecycle, purchase cache, and offline grace verification.
 * Storage: EncryptedSharedPreferences("subscription_prefs") — fully isolated.
 * This class does NOT touch the Room database to protect user favorites (is_fav).
 */
class SubscriptionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SubscriptionManager"
        private const val PREFS_NAME = "subscription_prefs"

        private const val KEY_TRIAL_START_EPOCH = "trial_start_epoch_ms"
        private const val KEY_IS_PURCHASED = "is_purchased"
        private const val KEY_LAST_VERIFIED_EPOCH = "last_verified_epoch_ms"
        private const val KEY_PURCHASE_TOKEN = "purchase_token"
        private const val KEY_IS_VIP_VERIFIED = "is_vip_verified"
        private const val KEY_VIP_LAST_VERIFIED = "vip_last_verified_ms"
        private const val KEY_REVOKED_TOKEN = "revoked_purchase_token"
        private const val KEY_VIP_REQUEST_PHONE = "vip_request_phone"
        private const val KEY_VIP_REQUEST_STATUS = "vip_request_status"
        private const val KEY_TRIAL_EXPIRED_SEEN = "trial_expired_seen"

        // مدة السماح بدون إنترنت للمشترك: بعدها يجب الاتصال مرة ليتحقق Google Play من أن الاشتراك ما زال فعالاً
        private const val OFFLINE_GRACE_PERIOD_MS = 30L * 24 * 60 * 60 * 1000 // 30 Days in ms

        @Volatile
        private var INSTANCE: SubscriptionManager? = null

        fun getInstance(context: Context): SubscriptionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SubscriptionManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _ready = CompletableDeferred<Unit>()
    val ready: Deferred<Unit> = _ready

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var fallbackPrefs: SharedPreferences? = null

    @Volatile
    private var initFailed = false

    @Volatile
    private var isVip = false

    /** ساعة موثوقة (وقت الخادم + عدّاد الإقلاع) بدل ساعة الهاتف. */
    val trustedClock = TrustedClock(context) { getPrefs() }

    /** يُستدعى عند كل تشغيل مع إنترنت لجلب الوقت الحقيقي من الخادم. */
    suspend fun syncTrustedTime(): Long? = trustedClock.syncWithServer()

    init {
        // Eager synchronous fallback so getPrefs() never blocks Main.
        // Encrypted prefs load async in initialize(); Splash awaits ready before routing.
        try {
            fallbackPrefs = context.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init fallback prefs", e)
        }
    }

    fun initialize() {
        scope.launch {
            try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                prefs = EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                loadVipStatus()
                _ready.complete(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "FATAL: Failed to initialize EncryptedSharedPreferences", e)
                FirebaseCrashlytics.getInstance().recordException(e)
                initFailed = true
                fallbackPrefs = context.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE)
                _ready.completeExceptionally(
                    RuntimeException("EncryptedSharedPreferences init failed", e)
                )
            }
        }
    }

    suspend fun awaitReady() = _ready.await()

    /**
     * Non-blocking prefs accessor — NEVER blocks Main.
     * SplashActivity awaits ready() before any entitlement routing, so [prefs]
     * is normally set. Pre-ready callers get fallback (fail-closed to paywall)
     * rather than ANR. Fail-closed is safe: no bypass, user re-taps after init.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun getPrefs(): SharedPreferences {
        prefs?.let { return it }

        if (initFailed) {
            return requireNotNull(fallbackPrefs) { "Fallback SharedPreferences unavailable" }
        }

        // If init completed (success or failure), return accordingly without blocking.
        if (_ready.isCompleted) {
            try {
                _ready.getCompleted()
                return requireNotNull(prefs) { "EncryptedSharedPreferences null after successful init" }
            } catch (_: Exception) {
                return requireNotNull(fallbackPrefs) { "Fallback SharedPreferences unavailable" }
            }
        }

        // Not ready yet — fail-closed to fallback, never runBlocking.
        Log.w(TAG, "Prefs accessed before init complete — using fallback (fail-closed)")
        return requireNotNull(fallbackPrefs) { "Fallback SharedPreferences unavailable" }
    }

    val isFirstLaunch: Boolean
        get() = getPrefs().getLong(KEY_TRIAL_START_EPOCH, -1L) == -1L

    fun isTrialStarted(): Boolean {
        return !isFirstLaunch
    }

    /**
     * Starts the trial after server-acknowledged creation.
     * Only called when StartupResult.NewDevice was returned and user confirms.
     * Creates trial atomically on server, then persists server timestamp locally.
     *
     * @return TrialCreationResult distinguishing new creation from existing discovery or failure.
     *         Caller must route through entitlement — do not assume active trial for Existing.
     */
    suspend fun startTrial(): TrialCreationResult {
        if (!isFirstLaunch) {
            Log.w(TAG, "startTrial called but trial already exists locally")
            return TrialCreationResult.Failed
        }

        val result = TrialRepository.createRemoteTrial(context)
        when (result) {
            is TrialCreationResult.Created -> {
                getPrefs().edit()
                    .putLong(KEY_TRIAL_START_EPOCH, result.serverTimestamp)
                    .apply()
                // trial_start قرأناه للتو من الخادم ← هو «الآن» الحقيقي
                trustedClock.anchor(result.serverTimestamp)
                com.alruwaili.garminalbarr.analytics.AnalyticsHelper.logTrialStarted(
                    TrialRepository.getAndroidId(context),
                    "welcome_screen"
                )
                Log.d(TAG, "Trial created with server timestamp: ${result.serverTimestamp}")
            }
            is TrialCreationResult.Existing -> {
                getPrefs().edit()
                    .putLong(KEY_TRIAL_START_EPOCH, result.serverTimestamp)
                    .apply()
                trustedClock.raiseFloor(result.serverTimestamp)
                Log.d(TAG, "Existing trial discovered — timestamp: ${result.serverTimestamp}")
            }
            is TrialCreationResult.Failed -> {
                Log.e(TAG, "Server trial creation failed — no local trial granted")
            }
        }
        return result
    }

    /**
     * Checks Firestore for an existing trial for this device.
     * Called on fresh install when local prefs are empty.
     * ANDROID_ID is the same after reinstall (same signing key),
     * so Firestore will find the original trial record.
     */
    /**
     * Fetches the complete server profile and persists relevant fields locally.
     * Returns the StartupResult for routing decisions.
     */
    suspend fun restoreTrialFromServer(): StartupResult {
        val result = TrialRepository.getRemoteTrialStart(context)
        when (result) {
            is StartupResult.ExistingAccess -> {
                persistServerProfile(result)
                Log.d(TAG, "Trial restored from Firestore: ${result.trialStartEpoch}")
            }
            is StartupResult.NewDevice -> {
                Log.d(TAG, "No trial found in Firestore for this device")
            }
            is StartupResult.ServerUnavailable -> {
                Log.w(TAG, "Server unavailable during trial restoration")
            }
            is StartupResult.ServerFailure -> {
                Log.e(TAG, "Server failure during trial restoration")
            }
        }
        return result
    }

    /**
     * Persists the server-verified profile locally.
     */
    private fun persistServerProfile(profile: StartupResult.ExistingAccess) {
        profile.trialStartEpoch?.let { epoch ->
            val old = getPrefs().getLong(KEY_TRIAL_START_EPOCH, -1L)
            getPrefs().edit().apply {
                putLong(KEY_TRIAL_START_EPOCH, epoch)
                // إن غيّرت تاريخ بداية التجربة من لوحة التحكم إلى تاريخ أحدث تُفتح التجربة من جديد
                if (old != -1L && epoch > old) remove(KEY_TRIAL_EXPIRED_SEEN)
            }.apply()
            trustedClock.raiseFloor(epoch)
        }
        // Persist VIP status
        setVipStatus(profile.isVipUser)
        if (profile.purchaseRevoked) {
            // [أمان] الخادم وجد أن الشراء غير صالح عند Google (مزيّف/مسترد) → إلغاء الصلاحية،
            // وتسجيل رمز الشراء حتى لا تعيد نتيجة Google Play المخزّنة/المزيّفة تفعيله.
            purchaseToken?.let { getPrefs().edit().putString(KEY_REVOKED_TOKEN, it).apply() }
            if (isPurchased) isPurchased = false
            Log.w(TAG, "Purchase revoked by server verification")
        } else {
            getPrefs().edit().remove(KEY_REVOKED_TOKEN).apply()
        }
        // Persist VIP request status
        profile.vipRequestStatus?.let { status ->
            saveVipRequest(savedVipRequestPhone ?: "", status)
        }
    }

    /**
     * Background refresh: fetch server profile and update local state if server confirms.
     * Called on routine launches when local trial exists.
     * Failure retains existing local state (fail-open for offline use).
     */
    suspend fun syncTrialToServer() {
        val localTrialStart = getPrefs().getLong(KEY_TRIAL_START_EPOCH, -1L)
        if (localTrialStart == -1L) return

        if (!TrialRepository.isNetworkAvailable(context)) {
            Log.d(TAG, "Offline — skipping server refresh, will retry next launch")
            return
        }

        val result = TrialRepository.getRemoteTrialStart(context)
        when (result) {
            is StartupResult.ExistingAccess -> {
                // Server confirms trial — update local state with server data
                persistServerProfile(result)
                Log.d(TAG, "Server profile refreshed: trial=${result.trialStartEpoch}, vip=${result.isVipUser}")
            }
            is StartupResult.NewDevice -> {
                // Server says no trial, but local has one — keep local (server may have lost data)
                Log.w(TAG, "Server reports NewDevice but local trial exists — keeping local state")
            }
            is StartupResult.ServerUnavailable, is StartupResult.ServerFailure -> {
                // Server unreachable — keep local state (fail-open for offline use)
                Log.w(TAG, "Server unavailable during refresh — keeping local state")
            }
        }
    }

    /**
     * الوقت المنقضي من التجربة محسوباً بالساعة الموثوقة (لا يتأثر بتغيير تاريخ الهاتف).
     */
    private fun trialElapsedMs(startEpoch: Long): Long =
        maxOf(0L, trustedClock.now() - startEpoch)

    val isTrialActive: Boolean
        get() {
            val p = getPrefs()
            val startEpoch = p.getLong(KEY_TRIAL_START_EPOCH, -1L)
            if (startEpoch == -1L) return false
            // انتهت مرة ← انتهت للأبد على هذا الجهاز
            if (p.getBoolean(KEY_TRIAL_EXPIRED_SEEN, false)) return false
            val active = trialElapsedMs(startEpoch) < BuildConfig.TRIAL_DURATION_MS
            if (!active && trustedClock.hasEverSynced && trustedClock.isServerAnchored) {
                p.edit().putBoolean(KEY_TRIAL_EXPIRED_SEEN, true).apply()
                Log.d(TAG, "Trial expired (trusted clock) — latched")
            }
            return active
        }

    /** المتبقي من التجربة بالملّي ثانية (٠ إذا انتهت). */
    val trialRemainingMs: Long
        get() {
            val startEpoch = getPrefs().getLong(KEY_TRIAL_START_EPOCH, -1L)
            if (startEpoch == -1L) return 0L
            if (!isTrialActive) return 0L
            return maxOf(0L, BuildConfig.TRIAL_DURATION_MS - trialElapsedMs(startEpoch))
        }

    /** نص عربي للمتبقي، مثل: «يومان و٥ ساعات» أو «٤٠ دقيقة». */
    fun trialRemainingText(): String {
        val ms = trialRemainingMs
        if (ms <= 0L) return "انتهت الفترة التجريبية"
        val totalMin = ms / 60_000
        val days = totalMin / (24 * 60)
        val hours = (totalMin % (24 * 60)) / 60
        val mins = totalMin % 60
        val parts = mutableListOf<String>()
        if (days > 0) parts += when (days) { 1L -> "يوم"; 2L -> "يومان"; else -> "$days أيام" }
        if (hours > 0) parts += when (hours) { 1L -> "ساعة"; 2L -> "ساعتان"; in 3L..10L -> "$hours ساعات"; else -> "$hours ساعة" }
        if (days == 0L && mins > 0) parts += when (mins) { 1L -> "دقيقة"; 2L -> "دقيقتان"; in 3L..10L -> "$mins دقائق"; else -> "$mins دقيقة" }
        if (parts.isEmpty()) parts += "أقل من دقيقة"
        return "متبقٍ من التجربة: " + parts.joinToString(" و")
    }

    var isPurchased: Boolean
        get() = getPrefs().getBoolean(KEY_IS_PURCHASED, false)
        set(value) {
            getPrefs().edit().apply {
                putBoolean(KEY_IS_PURCHASED, value)
                // تاريخ آخر تحقق يُحدَّث فقط عند الاتصال بالإنترنت (BillingClientWrapper):
                // نسخة Google Play المخزنة بدون إنترنت قد تكون قديمة فلا تمدد مدة السماح.
            }.apply()
            Log.d(TAG, "isPurchased updated to $value")
        }

    var purchaseToken: String?
        get() = getPrefs().getString(KEY_PURCHASE_TOKEN, null)
        set(value) {
            scope.launch {
                getPrefs().edit().putString(KEY_PURCHASE_TOKEN, value).apply()
            }
        }

    /** رمز شراء رفضه التحقق على الخادم — لا يُقبل مرة أخرى. */
    fun isTokenRevoked(token: String?): Boolean =
        token != null && token == getPrefs().getString(KEY_REVOKED_TOKEN, null)

    fun getLastVerifiedEpoch(): Long = getPrefs().getLong(KEY_LAST_VERIFIED_EPOCH, 0L)

    fun updateVerificationTimestamp() {
        scope.launch {
            getPrefs().edit()
                .putLong(KEY_LAST_VERIFIED_EPOCH, trustedClock.now())
                .apply()
            Log.d(TAG, "Verification timestamp refreshed")
        }
    }

    /**
     * Sets VIP status and persists to EncryptedSharedPreferences.
     * Once set to true, this persists across app restarts.
     * Can only be revoked by explicitly setting to false.
     */
    fun setVipStatus(vip: Boolean) {
        isVip = vip
        getPrefs().edit().apply {
            putBoolean(KEY_IS_VIP_VERIFIED, vip)
            // يُستدعى بعد قراءة الخادم: نسجّل وقت آخر تأكيد لصلاحية VIP
            if (vip) putLong(KEY_VIP_LAST_VERIFIED, trustedClock.now())
        }.apply()
        Log.d(TAG, "VIP status set to $vip (persisted)")
    }

    /**
     * Loads VIP status from EncryptedSharedPreferences on startup.
     * Called during initialize() to restore VIP state from previous session.
     */
    private fun loadVipStatus() {
        isVip = getPrefs().getBoolean(KEY_IS_VIP_VERIFIED, false)
        if (isVip) {
            Log.d(TAG, "VIP status restored from local storage: true")
        }
    }

    /** VIP فعّال بشرط أن يكون الخادم أكّده خلال آخر 30 يوماً (حتى لا يبقى مفتوحاً للأبد بعد انتهاء مدته وهو أوفلاين). */
    val hasVipAccess: Boolean
        get() {
            if (!isVip) return false
            val last = getPrefs().getLong(KEY_VIP_LAST_VERIFIED, -1L)
            if (last <= 0L) {
                // ترقية من نسخة قديمة: نبدأ العدّ من الآن
                getPrefs().edit().putLong(KEY_VIP_LAST_VERIFIED, trustedClock.now()).apply()
                return true
            }
            return trustedClock.now() - last < OFFLINE_GRACE_PERIOD_MS
        }

    // ── VIP Request Persistence ──

    fun saveVipRequest(phone: String, status: String) {
        getPrefs().edit()
            .putString(KEY_VIP_REQUEST_PHONE, phone)
            .putString(KEY_VIP_REQUEST_STATUS, status)
            .apply()
        Log.d(TAG, "VIP request saved: phone=$phone, status=$status")
    }

    val savedVipRequestPhone: String?
        get() = getPrefs().getString(KEY_VIP_REQUEST_PHONE, null)

    val savedVipRequestStatus: String?
        get() = getPrefs().getString(KEY_VIP_REQUEST_STATUS, null)

    fun clearVipRequest() {
        getPrefs().edit()
            .remove(KEY_VIP_REQUEST_PHONE)
            .remove(KEY_VIP_REQUEST_STATUS)
            .apply()
        Log.d(TAG, "VIP request cleared")
    }

    val isOfflineGraceActive: Boolean
        get() {
            if (!isPurchased) return false
            val lastVerified = getPrefs().getLong(KEY_LAST_VERIFIED_EPOCH, 0L)
            val elapsed = trustedClock.now() - lastVerified
            return elapsed < OFFLINE_GRACE_PERIOD_MS
        }

    /** تم التحقق من ترخيص Google Play على هذا الجهاز مرة واحدة على الأقل (للسماح بالعمل أوفلاين). */
    var licenseVerifiedOnce: Boolean
        get() = getPrefs().getBoolean("license_verified_once", false)
        set(value) { getPrefs().edit().putBoolean("license_verified_once", value).apply() }

    /** اشتراك مؤكَّد خلال مدة السماح (آخر تحقق من Google Play لم يتجاوز 30 يوماً). */
    val hasValidPurchase: Boolean
        get() = isPurchased && isOfflineGraceActive

    fun hasFullAccess(): Boolean {
        return hasVipAccess || hasValidPurchase || isTrialActive
    }

    fun hasFullAccessWithGrace(): Boolean {
        return hasVipAccess || hasValidPurchase || isTrialActive
    }

    fun hasFullAccessOffline(): Boolean {
        return hasVipAccess || hasValidPurchase || isTrialActive
    }
}
