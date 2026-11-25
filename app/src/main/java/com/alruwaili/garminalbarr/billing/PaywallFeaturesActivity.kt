package com.alruwaili.garminalbarr.billing

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.alruwaili.garminalbarr.MyApplication
import com.alruwaili.garminalbarr.databinding.ActivityPaywallFeaturesBinding
import kotlinx.coroutines.launch

class PaywallFeaturesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPaywallFeaturesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaywallFeaturesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as MyApplication
        val billingWrapper = app.billingClientWrapper
        billingWrapper.ensureConnected()

        // Close/Dismiss buttons finish features activity
        binding.btnClose.setOnClickListener {
            finish()
        }

        binding.btnLater.setOnClickListener {
            finish()
        }

        // Primary button launches Step 2 (options screen)
        binding.btnActivateAll.setOnClickListener {
            val intent = Intent(this, PaywallOptionsActivity::class.java)
            startActivity(intent)
        }

        // VIP activation button launches the VIP activation screen
        binding.btnVipActivation.setOnClickListener {
            val intent = Intent(this, VipActivationActivity::class.java)
            startActivity(intent)
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
}
