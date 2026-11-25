package com.navigationgps.desert.update

import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * تحديث التطبيق من داخل التطبيق (Google Play In-App Updates).
 *
 * - أولوية التحديث 4 أو 5 (تُحدَّد عند رفع الإصدار عبر Play Developer API)
 *   أو مرور 3 أيام على توفر التحديث → تحديث إجباري (IMMEDIATE) يغطي الشاشة.
 * - غير ذلك → تحديث مرن (FLEXIBLE): ينزل بالخلفية ثم يُسأل المستخدم لإعادة التشغيل.
 * - بدون إنترنت أو بدون Google Play: لا يحدث شيء ويعمل التطبيق عادي.
 */
class InAppUpdater(private val activity: FragmentActivity) {

    companion object {
        private const val TAG = "InAppUpdater"
        private const val FORCE_PRIORITY = 4
        private const val FORCE_AFTER_STALE_DAYS = 3
    }

    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(activity)
    private var downloadedDialogShown = false
    private var forceDialog: AlertDialog? = null

    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            Log.d(TAG, "Update flow result: ${result.resultCode}")
        }

    private val installListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            showRestartDialog()
        }
    }

    /** يُستدعى مرة في onCreate. */
    fun check() {
        enforceMinimumVersion()
        try {
            manager.registerListener(installListener)
            manager.appUpdateInfo.addOnSuccessListener { info ->
                if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return@addOnSuccessListener
                val stale = info.clientVersionStalenessDays() ?: 0
                val mustUpdate = info.updatePriority() >= FORCE_PRIORITY || stale >= FORCE_AFTER_STALE_DAYS
                val type = when {
                    mustUpdate && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> AppUpdateType.FLEXIBLE
                    else -> return@addOnSuccessListener
                }
                startFlow(info, type)
            }.addOnFailureListener { e ->
                Log.w(TAG, "appUpdateInfo failed (offline?): ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "check failed: ${e.message}")
        }
    }

    // ═══════════ التحديث الإجباري من Firebase (بدون رفع نسخة جديدة) ═══════════
    // Firestore: app_config/android
    //   min_version_code : رقم (أي نسخة رقم بنائها أقل منه يُفرض عليها التحديث)
    //   force_message    : نص اختياري يظهر للمستخدم
    // لإيقاف الإجبار: اجعل min_version_code = 0 أو احذف الحقل.
    // بدون إنترنت لا يُحجب المستخدم (لا يستطيع التحديث أصلاً)، ويُطلب منه عند أول اتصال.

    /** يُستدعى في onCreate و onResume. */
    fun enforceMinimumVersion() {
        if (!com.navigationgps.desert.billing.TrialRepository.isNetworkAvailable(activity)) return
        try {
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("app_config").document("android")
                .get(com.google.firebase.firestore.Source.SERVER)
                .addOnSuccessListener { doc ->
                    val minCode = doc.getLong("min_version_code") ?: 0L
                    val current = com.navigationgps.desert.BuildConfig.VERSION_CODE.toLong()
                    if (current < minCode) {
                        val msg = doc.getString("force_message")
                            ?: "يتوفر تحديث مهم للتطبيق. يجب التحديث للاستمرار في الاستخدام."
                        showForceUpdate(msg)
                    } else {
                        forceDialog?.dismiss()
                        forceDialog = null
                    }
                }
                .addOnFailureListener { e -> Log.w(TAG, "min version check failed: ${e.message}") }
        } catch (e: Exception) {
            Log.w(TAG, "enforceMinimumVersion failed: ${e.message}")
        }
    }

    private fun showForceUpdate(message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (forceDialog?.isShowing == true) return
        forceDialog = AlertDialog.Builder(activity)
            .setTitle("تحديث إجباري")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("تحديث الآن", null)
            .setNegativeButton("إغلاق التطبيق") { _, _ -> activity.finishAffinity() }
            .create()
        forceDialog?.setOnShowListener { d ->
            (d as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                startForcedUpdate()
            }
        }
        forceDialog?.show()
    }

    /** يحاول التحديث داخل التطبيق (IMMEDIATE)، وإلا يفتح صفحة التطبيق في Google Play. */
    private fun startForcedUpdate() {
        try {
            manager.appUpdateInfo.addOnSuccessListener { info ->
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                    startFlow(info, AppUpdateType.IMMEDIATE)
                } else {
                    openPlayStore()
                }
            }.addOnFailureListener { openPlayStore() }
        } catch (_: Exception) {
            openPlayStore()
        }
    }

    private fun openPlayStore() {
        val pkg = activity.packageName
        try {
            activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("market://details?id=$pkg")))
        } catch (_: Exception) {
            activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        }
    }

    /** يُستدعى في onResume: يكمل تحديثاً إجبارياً بدأ ولم يكتمل، أو يعرض إعادة التشغيل لتحديث نزل. */
    fun onResume() {
        enforceMinimumVersion()
        try {
            manager.appUpdateInfo.addOnSuccessListener { info ->
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    showRestartDialog()
                } else if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    startFlow(info, AppUpdateType.IMMEDIATE)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "onResume check failed: ${e.message}")
        }
    }

    fun release() {
        try { manager.unregisterListener(installListener) } catch (_: Exception) {}
    }

    private fun startFlow(info: com.google.android.play.core.appupdate.AppUpdateInfo, type: Int) {
        try {
            manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(type).build())
        } catch (e: Exception) {
            Log.w(TAG, "startUpdateFlow failed: ${e.message}")
        }
    }

    private fun showRestartDialog() {
        if (downloadedDialogShown || activity.isFinishing || activity.isDestroyed) return
        downloadedDialogShown = true
        AlertDialog.Builder(activity)
            .setTitle("تحديث جاهز")
            .setMessage("تم تنزيل نسخة جديدة من التطبيق. أعد التشغيل الآن لتثبيتها.")
            .setCancelable(false)
            .setPositiveButton("إعادة التشغيل") { _, _ -> manager.completeUpdate() }
            .setNegativeButton("لاحقاً") { _, _ -> downloadedDialogShown = false }
            .show()
    }
}
