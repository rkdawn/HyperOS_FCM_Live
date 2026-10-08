package io.github.howard20181.hyperos.fcmlive

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DiagnosticsParserTest {
    private val header = "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode"
    private fun socket(remote: String, state: String = "01", uid: Int = 10132) =
        "0: 0100007F:C000 $remote $state 00000000:00000000 00:00000000 00000000 $uid 0 456 1"

    @Test fun decodesIpv4AndIpv6() {
        assertEquals("127.0.0.1" to 5228, DiagnosticsParser.hexAddress("0100007F:146C"))
        assertEquals("0:0:0:0:0:0:0:1" to 5228, DiagnosticsParser.hexAddress("00000000000000000000000001000000:146C"))
        assertNull(DiagnosticsParser.hexAddress("garbage:146C"))
        assertNull(DiagnosticsParser.hexAddress("0100007F:10000"))
    }

    @Test fun foreignAppOnPushPortIsNeverGms() {
        val sockets = DiagnosticsParser.allGmsSockets(listOf(header, socket("0100007F:146C", uid = 11111),
            socket("0100007F:146D", uid = 99910132), socket("0100007F:01BB")), setOf(10132, 99910132))!!
        assertEquals(setOf(10132, 99910132), sockets.map { it.uid }.toSet())
        assertFalse(sockets.first { it.port == 443 }.pushCandidate)
        assertEquals(1, sockets.count { it.established && it.pushCandidate })
        assertEquals(emptyList<DiagnosticsParser.Socket>(), DiagnosticsParser.sockets(listOf(header, socket("0100007F:146C", uid = 11111)), 10132))
        assertNull(DiagnosticsParser.allGmsSockets(listOf(header), emptySet()))
    }

    @Test fun readFailureAndEmptyAreDifferent() {
        assertNull(DiagnosticsParser.sockets(listOf("Permission denied"), 10132))
        assertEquals(emptyList<DiagnosticsParser.Socket>(), DiagnosticsParser.sockets(listOf(header), 10132))
        assertNull(DiagnosticsParser.sockets(listOf(header, "0: incomplete row"), 10132))
        assertNull(DiagnosticsParser.sockets(listOf(header, socket("invalid:146C")), 10132))
        assertNull(DiagnosticsParser.sockets(listOf(header, socket("0100007F:146C", "ZZ")), 10132))
    }

    @Test fun processIdentityUsesExactNameAndVerifiedAppId() {
        val ps = listOf("UID PID NAME", "10132 1 com.google.android.gms", "99910132 2 com.google.android.gms",
            "99910132 3 com.google.android.gms.unstable", "10132 4 com.google.android.gms.unstable",
            "11111 5 com.google.android.gms", "10132 6 com.google.android.gms.unstablefake", "10132 7 com.google.android.gms.evil")
        val rows = DiagnosticsParser.allGmsProcesses(ps, 10132)!!
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.pid })
        assertEquals(setOf(10132, 99910132), rows.map { it.uid }.toSet())
        assertEquals(2, rows.count { it.name.endsWith(".unstable") })
        assertFalse(DiagnosticsParser.gmsProcessName("com.google.android.gmsfake"))
        assertFalse(DiagnosticsParser.gmsProcessName("com.google.android.gms.unstablefake"))
    }

    @Test fun processColumnsMayMoveAndUserNamesMayBeUsed() {
        assertEquals(listOf("com.google.android.gms"), DiagnosticsParser.processes(listOf("NAME UID PID", "com.google.android.gms 10132 1"), 10132))
        assertEquals(listOf("com.google.android.gms"), DiagnosticsParser.processes(listOf("pid name uid", "1 com.google.android.gms 10132"), 10132))
        assertEquals(listOf("com.google.android.gms"), DiagnosticsParser.processes(listOf("USER PID NAME", "u999_a132 1 com.google.android.gms"), 99910132))
        assertNull(DiagnosticsParser.processes(listOf("USER PID PPID", "10132 1 2"), 10132))
        assertEquals(emptyList<String>(), DiagnosticsParser.processes(listOf("UID PID NAME"), 10132))
    }

    @Test fun negativeAndSystemUidsAreExcluded() {
        assertTrue(DiagnosticsParser.isAppUid(10132))
        assertTrue(DiagnosticsParser.isAppUid(99910132))
        assertTrue(DiagnosticsParser.isAppUid(1010132))
        assertFalse(DiagnosticsParser.isAppUid(-10132))
        assertFalse(DiagnosticsParser.isAppUid(1000))
    }

    @Test fun greezeNeedsExplicitUnambiguousBoolean() {
        assertEquals(false, DiagnosticsParser.gmsLimitEnabled(listOf(" mGmsLimitEnabled : false")))
        assertEquals(true, DiagnosticsParser.gmsLimitEnabled(listOf("mGmsLimitEnabled=true other=false")))
        assertNull(DiagnosticsParser.gmsLimitEnabled(emptyList()))
        assertNull(DiagnosticsParser.gmsLimitEnabled(listOf("mGmsLimitEnabled=something")))
        assertNull(DiagnosticsParser.gmsLimitEnabled(listOf("mGmsLimitEnabled=false mGmsLimitEnabled=true")))
    }

    @Test fun absentOrInvalidSettingsNeverImplySuccess() {
        assertEquals(true, DiagnosticsParser.milletContainsGms(listOf("com.android.vending, com.google.android.gms")))
        assertEquals(false, DiagnosticsParser.milletContainsGms(listOf("com.android.vending")))
        assertEquals(false, DiagnosticsParser.milletContainsGms(listOf("")))
        assertNull(DiagnosticsParser.milletContainsGms(listOf("null")))
        assertNull(DiagnosticsParser.milletContainsGms(listOf("Permission denied")))
        assertNull(DiagnosticsParser.milletContainsGms(listOf("com.android.vending'; cmd")))
        assertEquals(false, DiagnosticsParser.aurogonConfigured(listOf("null")))
        assertNull(DiagnosticsParser.aurogonConfigured(listOf("Error: denied")))
    }

    @Test fun dozeRequiresExactPackageAndRecognizedTable() {
        assertEquals(true, DiagnosticsParser.deviceIdleGms(listOf("system,com.google.android.gms,10132"))?.first)
        assertEquals(false, DiagnosticsParser.deviceIdleGms(listOf("system,com.google.android.gms.fake,11111"))?.first)
        assertNull(DiagnosticsParser.deviceIdleGms(listOf("Permission denied")))
        assertNull(DiagnosticsParser.deviceIdleGms(emptyList()))
    }

    @Test fun separateUsersCannotMaskMissingOwnerConnection() {
        val rows = DiagnosticsParser.allGmsSockets(listOf(header, socket("0100007F:146C", uid = 99910132)), setOf(10132, 99910132))
        val sample = GmsSample(10132, "test", emptyList(), rows, null, "boot", 0, 1000, true, "", true)
        assertEquals(false, sample.observedOnline)
        assertNull(sample.copy(socketReadComplete = false).observedOnline)
        assertNull(sample.copy(rootAvailable = false).observedOnline)
        assertEquals(true, sample.copy(sockets = DiagnosticsParser.sockets(listOf(header, socket("0100007F:146C")), 10132)).observedOnline)
    }

    @Test fun missingSampleRebootAndLargeGapsDoNotCountAsLosses() {
        val first = DiagnosticsParser.observe(null, "boot", 1000, true)!!
        assertEquals(1, DiagnosticsParser.observe(first, "boot", 2000, false)!!.observedLosses)
        assertNull(DiagnosticsParser.observe(first, "boot", 2000, null))
        assertEquals(0, DiagnosticsParser.observe(first, "other", 2000, false)!!.observedLosses)
        assertEquals(0, DiagnosticsParser.observe(first, "boot", 90000, false)!!.observedLosses)
        assertEquals(1000L, DiagnosticsParser.observe(first, "boot", 2000, true)!!.since)
    }

    private val base = LocalDateTime.of(2026, 10, 8, 12, 0)
    private val now = base.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun line(hoursAgo: Long, message: String): ModuleLogParser.Line {
        val stamp = base.minusHours(hoursAgo).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
        return ModuleLogParser.parse("[ $stamp 1000: 1: 2 I/HyperGreeze ] $message")!!
    }

    @Test fun windowsUseNowBoundariesAndDeduplicateEveryCategory() {
        val today = line(2, "fcm-gate: pkg=com.example.one")
        val routine = line(100, "userTable: GMS current bgControl=noRestrict")
        val data = listOf(today, today, line(5, "fcm-gate: pkg=com.example.one"),
            line(30, "fcm-gate: pkg=com.example.two"), line(50, "fcm-gate: pkg=com.example.one"), routine, routine,
            line(-1, "fcm-gate: pkg=com.example.future"), line(200, "fcm-gate: pkg=com.example.old"))
        val stats = DiagnosticsParser.windowStats(data, now)
        assertEquals(listOf(2, 4, 4), stats.map { it.totalGates })
        assertEquals(listOf(1, 3, 4), stats.map { it.coveredDays })
        assertEquals(1, stats.last().actionCounts.values.sum())
        assertFalse(stats.any { "com.example.future" in it.gateRecords || "com.example.old" in it.gateRecords })
    }

    @Test fun boundaryIncludedAndStaleDataNotShiftedIntoToday() {
        assertEquals(1, DiagnosticsParser.windowStats(listOf(line(24, "fcm-gate: pkg=com.example.one")), now).first().totalGates)
        assertEquals(0, DiagnosticsParser.windowStats(listOf(line(200, "fcm-gate: pkg=com.example.one")), now).first().totalGates)
        assertTrue(DiagnosticsParser.windowStats(listOf(ModuleLogParser.Line("10-08 01:00:00.000", "01:00:00", "I", "x", "x")), now).isEmpty())
    }
}
