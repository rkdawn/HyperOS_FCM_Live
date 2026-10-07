package io.github.howard20181.hyperos.fcmlive

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsPresentationTest {
    private fun sample() = GmsSample(10133, "test", null, null, null, "boot", 0,
        1000, true, "原始依据", false)

    @Test fun unreadableDataNeverBecomesAHealthyService() {
        val rows = statusItems(sample(), LogRead(emptyList(), false, "读取失败"), false).associateBy { it.id }
        assertEquals(Verdict.OK, rows.getValue("root").verdict)
        for (id in listOf("process", "socket", "gate", "millet", "aurogon", "doze", "hook", "prefs", "logs")) {
            assertEquals(id, Verdict.UNKNOWN, rows.getValue(id).verdict)
        }
    }

    @Test fun milletAbsentIsFlaggedWithoutClaimingMessagesLost() {
        val rows = statusItems(sample().copy(milletContainsGms = false, aurogonConfigured = false, deviceIdleGms = true),
            null, true).associateBy { it.id }
        assertEquals(Verdict.ATTENTION, rows.getValue("millet").verdict)
        assertEquals(Verdict.INFO, rows.getValue("aurogon").verdict)
        assertEquals(Verdict.OK, rows.getValue("doze").verdict)
        assertTrue(rows.getValue("millet").detail.contains("不含 GMS"))
    }

    @Test fun connectedSocketDoesNotImplyProcessStoppedOrMessageDelivered() {
        val socket = DiagnosticsParser.Socket("1", "127.0.0.1", 5228, "01", "123")
        val rows = statusItems(sample().copy(processes = emptyList(), sockets = listOf(socket)), null, true)
            .associateBy { it.id }
        assertEquals(Verdict.UNKNOWN, rows.getValue("process").verdict)
        assertEquals(Verdict.OK, rows.getValue("socket").verdict)
        assertTrue(rows.getValue("socket").detail.contains("不能证明"))
        assertTrue(rows.getValue("process").detail.contains("不能据此断言"))
    }

    @Test fun explanationsKeepGateTrafficAndRecoveryWithinTheirEvidence() {
        fun explain(message: String) = explainModuleEvent(ModuleLogParser.Line("time", "time", "I", message, message))
        assertTrue(explain("fcm-gate: pkg=a.b caller=10133").contains("不代表"))
        assertTrue(explain("gms probe [startup]: rx=100B tx=50B (baseline)").contains("累计"))
        assertTrue(explain("gms probe [periodic]: rx=+10B tx=+5B").contains("自上次采样"))
        assertTrue(explain("P4: recovery broadcasts sent to GMS+GSF").contains("尚不能确认"))
        assertTrue(explain("GMS missing from doze whitelist").contains("与免打扰模式无关"))
    }
}
