package io.github.howard20181.hyperos.fcmlive

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsParserTest {
    private val header = "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode"
    private fun socket(remote: String, state: String = "01", uid: Int = 10133) =
        "0: 0100007F:C000 $remote $state 00000000:00000000 00:00000000 00000000 $uid 0 456 1"

    @Test fun decodesIpv4AndIpv6() {
        assertEquals("127.0.0.1" to 5228, DiagnosticsParser.hexAddress("0100007F:146C"))
        assertEquals("0:0:0:0:0:0:0:1" to 5228, DiagnosticsParser.hexAddress("00000000000000000000000001000000:146C"))
        assertNull(DiagnosticsParser.hexAddress("garbage:146C"))
        assertNull(DiagnosticsParser.hexAddress("0100007F:10000"))
    }

    @Test fun doesNotConfuseHttpsWithPushOrFailureWithOffline() {
        val sockets = DiagnosticsParser.sockets(listOf(header, socket("0100007F:01BB"),
            socket("0100007F:146C"), socket("0100007F:146D", "02"), socket("0100007F:146C", uid = 11111)), 10133)!!
        assertEquals(3, sockets.size)
        assertFalse(sockets.first { it.port == 443 }.pushCandidate)
        assertEquals(1, sockets.count { it.established && it.pushCandidate })
        assertNull(DiagnosticsParser.sockets(listOf("Permission denied"), 10133))
        assertEquals(emptyList<DiagnosticsParser.Socket>(), DiagnosticsParser.sockets(listOf(header), 10133))
    }

    @Test fun greezeNeedsAnExplicitBoolean() {
        assertEquals(false, DiagnosticsParser.gmsLimitEnabled(listOf(" mGmsLimitEnabled : false")))
        assertEquals(true, DiagnosticsParser.gmsLimitEnabled(listOf("mGmsLimitEnabled=true other=false")))
        assertNull(DiagnosticsParser.gmsLimitEnabled(emptyList()))
        assertNull(DiagnosticsParser.gmsLimitEnabled(listOf("mGmsLimitEnabled=something")))
        assertNull(DiagnosticsParser.gmsLimitEnabled(listOf("mGmsLimitEnabled=false", "mGmsLimitEnabled=true")))
    }

    @Test fun findsPersistentAndColonProcessesOnlyInCorrectUser() {
        val ps = listOf("UID PID NAME", "10133 1 com.google.android.gms.persistent",
            "10133 2 com.google.android.gms:unstable", "10133 3 com.google.android.gms.evil",
            "110133 4 com.google.android.gms")
        assertEquals(listOf("com.google.android.gms.persistent", "com.google.android.gms:unstable"),
            DiagnosticsParser.processes(ps, 10133))
        assertNull(DiagnosticsParser.processes(listOf("access denied"), 10133))
        assertFalse(DiagnosticsParser.gmsProcessName("com.google.android.gmsfake"))
    }

    @Test fun unknownSamplingAndRebootNeverBecomeDisconnects() {
        val initial = DiagnosticsParser.observe(null, "boot1", 1000, true)!!
        assertEquals(0, initial.observedLosses)
        val absent = DiagnosticsParser.observe(initial, "boot1", 2000, false)!!
        assertEquals(1, absent.observedLosses)
        assertNull(DiagnosticsParser.observe(initial, "boot1", 2000, null))
        assertNull(DiagnosticsParser.observe(initial, null, 2000, false))
        assertEquals(0, DiagnosticsParser.observe(initial, "boot2", 2000, false)!!.observedLosses)
        assertEquals(0, DiagnosticsParser.observe(initial, "boot1", 90000, false)!!.observedLosses)
        assertEquals(1000L, DiagnosticsParser.observe(initial, "boot1", 2000, true)!!.since)
    }

    @Test fun malformedRowsMustNotBecomeAnEmptySuccessfulTable() {
        assertNull(DiagnosticsParser.sockets(listOf(header, "0: incomplete row"), 10133))
        assertNull(DiagnosticsParser.sockets(listOf(header, socket("invalid:146C")), 10133))
        assertNull(DiagnosticsParser.sockets(listOf(header, socket("0100007F:146C", "ZZ")), 10133))
        assertNull(DiagnosticsParser.processes(listOf("NAME UID PID", "com.google.android.gms 10133 1"), 10133))
        assertNull(DiagnosticsParser.processes(listOf("UID PID NAME", "u0_a133 1 com.google.android.gms"), 10133))
        assertEquals(emptyList<String>(), DiagnosticsParser.processes(listOf("UID PID NAME"), 10133))
        assertNull(DiagnosticsParser.gmsLimitEnabled(listOf("mGmsLimitEnabled=false mGmsLimitEnabled=true")))
    }

    @Test fun partialNetworkTableWithoutCandidateStaysUnknown() {
        val https = DiagnosticsParser.sockets(listOf(header, socket("0100007F:01BB")), 10133)
        val sample = GmsSample(10133, "test", emptyList(), https, null, "boot", 0,
            1000, true, "", false)
        assertNull(sample.observedOnline)
        assertEquals(false, sample.copy(socketReadComplete = true).observedOnline)
        assertNull(sample.copy(rootAvailable = false).observedOnline)
    }

    @Test fun milletAurogonAndDozeKeepUnreadableDistinctFromAbsent() {
        assertEquals(true, DiagnosticsParser.milletContainsGms(listOf("com.android.vending, com.google.android.gms")))
        assertEquals(false, DiagnosticsParser.milletContainsGms(listOf("com.android.vending")))
        assertEquals(false, DiagnosticsParser.milletContainsGms(listOf("null")))
        assertNull(DiagnosticsParser.milletContainsGms(emptyList()))
        assertNull(DiagnosticsParser.milletContainsGms(listOf("")))

        assertEquals(true, DiagnosticsParser.aurogonConfigured(listOf("broadcastctrl:true")))
        assertEquals(false, DiagnosticsParser.aurogonConfigured(listOf("null")))
        assertNull(DiagnosticsParser.aurogonConfigured(emptyList()))

        assertEquals(true, DiagnosticsParser.deviceIdleGms(listOf("system,com.google.android.gms,10133"))?.first)
        assertEquals(false, DiagnosticsParser.deviceIdleGms(listOf("system,com.example.app,10133"))?.first)
        assertNull(DiagnosticsParser.deviceIdleGms(emptyList()))
    }

    @Test fun socketOverviewUsesTheSameSnapshotAsDetails() {
        val sockets = DiagnosticsParser.sockets(listOf(header, socket("0100007F:146C")), 10133)
        val sample = GmsSample(10133, "test", emptyList(), sockets, null, "boot", 0,
            1000, true, "", true)
        // 即使进程表为空，概览也不能覆盖掉同一次采样中的已连接 socket。
        assertEquals(true, sample.observedOnline)
        assertNull(sample.copy(sockets = null, socketReadComplete = false).observedOnline)
    }
}
