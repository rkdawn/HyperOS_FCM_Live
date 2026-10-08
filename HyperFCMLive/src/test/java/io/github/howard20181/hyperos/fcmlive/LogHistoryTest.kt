package io.github.howard20181.hyperos.fcmlive

import org.junit.Assert.*
import org.junit.Test

class LogHistoryTest {
    private fun line(stamp: String, text: String = "fcm-gate: pkg=com.example.test") =
        ModuleLogParser.parse("[ $stamp 1000: 1: 2 I/HyperGreeze ] $text")!!

    @Test fun keepsUniqueCollectedRecordsAndExpiresOutsideSevenDays() {
        val nowLine = line("2026-10-08 12:00:00.000")
        val now = ModuleLogParser.epochMillis(nowLine)!!
        val yesterday = line("2026-10-07 12:00:00.000")
        val old = line("2026-09-30 12:00:00.000")
        val future = line("2026-10-09 12:00:00.000")
        val result = LogHistory.merge(listOf(old, yesterday), listOf(yesterday, nowLine, future), now)
        assertEquals(listOf(yesterday, nowLine), result)
    }

    @Test fun parseFractionalSecondsAndRejectMalformedCalendarDates() {
        assertNotNull(ModuleLogParser.epochMillis(line("2026-10-08 12:00:00.123456")))
        assertNotNull(ModuleLogParser.epochMillis(line("2026-10-08 12:00:00.123456789")))
        assertNull(ModuleLogParser.epochMillis(line("2026-02-30 12:00:00.000")))
        assertNull(ModuleLogParser.epochMillis(line("10-08 12:00:00.000")))
    }

    @Test fun savedHistoryCanBeParsedOnTheNextLaunch() {
        val original = listOf(line("2026-10-08 12:00:00.000"), line("2026-10-08 12:01:00.000"))
        val stored = LogHistory.encode(original).toString(Charsets.UTF_8)
        assertEquals(original, ModuleLogParser.parseAll(stored.lines()))
    }

    @Test fun storageBudgetKeepsMostRecentRecords() {
        val data = (0 until 5000).map { line("2026-10-08 12:00:00.000", "$it " + "x".repeat(1000)) }
        val now = ModuleLogParser.epochMillis(data.last())!!
        val retained = LogHistory.merge(emptyList(), data, now)
        assertTrue(retained.size < data.size)
        assertEquals(data.last(), retained.last())
        assertTrue(retained.sumOf { it.raw.toByteArray(Charsets.UTF_8).size + 1 } <= LogHistory.MAX_BYTES)
    }
}
