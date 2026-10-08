package io.github.howard20181.hyperos.fcmlive

import org.junit.Assert.*
import org.junit.Test

class LogExplanationTest {
    private fun line(message: String, second: Int = 0, level: String = "I") = ModuleLogParser.Line(
        "2026-10-08 12:00:${second.toString().padStart(2, '0')}.000", "12:00:00", level, message,
        "time=$second level=$level message=$message")

    @Test fun everyExplanationAnswersThreePlainQuestions() {
        val examples = listOf("userTable: ensure GMS bgControl via powerkeeper",
            "userTable: GMS current bgControl=noRestrict", "P3: rewrote GMS scenario 0 -> 8",
            "fcm-gate: pkg=com.example.app caller=10132", "standby-firewall: suppressed command #10",
            "wake-path probe: checkWakePath DENIED #1", "Something new")
        for (m in examples) {
            val e = interpretLogEvent(line(m))
            assertTrue(e.happened.isNotBlank())
            assertTrue(e.impact.isNotBlank())
            assertTrue(e.advice.isNotBlank())
            assertEquals(3, e.text.lines().size)
            assertEquals(e.text, explainModuleEvent(line(m)))
        }
    }

    @Test fun readsAttemptsAndConfirmedWritesHaveDifferentMeanings() {
        val read = interpretLogEvent(line("userTable: GMS current bgControl=noRestrict"))
        val attempt = interpretLogEvent(line("userTable: update miuiAuto -> noRestrict"))
        val noChange = interpretLogEvent(line("userTable: update miuiAuto -> noRestrict count=0"))
        val changed = interpretLogEvent(line("userTable: update miuiAuto -> noRestrict count=1"))
        assertEquals(4, setOf(read.kind, attempt.kind, noChange.kind, changed.kind).size)
        assertTrue(read.impact.contains("没有说刚刚又改"))
        assertTrue(attempt.impact.contains("还不能判断"))
        assertTrue(noChange.impact.contains("不能说修复已成功"))
        assertTrue(changed.impact.contains("当时"))
        assertNotEquals(read.kind, interpretLogEvent(line("userTable: GMS current bgControl=noRestrictionUnknown")).kind)
    }

    @Test fun gateAndReconnectNeverClaimDeliveryOrRecovery() {
        val gate = interpretLogEvent(line("fcm-gate: pkg=com.example.app"))
        assertTrue(gate.impact.contains("不代表应用已经收到"))
        assertTrue(interpretLogEvent(line("P4: recovery broadcasts sent")).impact.contains("尚不能确认"))
        assertTrue(interpretLogEvent(line("Hot reload requested")).impact.contains("不是全部功能"))
    }

    @Test fun zeroTrafficAndCumulativeTrafficAreNotNotificationCounts() {
        val idle = interpretLogEvent(line("gms probe [periodic]: rx=+0B tx=+0B"))
        val baseline = interpretLogEvent(line("gms probe [startup]: rx=100B tx=50B (baseline)"))
        val delta = interpretLogEvent(line("gms probe [periodic]: rx=+10B tx=+2B"))
        assertEquals(3, setOf(idle.kind, baseline.kind, delta.kind).size)
        assertTrue(idle.impact.contains("不能据此认定掉线"))
        assertTrue(baseline.impact.contains("不是收到通知"))
        assertTrue(delta.impact.contains("不能换算成通知条数"))
    }

    @Test fun errorHasPriorityOverAnActionName() {
        val e = interpretLogEvent(line("userTable: update failed count=1"))
        assertTrue(e.needsAttention)
        assertTrue(e.happened.contains("失败"))
        assertFalse(e.happened.contains("已修改"))
        assertTrue(interpretLogEvent(line("read operation", level = "E")).needsAttention)
    }

    @Test fun groupingDeduplicatesButDoesNotMixOutcomes() {
        val a = line("userTable: update before -> noRestrict count=0", 1)
        val b = line("userTable: update before -> noRestrict count=1", 2)
        val failure = line("userTable: query failed", 3)
        val groups = explainedLogGroups(listOf(b, failure, a, a))
        assertEquals(3, groups.size)
        assertEquals(3, groups.sumOf { it.records.size })
        assertTrue(groups.first().explanation.needsAttention)
        assertTrue(groups.all { it.explanation == interpretLogEvent(it.records.last()) })
    }
}
