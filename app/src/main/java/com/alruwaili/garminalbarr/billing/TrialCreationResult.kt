package com.alruwaili.garminalbarr.billing

/**
 * Represents the outcome of a server-acknowledged trial creation attempt.
 * Distinguishes between a genuinely new trial and an existing one discovered during recheck.
 */
sealed class TrialCreationResult {
    /**
     * A new trial was atomically created on the server.
     * Contains the server-assigned timestamp.
     */
    data class Created(val serverTimestamp: Long) : TrialCreationResult()

    /**
     * An existing trial was discovered during the server recheck.
     * Contains the original server timestamp (may be expired).
     * Caller must route through entitlement — do not assume active trial.
     */
    data class Existing(val serverTimestamp: Long) : TrialCreationResult()

    /**
     * Creation failed due to network timeout, Firestore error, or conflict.
     * No local trial should be granted.
     */
    data object Failed : TrialCreationResult()
}
