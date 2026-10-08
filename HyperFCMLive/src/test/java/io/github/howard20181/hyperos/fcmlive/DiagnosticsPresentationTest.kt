package io.github.howard20181.hyperos.fcmlive

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsPresentationTest {
    private fun sample() = GmsSample(10132, "test", null, null, null, "boot", 1000, 10000, true, "原始依据", false)

    @Test fun rejectedRootIsNotReportedAsConfirmed() {
        val s = sample().copy(rootAvailable = false, raw = "UID 0 未取得")
        val rows = statusItems(s, null, true)
        assertEquals(1, rows.size)
        assertEquals(Verdict.UNKNOWN, rows.first().verdict)
        assertTrue(rows.first().detail.contains("UID 0 未取得"))
        assertEquals(Verdict.UNKNOWN, overallSummary(s, null, true).second)
    }

    @Test fun unreadableDataStaysUnknown() {
        val rows = statusItems(sample(), LogRead(emptyList(), false, "读取失败"), false).associateBy { it.id }
        assertEquals(Verdict.OK, rows.getValue("root").verdict)
        for (id in listOf("process", "socket", "gate", "millet", "aurogon", "doze", "hook", "prefs", "logs")) {
            assertEquals(id, Verdict.UNKNOWN, rows.getValue(id).verdict)
        }
    }

    @Test fun socketIsOnlyConnectionEvidence() {
        val socket = DiagnosticsParser.Socket("1", "127.0.0.1", 5228, "01", "123", 10132)
        val s = sample().copy(processes = emptyList(), sockets = listOf(socket), gmsLimitEnabled = false,
            milletContainsGms = true, deviceIdleGms = true)
        val rows = statusItems(s, null, true).associateBy { it.id }
        assertEquals(Verdict.UNKNOWN, rows.getValue("process").verdict)
        assertEquals(Verdict.INFO, rows.getValue("socket").verdict)
        assertTrue(rows.getValue("socket").detail.contains("还需要"))
        assertNotEquals(Verdict.OK, overallSummary(s, null, true).second)
        assertEquals(3, primaryStatus(s, null, true).size)
    }

    @Test fun missingMilletIsNotProofOfModuleFailure() {
        val row = statusItems(sample().copy(milletContainsGms = false), null, false).first { it.id == "millet" }
        assertEquals(Verdict.ATTENTION, row.verdict)
        assertFalse(row.detail.contains("模块没起作用"))
        assertTrue(row.detail.contains("可"))
    }

    private fun event(stamp: String, level: String = "I", message: String = "P3: rewrote GMS scenario 0 -> 8", process: String = "system") =
        ModuleLogParser.parse("[ $stamp 1000: 1: 2 $level/LSPosedFramework ] ($process)[${ModuleLogParser.MODULE_PACKAGE},HyperGreeze,run,0,1] $message")!!

    @Test fun historyAndFiveErrorsDoNotProveCurrentModuleHealthy() {
        val old = event("2026-10-07 12:00:00.000", message = "HyperFCMLive active in system_server: 29 hook(s) installed")
        val oldTime = ModuleLogParser.epochMillis(old)!!
        val s = sample().copy(bootEpochMs = oldTime + 1000, nowMs = oldTime + 2000)
        val logs = LogRead(listOf(old), true, "历史")
        assertEquals(Verdict.UNKNOWN, statusItems(s, logs, true).first { it.id == "hook" }.verdict)
        val errors = List(5) { event("2026-10-07 12:00:01.500", "E", "failed $it") }
        val row = statusItems(s, LogRead(errors, true, "本次"), true).first { it.id == "hook" }
        assertEquals(Verdict.ATTENTION, row.verdict)
        assertFalse(row.detail.contains("正常工作"))
    }

    @Test fun scopeEvidenceDoesNotInventAnotherScope() {
        val e = event("2026-10-07 12:00:01.500")
        val t = ModuleLogParser.epochMillis(e)!!
        val s = sample().copy(bootEpochMs = t - 1000, nowMs = t + 1000)
        val row = statusItems(s, LogRead(listOf(e), true, "test"), true).first { it.id == "hook" }
        assertEquals(Verdict.INFO, row.verdict)
        assertTrue(row.detail.contains("系统侧"))
        assertFalse(row.detail.contains("电源管理侧"))
        assertTrue(row.detail.contains("不代表全部"))
    }

    @Test fun explanationsDoNotTurnAttemptsIntoDelivery() {
        fun explain(m: String) = explainModuleEvent(ModuleLogParser.Line("time", "time", "I", m, m))
        assertTrue(explain("fcm-gate: pkg=a.b").contains("不代表"))
        assertTrue(explain("gms probe [startup]: rx=100B tx=50B (baseline)").contains("累计"))
        assertTrue(explain("gms probe [periodic]: rx=+10B tx=+5B").contains("自上次采样"))
        assertTrue(explain("P4: recovery broadcasts sent").contains("尚不能确认"))
        assertTrue(explain("GMS missing from doze whitelist").contains("与免打扰模式无关"))
        assertTrue(explain("userTable: query failed").contains("失败"))
    }
}
