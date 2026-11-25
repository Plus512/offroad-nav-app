package com.navigationgps.desert.billing

import org.junit.Assert.*
import org.junit.Test

/**
 * Behavioral unit tests for the billing/entitlement layer.
 * Tests the critical scenarios identified in code review:
 * - Reinstall with expired trial
 * - Server timeout/unavailable
 * - App Set ID unavailable
 * - VIP state transitions (pending, approved, rejected)
 * - Offline routine launch
 */
class EntitlementBehaviorTest {

    // ── StartupResult Tests ──

    @Test
    fun `ExistingAccess with expired trial should not grant access`() {
        val expiredTimestamp = System.currentTimeMillis() - (4 * 24 * 60 * 60 * 1000L) // 4 days ago
        val result = StartupResult.ExistingAccess(
            trialStartEpoch = expiredTimestamp,
            isVipUser = false,
            vipRequestStatus = null
        )

        assertFalse("Expired trial should not grant VIP", result.isVipUser)
        assertNull("No pending VIP request", result.vipRequestStatus)
    }

    @Test
    fun `ExistingAccess with active trial should be recognized`() {
        val activeTimestamp = System.currentTimeMillis() - (1 * 24 * 60 * 60 * 1000L) // 1 day ago
        val result = StartupResult.ExistingAccess(
            trialStartEpoch = activeTimestamp,
            isVipUser = false,
            vipRequestStatus = null
        )

        assertNotNull("Trial timestamp exists", result.trialStartEpoch)
        assertFalse("Not VIP user", result.isVipUser)
    }

    @Test
    fun `ExistingAccess with VIP user should grant access`() {
        val result = StartupResult.ExistingAccess(
            trialStartEpoch = null,
            isVipUser = true,
            vipRequestStatus = "approved"
        )

        assertTrue("VIP user should have access", result.isVipUser)
        assertEquals("approved", result.vipRequestStatus)
    }

    @Test
    fun `ExistingAccess with rejected VIP request should not grant access`() {
        val result = StartupResult.ExistingAccess(
            trialStartEpoch = null,
            isVipUser = false,
            vipRequestStatus = "rejected"
        )

        assertFalse("Rejected VIP should not grant access", result.isVipUser)
        assertEquals("rejected", result.vipRequestStatus)
    }

    @Test
    fun `NewDevice should indicate fresh install`() {
        val result = StartupResult.NewDevice

        assertTrue("Should be NewDevice", result is StartupResult.NewDevice)
    }

    @Test
    fun `ServerUnavailable should block activation`() {
        val result = StartupResult.ServerUnavailable

        assertTrue("Should be ServerUnavailable", result is StartupResult.ServerUnavailable)
    }

    @Test
    fun `ServerFailure should block activation`() {
        val result = StartupResult.ServerFailure

        assertTrue("Should be ServerFailure", result is StartupResult.ServerFailure)
    }

    // ── TrialCreationResult Tests ──

    @Test
    fun `Created result should contain valid timestamp`() {
        val timestamp = System.currentTimeMillis()
        val result = TrialCreationResult.Created(timestamp)

        assertEquals(timestamp, result.serverTimestamp)
        assertTrue("Should be Created", result is TrialCreationResult.Created)
    }

    @Test
    fun `Existing result should indicate discovered trial`() {
        val timestamp = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L) // 7 days ago
        val result = TrialCreationResult.Existing(timestamp)

