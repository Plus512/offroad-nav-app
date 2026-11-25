package com.navigationgps.desert

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.navigationgps.desert.billing.PaywallFeaturesActivity
import com.navigationgps.desert.billing.SubscriptionManager
import com.navigationgps.desert.billing.TrialCreationResult
import com.navigationgps.desert.databinding.ActivityWelcomeBinding
import kotlinx.coroutines.launch


class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private lateinit var subscriptionManager: SubscriptionManager
    private var serverBlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        try {
            setContentView(binding.root)
        } catch (t: Throwable) {
            android.util.Log.e("CH_CRASH", "setContentView failed: ${t.message}", t)
        }

        subscriptionManager = (application as MyApplication).subscriptionManager
        serverBlocked = intent.getBooleanExtra("server_blocked", false)

        val app = application as MyApplication
        val sharedVM = app.sharedLocationViewModel

        var isFirstCollection = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sharedVM.globalLocationsCache.collect { state ->
                    when (state) {
                        is CacheState.Ready -> {
                            if (isFirstCollection) {
                                binding.loadingContainer.visibility = View.GONE
                                binding.btnContinue.isEnabled = true
                                binding.btnContinue.alpha = 1.0f
                                isFirstCollection = false
                            } else {
                                binding.loadingContainer.animate()
                                    .alpha(0f)
                                    .setDuration(300)
                                    .withEndAction {
                                        if (!isFinishing && !isDestroyed) {
                                            binding.loadingContainer.visibility = View.GONE
                                        }
                                    }
                                binding.btnContinue.isEnabled = true
                                binding.btnContinue.animate()
                                    .alpha(1.0f)
                                    .setDuration(300)
                            }
                        }
                        is CacheState.Loading -> {
                            if (isFirstCollection) {
                                binding.loadingContainer.visibility = View.VISIBLE
                                binding.loadingContainer.alpha = 1.0f
                                binding.btnContinue.isEnabled = false
                                binding.btnContinue.alpha = 0.5f
                                isFirstCollection = false
                            }
                        }
                        is CacheState.Error -> {
                            binding.loadingContainer.visibility = View.GONE
                            binding.btnContinue.isEnabled = true
                            binding.btnContinue.alpha = 1.0f
                            isFirstCollection = false
                        }
                    }
                }
            }
        }

        binding.btnContinue.setOnClickListener {
            if (serverBlocked) {
                // Server was unavailable during startup — show activation-blocked dialog
                showActivationBlockedDialog()
                return@setOnClickListener
            }

            if (!isNetworkAvailable()) {
                showNoInternetDialog()
                return@setOnClickListener
            }

            binding.btnContinue.isEnabled = false
            lifecycleScope.launch {
                val result = subscriptionManager.startTrial()
                if (!isFinishing && !isDestroyed) {
                    when (result) {
                        is TrialCreationResult.Created -> {
                            // New trial created — route through entitlement
                            routeByEntitlement()
                        }
                        is TrialCreationResult.Existing -> {
                            // Existing trial discovered — must check if still active
                            routeByEntitlement()
                        }
                        is TrialCreationResult.Failed -> {
                            binding.btnContinue.isEnabled = true
                            showTrialFailedDialog()
                        }
                    }
                }
            }
        }

        binding.btnActivateNow.setOnClickListener {
            val intent = Intent(this, PaywallFeaturesActivity::class.java)
            startActivity(intent)
        }
    }

    /**
     * Checks if the device has an active internet connection.
     * Required before starting trial to ensure Firestore can verify
     * this device hasn't already used a trial (prevents offline bypass).
     */
    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showNoInternetDialog() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("يرجى الاتصال بالإنترنت")
            .setMessage("يجب الاتصال بالإنترنت عند أول استخدام للتطبيق لتفعيل الفترة التجريبية المجانية.")
            .setPositiveButton("حسنًا", null)
            .show()
    }

    private fun showTrialFailedDialog() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("فشل تفعيل الفترة التجريبية")
            .setMessage("تعذر تفعيل الفترة التجريبية. تأكد من اتصالك بالإنترنت وحاول مرة أخرى.")
            .setPositiveButton("إعادة المحاولة") { _, _ ->
                binding.btnContinue.isEnabled = true
                binding.btnContinue.performClick()
            }
            .setNegativeButton("إغلاق", null)
            .setCancelable(false)
            .show()
    }

    private fun showActivationBlockedDialog() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("يرجى الاتصال بالإنترنت")
            .setMessage("يجب الاتصال بالإنترنت عند أول استخدام للتطبيق لتفعيل الفترة التجريبية المجانية.")
            .setPositiveButton("إعادة المحاولة") { _, _ ->
                // Allow user to retry trial activation
                if (isNetworkAvailable()) {
                    serverBlocked = false
                    binding.btnContinue.isEnabled = true
                    binding.btnContinue.performClick()
                } else {
                    // Still offline — show again
                    showActivationBlockedDialog()
                }
            }
            .setNegativeButton("إغلاق", null)
            .setCancelable(false)
            .show()
    }

    /**
     * Routes based on current entitlement after trial creation/restoration.
     * Ensures expired trials are not granted access to core features.
     */
    private fun routeByEntitlement() {
        if (isFinishing || isDestroyed) return

        val intent = Intent(this, com.navigationgps.desert.map.DesertMapActivity::class.java)
        startActivity(intent)
        finish()
    }
}
