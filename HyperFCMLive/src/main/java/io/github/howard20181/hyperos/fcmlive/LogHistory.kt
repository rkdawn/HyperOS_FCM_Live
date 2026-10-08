package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** 仅在用户检测时保存本模块记录；不启用后台服务，也不承诺补齐未采集的历史。 */
internal object LogHistory {
    const val RETENTION_MS = 7L * 86_400_000
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_RECORDS = 20000

    fun merge(old: List<ModuleLogParser.Line>, fresh: List<ModuleLogParser.Line>, now: Long): List<ModuleLogParser.Line> {
        val timed = (old + fresh).distinctBy { it.raw }.mapNotNull { line ->
            ModuleLogParser.epochMillis(line)?.takeIf { it in (now - RETENTION_MS)..now }?.let { line to it }
        }.sortedBy { it.second }
        var bytes = 0
        return timed.asReversed().take(MAX_RECORDS).takeWhile {
            bytes += it.first.raw.toByteArray(Charsets.UTF_8).size + 1
            bytes <= MAX_BYTES
        }.map { it.first }.reversed()
    }

    fun encode(lines: List<ModuleLogParser.Line>): ByteArray =
        lines.joinToString("\n", postfix = "\n") { it.raw }.toByteArray(Charsets.UTF_8)

    fun collect(context: Context, fresh: List<ModuleLogParser.Line>, now: Long): Pair<List<ModuleLogParser.Line>, String> {
        val file = AtomicFile(File(context.noBackupFilesDir, "module-diagnostics-history.log"))
        var warning = ""
        val old = try {
            if (file.baseFile.length() > MAX_BYTES) error("历史文件超出大小上限")
            file.openRead().bufferedReader(Charsets.UTF_8).use { ModuleLogParser.parseAll(it.readLines()) }
        } catch (_: java.io.FileNotFoundException) {
            emptyList()
        } catch (_: Exception) {
            warning = "旧历史未能读取；"
            emptyList()
        }
        val combined = merge(old, fresh, now)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(encode(combined))
            file.finishWrite(stream)
        } catch (_: Exception) {
            stream?.let(file::failWrite)
            warning += "本次历史保存失败；"
        }
        return combined to (warning + "按需保存最近 7 天本模块记录，上限 4 MiB / 20000 条；只包含实际采集到的片段，不是全天监控。")
    }
}
