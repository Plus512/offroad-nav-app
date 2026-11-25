package com.alruwaili.garminalbarr.billing

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.android.billingclient.api.Purchase
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.alruwaili.garminalbarr.BuildConfig
import java.security.MessageDigest

/**
 * يرسل رقم الطلب إلى Firestore لحظة الشراء (أو عند اكتشاف تجديد جديد)
 * حتى تظهر العملية في لوحة التحكم خلال دقائق بدل انتظار تقارير المبيعات.
 *
 * - المجموعة: new_orders / {orderId}
 * - التطبيق يُنشئ السجل فقط (القواعد تمنع القراءة والتعديل والحذف).
 * - سكربت المزامنة يتحقق من كل طلب عند Google قبل اعتماده، فأي سجل مزيّف يُهمَل.
 * - لا يؤثر على الشراء نفسه: أي خطأ هنا يُسجَّل ويُتجاهل.
 */
object OrderReporter {

    private const val TAG = "OrderReporter"
    private const val PREFS = "order_reporter"
    private const val KEY_SENT = "sent_order_ids"
    private const val APP_KEY = "desert_maps"

    fun report(context: Context, purchase: Purchase) {
        try {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

            val orderId = purchase.orderId
            val docId = if (!orderId.isNullOrBlank()) orderId
                        else "tok_" + sha256(purchase.purchaseToken).take(40)

            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val sent = prefs.getStringSet(KEY_SENT, emptySet()) ?: emptySet()
            if (docId in sent) return

            val data = hashMapOf(
                "orderId" to (orderId ?: ""),
                "productId" to (purchase.products.firstOrNull() ?: ""),
                "purchaseToken" to purchase.purchaseToken,
                "purchaseTimeMs" to purchase.purchaseTime,
                "packageName" to context.packageName,
                "app" to APP_KEY,
                "appVersion" to BuildConfig.VERSION_NAME,
                "deviceId" to deviceId(context),
                "createdAt" to FieldValue.serverTimestamp()
            )

            FirebaseFirestore.getInstance()
                .collection("new_orders")
                .document(docId)
                .set(data)
                .addOnSuccessListener {
                    prefs.edit().putStringSet(KEY_SENT, sent + docId).apply()
                    Log.d(TAG, "Order reported: $docId")
                }
                .addOnFailureListener { e ->
                    // إذا كان السجل موجوداً مسبقاً فالقواعد ترفض الكتابة — هذا طبيعي.
                    Log.w(TAG, "Order report failed ($docId): ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Order report error", e)
        }
    }

    private fun deviceId(context: Context): String = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
    } catch (e: Exception) { "" }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
