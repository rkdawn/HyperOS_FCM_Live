package io.github.howard20181.hyperos.fcmlive

import org.junit.Assert.*
import org.junit.Test

class LogTimelineTest {
    private fun line(stamp: String, message: String) = ModuleLogParser.Line(
        stamp = stamp, time = stamp, level = "I", message = message,
        raw = "[ $stamp     1000:  3656:  3656 I/LSPosedFramework ] (system)[io.github.howard20181.hyperos.fcmlive,HyperGreeze,x-1,0,1] $message"
    )

    private val t1 = "2026-10-08 08:43:55.290"
    private val t1b = "2026-10-08 08:43:56.100"
    private val t2 = "2026-10-08 09:28:28.000"
    private val t3 = "2026-10-08 09:51:47.000"

    @Test fun timelinePreservesChronologicalOrder() {
        val timeline = explainedTimeline(listOf(
            line(t2, "standby-firewall: suppressed 'enablemiuistandby enable' #1"),
            line(t1, "allowlist loaded: selected=true, strict=false"),
            line(t3, "P3: rewrote GMS scenario 0 → 8"),
        ))
        assertEquals(listOf("2026-10-08 08:43:55", "2026-10-08 09:28:28", "2026-10-08 09:51:47"),
            timeline.map { it.records.first().stamp.take(19) })
    }

    @Test fun adjacentSameKindMergesButDifferentKindsSplit() {
        val timeline = explainedTimeline(listOf(
            line(t1, "allowlist loaded: selected=true, strict=false"),
            line(t1b, "P1: isAllowBroadcast hooked; caller-uid fallback armed (arg0=callerUid)"),
            line(t2, "standby-firewall: suppressed 'enablemiuistandby enable' #1"),
        ))
        // 三条 kind 各不相同 → 三段。
        assertEquals(3, timeline.size)
    }

    @Test fun sameKindAcrossGapSplitsIntoSeparateSpans() {
        val early = "2026-10-08 08:00:00.000"
        val late = "2026-10-08 11:00:00.000"
        val timeline = explainedTimeline(listOf(
            line(early, "standby-firewall: suppressed x #1"),
            line(late, "standby-firewall: suppressed x #2"),
        ))
        assertEquals(2, timeline.size)
        assertEquals(1, timeline[0].records.size)
        assertEquals(1, timeline[1].records.size)
    }

    @Test fun sameKindWithinGapMergesIntoOneSpan() {
        val a = "2026-10-08 08:00:00.000"
        val b = "2026-10-08 08:05:00.000"
        val timeline = explainedTimeline(listOf(line(a, "standby-firewall: suppressed x #1"), line(b, "standby-firewall: suppressed x #2")))
        assertEquals(1, timeline.size)
        assertEquals(2, timeline[0].records.size)
    }

    @Test fun duplicateRawLinesCollapsed() {
        val timeline = explainedTimeline(listOf(
            line(t1, "standby-firewall: suppressed x #1"),
            line(t1, "standby-firewall: suppressed x #1"),
        ))
        assertEquals(1, timeline.size)
        assertEquals(1, timeline[0].records.size)
    }

    @Test fun spanSummaryUsesLastRecordMeaningAndKeepsThreeSentenceText() {
        val timeline = explainedTimeline(listOf(
            line(t1, "standby-firewall: suppressed x #1"),
            line(t1b, "standby-firewall: suppressed x #2"),
        ))
        val explanation = timeline[0].explanation
        assertTrue(explanation.summary.contains("待机限网") || explanation.summary.contains("限网"))
        assertTrue(explanation.text.startsWith("发生了什么："))
        assertEquals(t1b.take(11), timeline[0].records.last().stamp.take(11))
    }

    @Test fun errorGroupingStillPrioritizesFailuresInTypeView() {
        val groups = explainedLogGroups(listOf(
            line(t1, "routine line A"),
            line(t2, "userTable: update failed"),
        ))
        assertEquals("failure", groups.first().explanation.kind)
    }
}
