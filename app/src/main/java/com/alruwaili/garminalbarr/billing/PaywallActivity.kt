package com.alruwaili.garminalbarr.billing

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.alruwaili.garminalbarr.MyApplication
import com.alruwaili.garminalbarr.R
import com.alruwaili.garminalbarr.databinding.ActivityPaywallBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.browser.customtabs.CustomTabsIntent
import android.net.Uri

class PaywallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPaywallBinding
    private var activeProductId = "com.alruwaili.garminalbarr.three_months"

    private val privacyPolicyUrl = "https://www.termsfeed.com/live/6202f636-5ef8-4817-af60-43628a73ad57"
    private val termsOfServiceUrl = "https://www.termsfeed.com/live/0556887a-c821-4c81-9a4b-168461e409fe"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaywallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as MyApplication
        val billingWrapper = app.billingClientWrapper
        billingWrapper.ensureConnected()

        // Initialize with default selected card (3-Months)
        selectThreeMonths()

        // Fetch and display localized pricing details dynamically once billing client is connected
        lifecycleScope.launch {
            billingWrapper.isConnected.collect { connected ->
                if (connected) {
                    val productIds = BillingClientWrapper.ACTIVE_SUBSCRIPTION_IDS
                    val details = billingWrapper.queryProductDetails(productIds)
                    if (!details.isNullOrEmpty()) {
                        details.forEach { productDetails ->
                            val price = productDetails.subscriptionOfferDetails?.firstOrNull()
                                ?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
                            if (price != null) {
                                when (productDetails.productId) {
                                    "com.alruwaili.garminalbarr.monthly" -> {
                                        binding.txtPriceMonthly.text = price
                                    }
                                    "com.alruwaili.garminalbarr.three_months" -> {
                                        binding.txtPriceThreeMonths.text = price
                                    }
                                    "com.alruwaili.garminalbarr.yearly" -> {
                                        binding.txtPriceYearly.text = price
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Handle Close Button (btnClose)
        binding.btnClose.setOnClickListener {
            finish()
        }

        // Option Card Click Listeners
        binding.cardMonthly.setOnClickListener {
            selectMonthly()
        }
        binding.cardThreeMonths.setOnClickListener {
            selectThreeMonths()
        }
        binding.cardYearly.setOnClickListener {
            selectYearly()
        }

        // Prevent clicking radio buttons from breaking visual state by forwarding clicks to the parent card layouts
        binding.radioMonthly.setOnClickListener {
            selectMonthly()
        }
        binding.radioThreeMonths.setOnClickListener {
            selectThreeMonths()
        }
        binding.radioYearly.setOnClickListener {
            selectYearly()
        }

        // Handle Primary Button (btnUnlockFeatures) - Purchase flow
        binding.btnUnlockFeatures.setOnClickListener {
            lifecycleScope.launch {
                billingWrapper.launchPurchaseFlow(this@PaywallActivity, activeProductId)
            }
        }

        // Handle Restore Purchases Button
        binding.btnRestorePurchases.setOnClickListener {
            binding.btnRestorePurchases.isEnabled = false
            lifecycleScope.launch {
                val restored = billingWrapper.queryActivePurchases()
                if (!restored) {
                    android.widget.Toast.makeText(
                        this@PaywallActivity,
                        "لا توجد اشتراكات نشطة لاستعادتها",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                binding.btnRestorePurchases.isEnabled = true
            }
        }

        // Handle Privacy Policy Button
        binding.btnPrivacyPolicy.setOnClickListener {
            try {
                val builder = CustomTabsIntent.Builder()
                val customTabsIntent = builder.build()
                customTabsIntent.launchUrl(this@PaywallActivity, Uri.parse(privacyPolicyUrl))
            } catch (e: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(
                    this@PaywallActivity,
                    "لا يوجد متصفح ويب لفتح الرابط",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    this@PaywallActivity,
                    "تعذر فتح الرابط",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Handle Terms of Service Button
        binding.btnLegalFooter.setOnClickListener {
            try {
                val builder = CustomTabsIntent.Builder()
                val customTabsIntent = builder.build()
                customTabsIntent.launchUrl(this@PaywallActivity, Uri.parse(termsOfServiceUrl))
            } catch (e: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(
                    this@PaywallActivity,
                    "لا يوجد متصفح ويب لفتح الرابط",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    this@PaywallActivity,
                    "تعذر فتح الرابط",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Auto-dismiss the paywall if premium access has been successfully purchased
        lifecycleScope.launch {
            billingWrapper.isPurchased.collect { purchased ->
                if (purchased) {
                    finish()
                }
            }
        }
    }

    private fun selectMonthly() {
        activeProductId = "com.alruwaili.garminalbarr.monthly"

        binding.radioMonthly.isChecked = true
        binding.radioThreeMonths.isChecked = false
        binding.radioYearly.isChecked = false

        binding.cardMonthly.setBackgroundResource(R.drawable.bg_tier_card_selected)
        binding.cardThreeMonths.setBackgroundResource(R.drawable.bg_tier_card_unselected)
        binding.cardYearly.setBackgroundResource(R.drawable.bg_tier_card_unselected)
    }

    private fun selectThreeMonths() {
        activeProductId = "com.alruwaili.garminalbarr.three_months"

        binding.radioMonthly.isChecked = false
        binding.radioThreeMonths.isChecked = true
        binding.radioYearly.isChecked = false

        binding.cardMonthly.setBackgroundResource(R.drawable.bg_tier_card_unselected)
        binding.cardThreeMonths.setBackgroundResource(R.drawable.bg_tier_card_selected)
        binding.cardYearly.setBackgroundResource(R.drawable.bg_tier_card_unselected)
    }

    private fun selectYearly() {
        activeProductId = "com.alruwaili.garminalbarr.yearly"

        binding.radioMonthly.isChecked = false
        binding.radioThreeMonths.isChecked = false
        binding.radioYearly.isChecked = true

        binding.cardMonthly.setBackgroundResource(R.drawable.bg_tier_card_unselected)
        binding.cardThreeMonths.setBackgroundResource(R.drawable.bg_tier_card_unselected)
        binding.cardYearly.setBackgroundResource(R.drawable.bg_tier_card_selected)
    }
}
