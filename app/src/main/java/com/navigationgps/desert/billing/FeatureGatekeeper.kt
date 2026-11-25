package com.navigationgps.desert.billing

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.fragment.app.Fragment

object FeatureGatekeeper {

    private const val TAG = "FeatureGatekeeper"

    /**
     * Click-level gate for Activity context.
     * If the user has full access (trial active OR purchased), the action executes immediately.
     * If the user does NOT have access, the paywall activity is launched.
     */
    fun guardAction(activity: Activity, action: () -> Unit) {
        val subManager = SubscriptionManager.getInstance(activity)

        if (subManager.hasFullAccessWithGrace()) {
            action()
            Log.d(TAG, "Action ALLOWED")
        } else {
            val intent = Intent(activity, PaywallFeaturesActivity::class.java)
            activity.startActivity(intent)
            Log.d(TAG, "Action BLOCKED — paywall shown")
        }
    }

    /**
     * Click-level gate for Fragment context.
     */
    fun guardAction(fragment: Fragment, action: () -> Unit) {
        val context = fragment.requireContext()
        val activity = fragment.requireActivity()
        val subManager = SubscriptionManager.getInstance(context)

        if (subManager.hasFullAccessWithGrace()) {
            action()
            Log.d(TAG, "Action ALLOWED (Fragment)")
        } else {
            val intent = Intent(activity, PaywallFeaturesActivity::class.java)
            activity.startActivity(intent)
            Log.d(TAG, "Action BLOCKED (Fragment) — paywall shown")
        }
    }
}