package com.navigationgps.desert.billing

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.SetOptions
import com.navigationgps.desert.MyApplication
import com.navigationgps.desert.R
import com.navigationgps.desert.databinding.ActivityVipActivationBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class VipActivationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVipActivationBinding
    private lateinit var countryCodeAdapter: CountryCodeAdapter
    private val db = FirebaseFirestore.getInstance()

    private var listenerRegistration: ListenerRegistration? = null
    private var selectedCountryCode = "+962"

    companion object {
        private const val SUPPORT_EMAIL = "saleemalruwaili1975@gmail.com"
        private const val EMAIL_SUBJECT = "تفعيل حساب VIP - تطبيق خرائط الصحراء الدليلة"
        private const val TIMEOUT_MS = 10000L
    }

    private val countryCodes = listOf(
        "+962" to "Jordan (الأردن)",
        "+966" to "Saudi Arabia (السعودية)",
        "+971" to "UAE (الإمارات)",
        "+965" to "Kuwait (الكويت)",
        "+973" to "Bahrain (البحرين)",
        "+974" to "Qatar (قطر)",
        "+968" to "Oman (عمان)",
        "+20" to "Egypt (مصر)",
        "+964" to "Iraq (العراق)",
        "+961" to "Lebanon (لبنان)",
        "+963" to "Syria (سوريا)",
        "+212" to "Morocco (المغرب)",
        "+216" to "Tunisia (تونس)",
        "+213" to "Algeria (الجزائر)",
        "+249" to "Sudan (السودان)",
        "+218" to "Libya (ليبيا)",
        "+967" to "Yemen (اليمن)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVipActivationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCountryCodeSpinner()
        setupClickListeners()
        checkExistingRequest()
    }

    private fun setupCountryCodeSpinner() {
        countryCodeAdapter = CountryCodeAdapter(this, countryCodes)
        binding.spinnerCountryCode.adapter = countryCodeAdapter
        binding.spinnerCountryCode.onItemSelectedListener = object :
            android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedCountryCode = countryCodeAdapter.getCodeForPosition(position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val defaultIndex = countryCodes.indexOfFirst { it.first == "+962" }
        if (defaultIndex >= 0) {
            binding.spinnerCountryCode.setSelection(defaultIndex)
        }
    }

    private fun setupClickListeners() {
        binding.btnSubmit.setOnClickListener {
            submitPhoneNumber()
        }

        binding.btnEditNumber.setOnClickListener {
            showInputState()
        }

        binding.btnEmail.setOnClickListener {
            openEmail()
        }
    }

    private fun checkExistingRequest() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                checkFirestoreForExistingRequest()
            }

            if (isFinishing || isDestroyed) return@launch

            if (result != null) {
                val (phone, status, _) = result
                when (status) {
                    "pending", "approved" -> {
                        showPendingState()
                        startVipListener()
                    }
                    "rejected" -> {
                        showInputState()
                        Toast.makeText(
                            this@VipActivationActivity,
                            getString(R.string.vip_activation_error),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    else -> showInputState()
                }
            } else {
                showInputState()
            }
        }
    }

    private suspend fun checkFirestoreForExistingRequest(): Triple<String, String, String>? {
        return try {
            val androidId = TrialRepository.getAndroidId(this)
            val doc = withTimeoutOrNull(TIMEOUT_MS) {
                db.collection("trials")
                    .document(androidId)
                    .get()
                    .await()
            } ?: return null

            if (!doc.exists()) return null

            val phone = doc.getString("vip_request_phone") ?: return null
            val status = doc.getString("vip_request_status") ?: return null
            val countryCode = doc.getString("vip_request_country_code") ?: "+962"

            Triple(phone, status, countryCode)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    private suspend fun checkExistingRequestSync(): Boolean {
        return try {
            val androidId = TrialRepository.getAndroidId(this)
            val doc = withTimeoutOrNull(TIMEOUT_MS) {
                db.collection("trials")
                    .document(androidId)
                    .get()
                    .await()
            } ?: return false
            doc.getString("vip_request_phone") != null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    private fun submitPhoneNumber() {
        val phoneNumber = binding.etPhoneNumber.text.toString().trim()

        if (!isValidPhoneNumber(phoneNumber)) {
            binding.etPhoneNumber.error = getString(R.string.vip_activation_invalid_phone)
            return
        }

        if (!TrialRepository.isNetworkAvailable(this)) {
            Toast.makeText(this, getString(R.string.vip_activation_no_network), Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnSubmit.isEnabled = false

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                submitToFirestore(selectedCountryCode, phoneNumber)
            }

            if (isFinishing || isDestroyed) return@launch

            if (success) {
                com.navigationgps.desert.analytics.AnalyticsHelper.logVipRequested(selectedCountryCode, android.os.Build.MODEL)
                val app = application as MyApplication
                val cleanNumber = phoneNumber.removePrefix("0")
                app.subscriptionManager.saveVipRequest(
                    "$selectedCountryCode$cleanNumber",
                    "pending"
                )
                showPendingState()
                startVipListener()
            } else {
                binding.btnSubmit.isEnabled = true
                Toast.makeText(
                    this@VipActivationActivity,
                    getString(R.string.vip_activation_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private suspend fun submitToFirestore(countryCode: String, phoneNumber: String): Boolean {
        return try {
            val androidId = TrialRepository.getAndroidId(this)
            val cleanNumber = phoneNumber.removePrefix("0")
            val fullPhone = "$countryCode$cleanNumber"
            val isEdit = checkExistingRequestSync()

            // Check if document exists to decide whether to include identity fields
            val docExists = withTimeoutOrNull(TIMEOUT_MS) {
                val doc = db.collection("trials")
                    .document(androidId)
                    .get(Source.SERVER)
                    .await()
                doc.exists()
            } ?: false

            val data = if (docExists) {
                // Normal existing-trial flow: only VIP request fields
                mapOf(
                    "vip_request_phone" to fullPhone,
                    "vip_request_country_code" to countryCode,
                    "vip_request_status" to "pending",
                    "vip_request_date" to FieldValue.serverTimestamp(),
                    "vip_request_device_model" to android.os.Build.MODEL,
                    "vip_request_edited" to isEdit
                )
            } else {
                // Genuinely absent document: include identity/trial fields for creation
                val appSetId = withTimeoutOrNull(5000L) {
                    try {
                        val client = com.google.android.gms.appset.AppSet.getClient(this@VipActivationActivity)
                        val info = client.appSetIdInfo.await()
                        info.id
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        "unavailable"
                    }
                } ?: "unavailable"

                mapOf(
                    "vip_request_phone" to fullPhone,
                    "vip_request_country_code" to countryCode,
                    "vip_request_status" to "pending",
                    "vip_request_date" to FieldValue.serverTimestamp(),
                    "vip_request_device_model" to android.os.Build.MODEL,
                    "vip_request_edited" to isEdit,
                    "android_id" to androidId,
                    "app_set_id" to appSetId,
                    "device_model" to android.os.Build.MODEL,
                    "trial_used" to true,
                    "trial_start" to FieldValue.serverTimestamp()
                )
            }

            withTimeoutOrNull(TIMEOUT_MS) {
                db.collection("trials")
                    .document(androidId)
                    .set(data, SetOptions.merge())
                    .await()
                true
            } ?: false
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    private fun startVipListener() {
        val app = application as MyApplication
        listenerRegistration = VipManager.listenForVipActivation(
            context = this,
            subscriptionManager = app.subscriptionManager,
            onActivated = {
                // Approval: persist VIP, show success, remove listener, start DesertMapActivity, finish
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(
                        this@VipActivationActivity,
                        getString(R.string.vip_activation_activated),
                        Toast.LENGTH_LONG
                    ).show()
                    listenerRegistration?.remove()
                    listenerRegistration = null
                    startActivity(Intent(this@VipActivationActivity, com.navigationgps.desert.map.DesertMapActivity::class.java))
                    finish()
                }
            },
            onRejected = {
                // Rejection: clear saved pending-request fields, remove listener, show dialog, then PaywallFeaturesActivity
                runOnUiThread {
                    app.subscriptionManager.clearVipRequest()
                    listenerRegistration?.remove()
                    listenerRegistration = null
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    androidx.appcompat.app.AlertDialog.Builder(this@VipActivationActivity)
                        .setTitle("تم رفض طلب التفعيل")
                        .setMessage("تم رفض طلب تفعيل حساب VIP. يمكنك المحاولة مرة أخرى أو التواصل مع الدعم.")
                        .setPositiveButton("حسنًا") { _, _ ->
                            startActivity(Intent(this@VipActivationActivity, com.navigationgps.desert.billing.PaywallFeaturesActivity::class.java))
                            finish()
                        }
                        .setCancelable(false)
                        .show()
                }
            }
        )
    }

    private fun showInputState() {
        binding.inputContainer.visibility = View.VISIBLE
        binding.pendingContainer.visibility = View.GONE
        binding.etPhoneNumber.text?.clear()
        binding.etPhoneNumber.requestFocus()
    }

    private fun showPendingState() {
        binding.inputContainer.visibility = View.GONE
        binding.pendingContainer.visibility = View.VISIBLE
        binding.btnSubmit.isEnabled = true
    }

    private fun openEmail() {
        val body = "مرحباً، أريد تفعيل حسابي في تطبيق خرائط الصحراء الدليلة\n\n" +
                "رقم الجهاز: ${android.os.Build.MODEL}"
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, EMAIL_SUBJECT)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.vip_activation_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun isValidPhoneNumber(phone: String): Boolean {
        return phone.isNotEmpty() && phone.length in 6..15 && phone.all { it.isDigit() || it == '+' }
    }

    override fun onDestroy() {
        listenerRegistration?.remove()
        super.onDestroy()
    }
}
