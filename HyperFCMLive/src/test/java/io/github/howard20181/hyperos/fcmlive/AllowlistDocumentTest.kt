package io.github.howard20181.hyperos.fcmlive

import org.junit.Assert.*
import org.junit.Test

class AllowlistDocumentTest {
    @Test fun parsesCommentsAndDeduplicatesPackages() {
        assertEquals(setOf("android", "com.example.app"), AllowlistDocument.read(
            "# 备份\nandroid\ncom.example.app\ncom.example.app\n".byteInputStream()))
    }
    @Test fun acceptsUtf8BomWithoutTreatingItAsPartOfPackageName() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "com.example.app\n".toByteArray()
        assertEquals(setOf("com.example.app"), AllowlistDocument.read(bytes.inputStream()))
    }
    @Test fun rejectsOversizedFileBeforePartialImport() {
        assertThrows(IllegalArgumentException::class.java) {
            AllowlistDocument.read(ByteArray(AllowlistDocument.MAX_BYTES + 1) { 65 }.inputStream())
        }
    }
    @Test fun rejectsInvalidPackageWithoutApplyingPartialData() {
        assertThrows(IllegalArgumentException::class.java) { AllowlistDocument.read("a.b\nnot a package\n".byteInputStream()) }
    }
}
