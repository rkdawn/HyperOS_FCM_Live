package io.github.howard20181.hyperos.fcmlive

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ExportStagingTest {
    @Test fun removesOnlyExpiredUnprotectedStagingFiles() {
        val directory = Files.createTempDirectory("fcm-export-test").toFile()
        val stale = directory.resolve("pending-fcm-11111111-1111-1111-1111-111111111111.txt")
        val waiting = directory.resolve("pending-fcm-22222222-2222-2222-2222-222222222222.txt")
        val recent = directory.resolve("pending-fcm-33333333-3333-3333-3333-333333333333.txt")
        val unrelated = directory.resolve("user-report.txt")
        val now = System.currentTimeMillis()
        try {
            listOf(stale, waiting, recent, unrelated).forEach { it.writeText("测试报告") }
            listOf(stale, waiting, unrelated).forEach { assertTrue(it.setLastModified(now - ExportStaging.MAX_AGE_MS - 1000)) }
            ExportStaging.hold(waiting)
            ExportStaging.removeExpired(directory, now)
            assertFalse(stale.exists())
            assertTrue(waiting.exists())
            assertTrue(recent.exists())
            assertTrue(unrelated.exists())
            ExportStaging.release(waiting)
            ExportStaging.removeExpired(directory, now)
            assertFalse(waiting.exists())
        } finally {
            ExportStaging.release(waiting)
            directory.deleteRecursively()
        }
    }
}
