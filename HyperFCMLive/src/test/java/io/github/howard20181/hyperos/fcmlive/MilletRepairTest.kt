package io.github.howard20181.hyperos.fcmlive

import org.junit.Assert.*
import org.junit.Test

class MilletRepairTest {
    @Test fun healthyOrUnknownSettingsAreNeverWritten() {
        for (raw in listOf(null, "null", "not a package", "com.google.android.gms")) {
            var writes = 0
            val result = MilletRepair.ensure({ raw }, { writes++; true })
            assertEquals(0, writes)
            assertEquals(if (raw == "com.google.android.gms") MilletRepair.Status.UNCHANGED else MilletRepair.Status.UNAVAILABLE, result.status)
        }
    }

    @Test fun successfulRepairPreservesTheLatestOtherEntries() {
        val reads = listOf("com.example.a", "com.example.a,com.example.b", "com.example.a,com.example.b,com.google.android.gms")
        var readIndex = 0
        var written = ""
        val result = MilletRepair.ensure({ reads[readIndex++] }, { written = it; true })
        assertEquals(MilletRepair.Status.REPAIRED, result.status)
        assertEquals(setOf("com.example.a", "com.example.b", "com.google.android.gms"), written.split(',').map { it.trim() }.toSet())
    }

    @Test fun falseWriteAndUnconfirmedReadNeverReportRepair() {
        assertEquals(MilletRepair.Status.WRITE_FAILED, MilletRepair.ensure({ "com.example.a" }, { false }).status)
        assertEquals(MilletRepair.Status.UNCONFIRMED, MilletRepair.ensure({ "com.example.a" }, { true }).status)
        var count = 0
        val lostEntry = MilletRepair.ensure({ if (++count <= 2) "com.example.a" else "com.google.android.gms" }, { true })
        assertEquals(MilletRepair.Status.UNCONFIRMED, lostEntry.status)
    }

    @Test fun racingRepairAndReadErrorsDoNotCauseExtraWrites() {
        var readIndex = 0
        var writes = 0
        val result = MilletRepair.ensure({ if (++readIndex == 1) "" else "com.google.android.gms" }, { writes++; true })
        assertEquals(MilletRepair.Status.UNCHANGED, result.status)
        assertEquals(0, writes)
        val failed = MilletRepair.ensure({ throw SecurityException("not permitted") }, { writes++; true })
        assertEquals(MilletRepair.Status.UNAVAILABLE, failed.status)
        assertEquals(0, writes)
    }
}
