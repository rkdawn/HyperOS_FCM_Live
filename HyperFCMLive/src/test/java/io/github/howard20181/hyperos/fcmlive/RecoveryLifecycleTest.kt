package io.github.howard20181.hyperos.fcmlive

import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RecoveryLifecycleTest {
    @Test fun retiredGenerationsCannotSendOrRetireTheirReplacement() {
        val properties = Properties()
        val old = RecoveryEpoch(properties)
        assertTrue(old.claim(10132, 1000))
        old.retire()
        val current = RecoveryEpoch(properties)
        assertFalse(old.active())
        assertFalse(old.claim(10132, 100_000))
        old.retire()
        assertTrue(current.active())
        assertFalse(current.claim(10132, 60_000))
        assertTrue(current.claim(10132, 61_000))
    }

    @Test fun cooldownIsPerUidAndOnlyOneConcurrentClaimWins() {
        val epoch = RecoveryEpoch(Properties())
        val start = CountDownLatch(1)
        val won = AtomicInteger(0)
        val threads = List(8) { Thread { start.await(); if (epoch.claim(10132, 1000)) won.incrementAndGet() } }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, won.get())
        assertTrue(epoch.claim(99910132, 1000))
        assertFalse(epoch.claim(-1, 1000))
    }

    @Test fun pendingWorkIsMergedAndNetworkGraceCanPostponeIt() {
        val schedule = RecoverySchedule()
        assertTrue(schedule.request(0, 30_000, 0))
        assertFalse(schedule.request(1000, 30_000, 0))
        assertEquals(30_000L, schedule.dueAt)
        assertTrue(schedule.request(2000, 30_000, 32_000, replace = true))
        assertEquals(32_000L, schedule.dueAt)
        assertFalse(schedule.request(3000, 0, 32_000))
        schedule.clear()
        assertNull(schedule.dueAt)
    }

    @Test fun internalRequestsRequireBothRealUidAndSystemProvidedPackage() {
        assertTrue(ModuleGuards.authorizedRecoverySender(1000, "com.miui.powerkeeper", 1000))
        assertFalse(ModuleGuards.authorizedRecoverySender(-1, "com.miui.powerkeeper", 1000))
        assertFalse(ModuleGuards.authorizedRecoverySender(10132, "com.miui.powerkeeper", 1000))
        assertFalse(ModuleGuards.authorizedRecoverySender(1000, "com.example.fake", 1000))
        assertFalse(ModuleGuards.authorizedRecoverySender(1000, null, 1000))
        assertFalse(ModuleGuards.authorizedRecoverySender(1000, "com.miui.powerkeeper", null))
        assertFalse(ModuleGuards.authorizedRecoverySender(99910132, "com.miui.powerkeeper", 99910132))
    }
}
