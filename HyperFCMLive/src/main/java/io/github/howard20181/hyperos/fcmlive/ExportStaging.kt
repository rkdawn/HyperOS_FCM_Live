package io.github.howard20181.hyperos.fcmlive

import java.io.File

/** 清理中断导出留下的私有暂存文件，不触碰用户选择保存的报告。 */
internal object ExportStaging {
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private val pending = mutableSetOf<String>()
    private val fileName = Regex("pending-fcm-[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}\\.txt")

    fun hold(file: File) { synchronized(pending) { pending += file.absolutePath } }
    fun release(file: File) { synchronized(pending) { pending -= file.absolutePath } }

    fun removeExpired(directory: File, now: Long) {
        directory.listFiles()?.forEach { file ->
            if (!file.isFile || (!fileName.matches(file.name) && file.name != "pending-fcm-diagnostics.txt")) return@forEach
            val modified = file.lastModified()
            if (modified > 0 && now >= modified && now - modified >= MAX_AGE_MS) synchronized(pending) {
                if (file.absolutePath !in pending) runCatching { file.delete() }
            }
        }
    }
}
