package io.github.howard20181.hyperos.fcmlive

import org.junit.Assert.*
import org.junit.Test

class RootDiagnosticsEvidenceTest {
    @Test fun failuresAndTruncationCannotLookSuccessful() {
        assertFalse(RootDiagnosticsReader.Evidence("test", null, emptyList(), "超时").ok)
        assertFalse(RootDiagnosticsReader.Evidence("test", 1, emptyList(), "拒绝访问").ok)
        assertFalse(RootDiagnosticsReader.Evidence("test", 0, emptyList(), "输出不完整").ok)
        assertTrue(RootDiagnosticsReader.Evidence("test", 0, emptyList(), "").ok)
        val warning = RootDiagnosticsReader.Evidence("test", 0, emptyList(), "", "工具警告")
        assertTrue(warning.summary().contains("工具警告"))
    }

    @Test fun outputIsBoundedByLinesAndCharacters() {
        val lines = RootDiagnosticsReader.BoundedLines(maxLines = 2, maxChars = 100)
        assertTrue(lines.add("first"))
        assertTrue(lines.add("second"))
        assertFalse(lines.add("third"))
        assertEquals(2, lines.size)
        assertTrue(lines.truncated)
        val chars = RootDiagnosticsReader.BoundedLines(maxLines = 10, maxChars = 5)
        assertTrue(chars.add("12345"))
        assertFalse(chars.add("6"))
        assertTrue(chars.truncated)
    }
}
