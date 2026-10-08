package io.github.howard20181.hyperos.fcmlive

import org.junit.Assert.*
import org.junit.Test

class GmsRecoveryPolicyTest {
    private val absent = RecoveryObservation(10132, "wifi", RecoveryNetworkState.READY, GmsConnectionState.ABSENT)

    @Test fun establishedConnectionNeverRequestsRecovery() {
        val policy = GmsRecoveryPolicy()
        val decision = policy.observe(absent.copy(connection = GmsConnectionState.PRESENT), 0, null, compatibilityTrigger = true)
        assertEquals(GmsRecoveryPolicy.Decision.Stop("connection-present"), decision)
    }

    @Test fun absenceMustBeObservedTwiceWithEnoughSeparation() {
        val policy = GmsRecoveryPolicy()
        assertEquals(GmsRecoveryPolicy.Decision.Check(30_000), policy.observe(absent, 0, null))
        assertEquals(GmsRecoveryPolicy.Decision.Check(29_000), policy.observe(absent, 1000, null))
        assertEquals(GmsRecoveryPolicy.Decision.Request(false), policy.observe(absent, 30_000, null))
    }

    @Test fun unknownAndSleepGapsBreakTheAbsenceEvidence() {
        val policy = GmsRecoveryPolicy()
        policy.observe(absent, 0, null)
        assertTrue(policy.observe(absent.copy(connection = GmsConnectionState.UNKNOWN), 10_000, null) is GmsRecoveryPolicy.Decision.Stop)
        assertEquals(GmsRecoveryPolicy.Decision.Check(30_000), policy.observe(absent, 40_000, null))
        assertEquals(GmsRecoveryPolicy.Decision.Check(30_000), policy.observe(absent, 1_800_000, null))
        assertEquals(GmsRecoveryPolicy.Decision.Check(30_000), policy.observe(absent, 0, null))
    }

    @Test fun unavailableNetworkOrDisabledTargetDoesNotStartGenericRecovery() {
        for (network in listOf(RecoveryNetworkState.UNKNOWN, RecoveryNetworkState.OFFLINE)) {
            val policy = GmsRecoveryPolicy()
            assertTrue(policy.observe(absent.copy(network = network), 0, null) is GmsRecoveryPolicy.Decision.Stop)
            assertTrue(policy.observe(absent.copy(network = network), 60_000, null) is GmsRecoveryPolicy.Decision.Stop)
        }
        assertTrue(GmsRecoveryPolicy().observe(absent.copy(eligible = false), 0, null, true) is GmsRecoveryPolicy.Decision.Stop)
        assertTrue(GmsRecoveryPolicy().observe(absent.copy(uid = -1), 0, null, true) is GmsRecoveryPolicy.Decision.Stop)
        assertTrue(GmsRecoveryPolicy().observe(absent.copy(network = RecoveryNetworkState.OFFLINE), 0, null, true) is GmsRecoveryPolicy.Decision.Stop)
    }

    @Test fun retriesAreBoundedAndBackOff() {
        val policy = GmsRecoveryPolicy()
        policy.observe(absent, 0, null)
        assertEquals(GmsRecoveryPolicy.Decision.Request(false), policy.observe(absent, 30_000, null))
        policy.attempted(30_000, false)
        policy.observe(absent, 60_000, 30_000)
        assertEquals(GmsRecoveryPolicy.Decision.Check(60_000), policy.observe(absent, 90_000, 30_000))
        assertEquals(GmsRecoveryPolicy.Decision.Request(false), policy.observe(absent, 150_000, 30_000))
        policy.attempted(150_000, false)
        policy.observe(absent, 180_000, 150_000)
        assertEquals(GmsRecoveryPolicy.Decision.Check(150_000), policy.observe(absent, 300_000, 150_000))
        assertEquals(GmsRecoveryPolicy.Decision.Request(false), policy.observe(absent, 450_000, 150_000))
        policy.attempted(450_000, false)
        assertEquals(GmsRecoveryPolicy.Decision.Stop("retry-limit"), policy.observe(absent, 480_000, 450_000))
        assertEquals(GmsRecoveryPolicy.Decision.Stop("retry-limit"), policy.observe(absent, 1_800_000, 450_000, newSequence = true))
    }

