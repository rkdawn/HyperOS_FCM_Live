package io.github.howard20181.hyperos.fcmlive

import org.junit.Assert.*
import org.junit.Test

class ModuleLogParserTest {
    private fun wrapped(time: String, message: String, module: String = ModuleLogParser.MODULE_PACKAGE) =
        "[ 2026-10-07T$time    1000:  1234:  1235 I/LSPosedFramework ] (system)[$module,HyperGreeze,run1,0,1] $message"

    @Test fun parsesActualLsposedEnvelope() {
        val line = ModuleLogParser.parse(wrapped("01:23:45.678", "delivery: pkg=com.example.app caller=10133"))!!
        assertEquals("01:23:45", line.time)
        assertEquals("2026-10-07 01:23:45.678", line.stamp)
        assertEquals("com.example.app", ModuleLogParser.gatePackage(line.message))
        assertNotNull(ModuleLogParser.epochMillis(line))
    }

    @Test fun supportsDirectTagAndLogcatFallback() {
        assertEquals("message", ModuleLogParser.parse("[ 2026-10-07T01:00:00.000 1000:123:456 I/HyperGreeze ] message")?.message)
        assertEquals("message", ModuleLogParser.parse("10-07 01:00:00.000 123 456 I HyperGreeze: message")?.message)
        assertEquals("message", ModuleLogParser.parse("10-07 01:00:00.000 I/HyperGreeze(123): message")?.message)
        assertNull(ModuleLogParser.epochMillis(ModuleLogParser.parse("10-07 01:00:00.000 123 456 I HyperGreeze: message")!!))
    }

    @Test fun rejectsForeignModulesAndMalformedLines() {
        assertNull(ModuleLogParser.parse(wrapped("01:00:00.000", "delivery: pkg=a.b", "other.module")))
        assertNull(ModuleLogParser.parse("10-07 01:00:00.000 123 456 I OtherTag: delivery: pkg=a.b"))
        assertNull(ModuleLogParser.parse(wrapped("01:00:00.000", "quoted [${ModuleLogParser.MODULE_PACKAGE},HyperGreeze,run,0,1] delivery: pkg=a.b", "other.module")))
        assertNull(ModuleLogParser.parse("java.lang.RuntimeException: failed"))
        assertNull(ModuleLogParser.parse("-------- beginning of main"))
        assertNull(ModuleLogParser.gatePackage("isAllowBroadcast: c2dm allowed for callee=a.b"))
        assertNull(ModuleLogParser.gatePackage("delivery: pkg=null caller=1000"))
    }

    @Test fun aggregatesOnceAndKeepsLatestTime() {
        val first = wrapped("01:00:00.000", "delivery: pkg=a.b caller=1000")
        val last = wrapped("01:02:00.000", "fcm-gate: pkg=a.b caller=1000")
        val count = ModuleLogParser.gateCounts(ModuleLogParser.parseAll(listOf(last, first, first)))["a.b"]!!
        assertEquals(2, count.count)
        assertEquals("2026-10-07 01:02:00.000", count.lastStamp)
    }

    @Test fun trafficBaselineIsNotHalfHourDelta() {
        assertEquals(ModuleLogParser.Traffic(10, 5, true), ModuleLogParser.trafficOf("gms probe [periodic]: rx=+10B tx=+5B"))
        assertEquals(ModuleLogParser.Traffic(100, 50, false), ModuleLogParser.trafficOf("gms probe [startup]: rx=100B tx=50B (baseline)"))
        assertNull(ModuleLogParser.trafficOf("gms probe: traffic=unsupported"))
        assertNull(ModuleLogParser.trafficOf("rx=+-12B tx=+20B"))
        assertNull(ModuleLogParser.trafficOf("rx=10B tx=+20B"))
    }
}
