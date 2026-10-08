package io.github.howard20181.hyperos.fcmlive

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RecoverySocketSnapshotTest {
    private val header = "sl local_address rem_address st tx_queue tr retrnsmt uid timeout inode"
    private fun row(uid: Int, port: String = "146C", state: String = "01") =
        "0: 0100007F:AAAA 0100007F:$port $state 00000000:00000000 00:00000000 00000000 $uid 0 12345"

    @Test fun completeAbsenceIsDifferentFromUnreadableTable() {
        assertEquals(GmsConnectionState.ABSENT, RecoverySocketSnapshot.states(listOf(header), listOf(header), setOf(10132))[10132])
        assertEquals(GmsConnectionState.UNKNOWN, RecoverySocketSnapshot.states(listOf(header), null, setOf(10132))[10132])
        assertEquals(GmsConnectionState.UNKNOWN, RecoverySocketSnapshot.states(listOf("bad header"), listOf(header), setOf(10132))[10132])
        assertEquals(GmsConnectionState.UNKNOWN, RecoverySocketSnapshot.states(listOf(header, "malformed row"), listOf(header), setOf(10132))[10132])
    }

    @Test fun positiveEvidenceFromOneFamilyIsEnoughButOtherUsersDoNotCount() {
        val states = RecoverySocketSnapshot.states(listOf(header, row(99910132)), null, setOf(10132, 99910132))
        assertEquals(GmsConnectionState.UNKNOWN, states[10132])
        assertEquals(GmsConnectionState.PRESENT, states[99910132])
        val other = RecoverySocketSnapshot.states(listOf(header, row(10100)), listOf(header), setOf(10132))
        assertEquals(GmsConnectionState.ABSENT, other[10132])
    }

    @Test fun httpsAndPendingConnectionsAreNotEstablishedPushConnections() {
        val states = RecoverySocketSnapshot.states(listOf(header, row(10132, "01BB"), row(10132, state = "02")), listOf(header), setOf(10132))
        assertEquals(GmsConnectionState.ABSENT, states[10132])
    }

    @Test fun boundedReaderRejectsOversizeTooManyLinesAndReadFailures() {
        assertEquals(listOf(header), RecoverySocketSnapshot.readTable { ByteArrayInputStream(header.toByteArray()) })
        assertNull(RecoverySocketSnapshot.readTable { ByteArrayInputStream(ByteArray(RecoverySocketSnapshot.MAX_BYTES + 1) { 32 }) })
        assertNull(RecoverySocketSnapshot.readTable { ByteArrayInputStream("\n".repeat(RecoverySocketSnapshot.MAX_LINES).toByteArray()) })
        assertNull(RecoverySocketSnapshot.readTable { throw IOException("not permitted") })
        assertNull(RecoverySocketSnapshot.readTable { ByteArrayInputStream(byteArrayOf(-1)) })
    }
}
