package com.alruwaili.garminalbarr

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log
import com.alruwaili.garminalbarr.billing.StartupResult
import com.github.javiersantos.piracychecker.PiracyChecker
import com.github.javiersantos.piracychecker.callbacks.PiracyCheckerCallback
import com.github.javiersantos.piracychecker.enums.PiracyCheckerError
import com.github.javiersantos.piracychecker.enums.PirateApp

class SplashActivity : AppCompatActivity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        setContentView(R.layout.activity_splash)

        val app = application as MyApplication
        val subManager = app.subscriptionManager

        lifecycleScope.launch {
            subManager.awaitReady()
            val minimumSplashTime = async { delay(500) }

            // ── جلب الوقت الحقيقي من الخادم (لاحتساب مدة التجربة دون الاعتماد على ساعة الهاتف) ──
            @Suppress("DEPRECATION")
            lifecycleScope.launch {
                val serverNow = subManager.syncTrustedTime()
                Log.d("SplashActivity", "Trusted time sync: ${serverNow ?: "offline/failed"}")
            }

            var startupResult: StartupResult? = null

            if (subManager.isFirstLaunch) {
                // Fresh install/reinstall: server-only lookup
                val result = withTimeoutOrNull(5000L) {
                    subManager.restoreTrialFromServer()
                }
                startupResult = result
                when (result) {
                    is StartupResult.ExistingAccess -> {
                        // Already persisted by restoreTrialFromServer
                        Log.d("SplashActivity", "Existing access restored: trial=${result.trialStartEpoch}, vip=${result.isVipUser}")
                    }
                    is StartupResult.NewDevice -> {
                        // Open WelcomeActivity; do not create local trial state yet
                        Log.d("SplashActivity", "New device detected — routing to WelcomeActivity")
                        minimumSplashTime.await()
                        if (!isFinishing && !isDestroyed) {
                            startActivity(Intent(this@SplashActivity, WelcomeActivity::class.java))
                            finish()
                        }
                        return@launch
                    }
                    is StartupResult.ServerUnavailable, is StartupResult.ServerFailure -> {
                        // Route to WelcomeActivity; dialog shown on "متابعة" click
                        Log.w("SplashActivity", "Server unavailable/failure — routing to WelcomeActivity")
                        minimumSplashTime.await()
                        if (!isFinishing && !isDestroyed) {
                            val intent = Intent(this@SplashActivity, WelcomeActivity::class.java)
                            intent.putExtra("server_blocked", true)
                            startActivity(intent)
                            finish()
                        }
                        return@launch
                    }
                    null -> {
                        // Timeout: treat as ServerUnavailable
                        Log.w("SplashActivity", "Server lookup timed out — routing to WelcomeActivity")
                        minimumSplashTime.await()
                        if (!isFinishing && !isDestroyed) {
                            val intent = Intent(this@SplashActivity, WelcomeActivity::class.java)
                            intent.putExtra("server_blocked", true)
                            startActivity(intent)
                            finish()
                        }
                        return@launch
                    }
                }
            } else {
                // Local trial exists — background server refresh (non-blocking)
                @Suppress("DEPRECATION")
                lifecycleScope.launch {
                    withTimeoutOrNull(5000L) {
                        subManager.syncTrialToServer()
                    }
                    Log.d("SplashActivity", "Background server refresh completed")
                }
            }

            // ── My Locations Cloud Restore ──
            val storage = MyLocationsStorage(this@SplashActivity)
            if (subManager.isFirstLaunch || storage.isEmpty()) {
                val cloudLocations = withTimeoutOrNull(5000L) {
                    LocationsSyncRepository.restoreFromCloud(this@SplashActivity)
                }
                if (!cloudLocations.isNullOrEmpty()) {
                    storage.save(cloudLocations)
                    Log.d("SplashActivity", "Restored ${cloudLocations.size} locations from cloud")
                }
            }

            minimumSplashTime.await()

            if (isFinishing || isDestroyed) return@launch

            val licensingKey = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAnLnUEhc5iINbczxXBcvfKDa3AsEAd6Lt1TPZhEg+4pQLyyLzD/s7LCmlte3B8TZ81PQg3v2DOXEllX7E4NbUR1Lx+YUpryOSVEnAtk3exm7qpMO55LDvCkTzVhWnTTcUAPcN6sKr78wiuH+yah468IAzO+VLV3ei6CxCx6J6mkU4QAuiNmiSVlx7OyIEv4FYyFK+OLGYMn7OTGx6fGWl2kMt9Uz8G6X5Wl1ne4lOHF++o277T49CYbs+Va/GelmUWXdOjPoCmSPZqdYorFIE/syh9x092Icmdj4CcN5aYS2qw0vQYxOwIxYwB7y5zQBUwksYgLUTQedFJOYev+WeTQIDAQAB"

            // ── التحقق من الترخيص (Google Play) ──
            // - المشترك أو صاحب كود VIP يدخل مباشرة (لا نحجب من دفع).
            // - بدون إنترنت: يدخل من سبق التحقق منه على هذا الجهاز (تطبيق صحراوي يعمل أوفلاين).
            // - لا نشترط مصدر التثبيت: من نقل التطبيق لجوال جديد أو استعاده من نسخة احتياطية لا يُحجب؛
            //   Google Play Licensing نفسه يتحقق أن الحساب حصل على التطبيق من المتجر.
            val entitled = subManager.hasVipAccess || subManager.hasValidPurchase
            val online = com.alruwaili.garminalbarr.billing.TrialRepository.isNetworkAvailable(this@SplashActivity)
            when {
                licensingKey.isEmpty() || BuildConfig.DEBUG || entitled -> routeByEntitlement()
                !online -> {
                    // أوفلاين: من تحقّقنا منه سابقاً، أو المثبَّت من Google Play (مثل من حدّث التطبيق ثم خرج للبر)
                    if (subManager.licenseVerifiedOnce || isInstalledFromPlay()) routeByEntitlement()
                    else showLicenseNeedsInternetDialog()
                }
                else -> {
                    PiracyChecker(this@SplashActivity)
                        .enableGooglePlayLicensing(licensingKey)
                        .callback(object : PiracyCheckerCallback() {
                            override fun allow() {
                                subManager.licenseVerifiedOnce = true
                                runOnUiThread { routeByEntitlement() }
                            }

                            override fun doNotAllow(error: PiracyCheckerError, appit: PirateApp?) {
                                runOnUiThread {
                                    if (isFinishing || isDestroyed) return@runOnUiThread
                                    if (subManager.licenseVerifiedOnce) {
                                        // جهاز تحقّقنا منه سابقاً: رفض مؤقت من الخادم لا يُغلق التطبيق على مستخدم شرعي
                                        routeByEntitlement()
                                    } else {
                                        showNotFromPlayDialog()
                                    }
                                }
                            }

                            override fun onError(error: PiracyCheckerError) {
                                runOnUiThread {
                                    if (isFinishing || isDestroyed) return@runOnUiThread
                                    if (subManager.licenseVerifiedOnce) {
                                        routeByEntitlement()
                                    } else {
                                        showLicenseErrorDialog()
                                    }
                                }
                            }
                        })
                        .start()
                }
            }
        }
    }

    private fun isInstalledFromPlay(): Boolean = try {
        val installer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
        installer == "com.android.vending"
    } catch (_: Exception) {
        false
    }

    private fun showLicenseNeedsInternetDialog() {
        if (isFinishing || isDestroyed) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("مطلوب اتصال بالإنترنت")
            .setMessage("يحتاج التطبيق اتصالاً بالإنترنت مرة واحدة فقط عند أول تشغيل للتحقق من Google Play. بعدها يعمل بدون إنترنت.")
            .setCancelable(false)
            .setPositiveButton("إعادة المحاولة") { _, _ -> recreate() }
            .setNegativeButton("إغلاق") { _, _ -> finish() }
            .show()
    }

    private fun showNotFromPlayDialog() {
        if (isFinishing || isDestroyed) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("نسخة غير مفعّلة")
            .setMessage("هذه النسخة لم تُثبَّت من Google Play. يمكنك تحميل التطبيق من المتجر، أو التفعيل بكود ترويجي.")
            .setCancelable(false)
            .setPositiveButton("التفعيل بكود") { _, _ ->
                startActivity(Intent(this, com.alruwaili.garminalbarr.billing.VipActivationActivity::class.java))
                finish()
            }
            .setNeutralButton("فتح Google Play") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                }
                finish()
            }
            .setNegativeButton("إغلاق") { _, _ -> finish() }
            .show()
    }

    private fun showLicenseErrorDialog() {
        if (isFinishing || isDestroyed) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("خطأ في التحقق")
            .setMessage("تعذر التحقق من الترخيص. تأكد من اتصالك بالإنترنت وأن Google Play مثبت ومسجّل الدخول فيه.")
            .setCancelable(false)
            .setPositiveButton("إعادة المحاولة") { _, _ -> recreate() }
            .setNegativeButton("إغلاق") { _, _ -> finish() }
            .show()
    }

    private fun proceedToNextScreen(isTrialStarted: Boolean) {
        if (!isTrialStarted) {
            val intent = Intent(this@SplashActivity, WelcomeActivity::class.java)
            startActivity(intent)
        } else {
            val intent = Intent(this@SplashActivity, com.alruwaili.garminalbarr.legacymap.MainActivity::class.java)
            startActivity(intent)
        }

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /**
     * Routes based on current entitlement after startup checks.
     */
    private fun routeByEntitlement() {
        val subManager = (application as MyApplication).subscriptionManager
        val intent = Intent(this@SplashActivity, com.alruwaili.garminalbarr.legacymap.MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}