    @Test fun onlyRealConnectivityOrNetworkChangeResetsRetryLimit() {
        val policy = GmsRecoveryPolicy()
        policy.observe(absent, 700_000, 600_000)
        repeat(3) { policy.attempted(700_000, false) }
        assertEquals(GmsRecoveryPolicy.Decision.Stop("retry-limit"), policy.observe(absent, 730_000, 700_000))
        policy.observe(absent.copy(connection = GmsConnectionState.PRESENT), 740_000, 700_000)
        assertEquals(GmsRecoveryPolicy.Decision.Check(30_000), policy.observe(absent, 750_000, 700_000))
        assertEquals(GmsRecoveryPolicy.Decision.Check(30_000), policy.observe(absent.copy(networkKey = "mobile"), 760_000, 700_000))
    }

    @Test fun compatibilityIsAnExplicitSingleAttemptNotAnUnknownFallback() {
        val policy = GmsRecoveryPolicy()
        val unknown = absent.copy(connection = GmsConnectionState.UNKNOWN)
        assertTrue(policy.observe(unknown, 0, null) is GmsRecoveryPolicy.Decision.Stop)
        assertEquals(GmsRecoveryPolicy.Decision.Request(true), policy.observe(unknown, 1000, null, compatibilityTrigger = true))
        policy.attempted(1000, true)
        assertTrue(policy.observe(unknown, 31_000, 1000) is GmsRecoveryPolicy.Decision.Stop)
        assertEquals(GmsRecoveryPolicy.Decision.Stop("compatibility-finished"), policy.observe(absent, 61_000, 1000))
        assertEquals(GmsRecoveryPolicy.Decision.Check(30_000), policy.observe(absent, 90_000, 1000, newSequence = true))
    }

    @Test fun networkChangesDoNotBypassGlobalCooldown() {
        val policy = GmsRecoveryPolicy()
        policy.observe(absent, 0, null)
        assertEquals(GmsRecoveryPolicy.Decision.Check(30_000), policy.observe(absent.copy(networkKey = "mobile"), 20_000, 10_000))
        assertEquals(GmsRecoveryPolicy.Decision.Check(20_000), policy.observe(absent.copy(networkKey = "mobile"), 50_000, 10_000))
        assertEquals(GmsRecoveryPolicy.Decision.Stop("compatibility-throttled"), policy.observe(absent, 55_000, 10_000, true))
    }

    @Test fun unavailableNetworkIdentityDoesNotResetRetryLimit() {
        val policy = GmsRecoveryPolicy()
        policy.observe(absent, 0, null)
        repeat(3) { policy.attempted(30_000, false) }
        policy.observe(absent.copy(networkKey = null, network = RecoveryNetworkState.UNKNOWN), 60_000, 30_000)
        assertEquals(GmsRecoveryPolicy.Decision.Stop("retry-limit"), policy.observe(absent, 120_000, 30_000))
    }

    @Test fun anotherUserCannotSatisfyThisUsersConnection() {
        val owner = GmsRecoveryPolicy()
        val clone = GmsRecoveryPolicy()
        assertTrue(owner.observe(absent.copy(connection = GmsConnectionState.PRESENT), 0, null) is GmsRecoveryPolicy.Decision.Stop)
        val missingClone = absent.copy(uid = 99910132)
        assertEquals(GmsRecoveryPolicy.Decision.Check(30_000), clone.observe(missingClone, 0, null))
        assertEquals(GmsRecoveryPolicy.Decision.Request(false), clone.observe(missingClone, 30_000, null))
    }
}
