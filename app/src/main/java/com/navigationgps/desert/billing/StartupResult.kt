package com.navigationgps.desert.billing

/**
 * Represents the result of a startup lookup from Firestore.
 * Used to determine access state on fresh install/reinstall.
 */
sealed class StartupResult {
    /**
     * No trial record found for this device — fresh install.
     */
    data object NewDevice : StartupResult()

    /**
     * Existing trial/VIP record found on server.
     * Contains the verified server-side state.
     */
    data class ExistingAccess(
        val trialStartEpoch: Long?,
        val isVipUser: Boolean,
        val vipRequestStatus: String?,
        // تضعه المزامنة/اللوحة عندما يفشل التحقق من الشراء عند Google (شراء مزيّف أو مسترد)
        val purchaseRevoked: Boolean = false
    ) : StartupResult()

    /**
     * Firestore is unreachable (network timeout, offline, etc.).
     * Must not create trial — show activation-blocked UI.
     */
    data object ServerUnavailable : StartupResult()

    /**
     * Firestore returned an error (permission denied, etc.).
     * Must not create trial — show activation-blocked UI.
     */
    data object ServerFailure : StartupResult()
}