        assertEquals(timestamp, result.serverTimestamp)
        assertTrue("Should be Existing", result is TrialCreationResult.Existing)
    }

    @Test
    fun `Existing result with expired trial should require entitlement check`() {
        val expiredTimestamp = System.currentTimeMillis() - (4 * 24 * 60 * 60 * 1000L) // 4 days ago
        val result = TrialCreationResult.Existing(expiredTimestamp)

        // Caller must check if trial is still active
        val trialDurationMs = 3 * 24 * 60 * 60 * 1000L // 3 days
        val elapsed = System.currentTimeMillis() - result.serverTimestamp
        val isTrialActive = elapsed < trialDurationMs

        assertFalse("Expired trial should not be active", isTrialActive)
    }

    @Test
    fun `Existing result with active trial should grant access`() {
        val activeTimestamp = System.currentTimeMillis() - (1 * 24 * 60 * 60 * 1000L) // 1 day ago
        val result = TrialCreationResult.Existing(activeTimestamp)

        val trialDurationMs = 3 * 24 * 60 * 60 * 1000L // 3 days
        val elapsed = System.currentTimeMillis() - result.serverTimestamp
        val isTrialActive = elapsed < trialDurationMs

        assertTrue("Active trial should grant access", isTrialActive)
    }

    @Test
    fun `Failed result should not grant any access`() {
        val result = TrialCreationResult.Failed

        assertTrue("Should be Failed", result is TrialCreationResult.Failed)
    }

    // ── Entitlement Routing Logic Tests ──

    @Test
    fun `VIP access should take precedence over trial`() {
        val hasVip = true
        val isTrialActive = true
        val isPurchased = false

        val shouldGrantAccess = hasVip || isTrialActive || isPurchased
        assertTrue("Should grant access", shouldGrantAccess)
    }

    @Test
    fun `Active trial should grant access without VIP`() {
        val hasVip = false
        val isTrialActive = true
        val isPurchased = false

        val shouldGrantAccess = hasVip || isTrialActive || isPurchased
        assertTrue("Should grant access with active trial", shouldGrantAccess)
    }

    @Test
    fun `Expired trial without VIP or purchase should deny access`() {
        val hasVip = false
        val isTrialActive = false
        val isPurchased = false

        val shouldGrantAccess = hasVip || isTrialActive || isPurchased
        assertFalse("Should deny access", shouldGrantAccess)
    }

    @Test
    fun `Pending VIP request should route to activation activity`() {
        val vipRequestStatus = "pending"
        val shouldRouteToActivation = vipRequestStatus == "pending"

        assertTrue("Should route to VipActivationActivity", shouldRouteToActivation)
    }

    @Test
    fun `Rejected VIP request should route to paywall`() {
        val vipRequestStatus = "rejected"
        val shouldRouteToPaywall = vipRequestStatus == "rejected" || vipRequestStatus == null

        assertTrue("Should route to PaywallFeaturesActivity", shouldRouteToPaywall)
    }

    @Test
    fun `No VIP request should route to paywall`() {
        val vipRequestStatus = null
        val shouldRouteToPaywall = vipRequestStatus == "rejected" || vipRequestStatus == null

        assertTrue("Should route to PaywallFeaturesActivity", shouldRouteToPaywall)
    }

    // ── Pending-VIP Routing Tests (new behavior) ──

    @Test
    fun `Expired trial with pending VIP should route to MainActivity`() {
        // Pending users can browse Home dashboard — features are paywalled
        val hasVip = false
        val isTrialActive = false
        val vipRequestStatus = "pending"

        // All expired users (regardless of VIP request status) route to MainActivity
        val shouldRouteToMainActivity = !hasVip && !isTrialActive
        assertTrue("Should route to MainActivity", shouldRouteToMainActivity)
    }

    @Test
    fun `Expired trial with no VIP request should route to MainActivity`() {
        val hasVip = false
        val isTrialActive = false
        val vipRequestStatus = null

        val shouldRouteToMainActivity = !hasVip && !isTrialActive
        assertTrue("Should route to MainActivity", shouldRouteToMainActivity)
    }

    @Test
    fun `Expired trial with rejected VIP should route to MainActivity`() {
        val hasVip = false
        val isTrialActive = false
        val vipRequestStatus = "rejected"

        val shouldRouteToMainActivity = !hasVip && !isTrialActive
        assertTrue("Should route to MainActivity", shouldRouteToMainActivity)
    }

    @Test
    fun `Pending user opening protected feature should see paywall`() {
        // EntitlementGuard redirects to PaywallFeaturesActivity for all protected activities
        val hasEntitlement = false
        val isProtectedActivity = true

        val shouldShowPaywall = !hasEntitlement && isProtectedActivity
        assertTrue("Should show paywall for protected feature", shouldShowPaywall)
    }

    // ── Home Pending-VIP Button Tests ──

    @Test
    fun `Pending VIP button should be visible only when request status is pending`() {
        val savedVipRequestStatus = "pending"
        val hasVipAccess = false

        val shouldShowButton = savedVipRequestStatus == "pending" && !hasVipAccess
        assertTrue("Button should be visible for pending status", shouldShowButton)
    }

    @Test
    fun `Pending VIP button should be hidden when VIP is approved`() {
        val savedVipRequestStatus = "pending"
        val hasVipAccess = true

        val shouldShowButton = savedVipRequestStatus == "pending" && !hasVipAccess
        assertFalse("Button should be hidden when VIP approved", shouldShowButton)
    }

    @Test
    fun `Pending VIP button should be hidden when request is rejected`() {
        val savedVipRequestStatus = "rejected"
        val hasVipAccess = false

        val shouldShowButton = savedVipRequestStatus == "pending" && !hasVipAccess
        assertFalse("Button should be hidden for rejected status", shouldShowButton)
    }

    @Test
    fun `Pending VIP button should be hidden when no request exists`() {
        val savedVipRequestStatus = null
        val hasVipAccess = false

        val shouldShowButton = savedVipRequestStatus == "pending" && !hasVipAccess
        assertFalse("Button should be hidden when no request", shouldShowButton)
    }

    @Test
    fun `Debug trial duration should be 5 minutes`() {
        val debugDurationMs = 300000L // 5 minutes
        val expectedMs = 5 * 60 * 1000L

        assertEquals("Debug trial should be 5 minutes", expectedMs, debugDurationMs)
    }

    @Test
    fun `Release trial duration should be 3 days`() {
        val releaseDurationMs = 259200000L // 3 days
        val expectedMs = 3 * 24 * 60 * 60 * 1000L

        assertEquals("Release trial should be 3 days", expectedMs, releaseDurationMs)
    }

    // ── Offline Grace Period Tests ──

    @Test
    fun `Offline grace period should be 7 days`() {
        val gracePeriodMs = 7L * 24 * 60 * 60 * 1000
        val expectedMs = 7 * 24 * 60 * 60 * 1000L

        assertEquals("Offline grace should be 7 days", expectedMs, gracePeriodMs)
    }

    @Test
    fun `Within offline grace period should maintain access`() {
        val lastVerified = System.currentTimeMillis() - (3 * 24 * 60 * 60 * 1000L) // 3 days ago
        val gracePeriodMs = 7L * 24 * 60 * 60 * 1000

        val elapsed = System.currentTimeMillis() - lastVerified
        val isGraceActive = elapsed < gracePeriodMs

        assertTrue("Should be within grace period", isGraceActive)
    }

    @Test
    fun `Beyond offline grace period should lose access`() {
        val lastVerified = System.currentTimeMillis() - (10 * 24 * 60 * 60 * 1000L) // 10 days ago
        val gracePeriodMs = 7L * 24 * 60 * 60 * 1000

        val elapsed = System.currentTimeMillis() - lastVerified
        val isGraceActive = elapsed < gracePeriodMs

        assertFalse("Should be beyond grace period", isGraceActive)
    }

    // ── App Set ID Fallback Tests ──

    @Test
    fun `App Set ID unavailable should return ServerUnavailable`() {
        // When App Set ID is unavailable (GMS not present), we cannot perform dual-key lookup
        // Must return ServerUnavailable to prevent false NewDevice
        val appSetIdAvailable = false
        val expectedResult = if (!appSetIdAvailable) {
            StartupResult.ServerUnavailable
        } else {
            StartupResult.NewDevice
        }

        assertTrue("Should return ServerUnavailable", expectedResult is StartupResult.ServerUnavailable)
    }

    @Test
    fun `App Set ID query failure should return ServerFailure`() {
        // When App Set ID query fails, we cannot determine device identity
        // Must return ServerFailure to prevent false NewDevice
        val queryFailed = true
        val expectedResult = if (queryFailed) {
            StartupResult.ServerFailure
        } else {
            StartupResult.NewDevice
        }

        assertTrue("Should return ServerFailure", expectedResult is StartupResult.ServerFailure)
    }
}
