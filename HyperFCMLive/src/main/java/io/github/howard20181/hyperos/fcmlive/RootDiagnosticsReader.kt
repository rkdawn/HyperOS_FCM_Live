package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.os.SystemClock
import com.topjohnwu.superuser.Shell
import java.util.concurrent.TimeUnit

/** 每次诊断独享一个短期 shell。只读、限时、限输出，逐项保留失败原因。 */
internal object RootDiagnosticsReader {
    data class Evidence(val source: String, val code: Int?, val lines: List<String>, val error: String,
        val warnings: String = "") {
        val ok get() = code == 0 && error.isEmpty()
        fun summary() = "$source：exit=${code ?: "未知"}${if (error.isEmpty()) "" else "，$error"}" +
            if (warnings.isEmpty()) "" else "；stderr=$warnings"
    }
    data class Snapshot(val sample: GmsSample, val log: LogRead)

    // Root 输出不直接无限累积到内存；截断也必须让调用方知道。
    internal class BoundedLines(private val maxLines: Int = 6000, private val maxChars: Int = 1_000_000) : ArrayList<String>() {
        private var chars = 0
        var truncated = false
        override fun add(element: String): Boolean {
            if (size >= maxLines || chars + element.length > maxChars) {
                truncated = true
                return false
            }
            chars += element.length
            return super.add(element)
        }
    }

    internal fun read(shell: Shell, source: String, command: String): Evidence {
        val out = BoundedLines()
        val err = BoundedLines()
        var future: java.util.concurrent.Future<Shell.Result>? = null
        return try {
            val pending = shell.newJob().add(command).to(out, err).enqueue()
            future = pending
            val result = pending.get(8, TimeUnit.SECONDS)
            Evidence(source, result.code, out.toList(), when {
                out.truncated || err.truncated -> "输出超过限制，结果不完整"
                result.code != 0 -> err.joinToString("; ").take(500).ifBlank { "命令未成功" }
                else -> ""
            }, if (result.code == 0) err.joinToString("; ").take(500) else "")
        } catch (e: Exception) {
            future?.cancel(true)
            // 只关闭本次诊断创建的 shell，不碰系统服务和其他 shell。
            runCatching { shell.close() }
            Evidence(source, null, emptyList(), "${e.javaClass.simpleName}：命令超时或执行失败")
        }
    }

    fun collect(context: Context): Snapshot {
        val now = System.currentTimeMillis()
        val bootEpoch = now - SystemClock.elapsedRealtime()
        val uid = runCatching { context.packageManager.getApplicationInfo(DiagnosticsParser.GMS, 0).uid }.getOrNull()
        val evidence = mutableListOf<Evidence>()
        fun unavailable(reason: String) = Snapshot(
            GmsSample(uid, "PackageManager 当前用户", null, null, null, null,
                bootEpoch, now, false, reason, false),
            LogRead(emptyList(), false, reason)
        )
        val shell = try {
            // build 在 IO 线程等待授权；不同 Root 管理器可以复用以前的授权。
            Shell.Builder.create().setTimeout(20).build()
        } catch (e: Exception) {
            return unavailable("Root shell 建立失败：${e.javaClass.simpleName}：${e.message}")
        }
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        fun read(source: String, command: String): Evidence =
            if (SystemClock.elapsedRealtime() >= deadline) Evidence(source, null, emptyList(), "达到本次检测时间上限，未读取")
            else RootDiagnosticsReader.read(shell, source, command)
        try {
            val identity = read("Root 身份", "id -u")
            evidence += identity
            if (!identity.ok || identity.lines.singleOrNull()?.trim() != "0") {
                return unavailable("未取得 UID 0：${identity.summary()}。请在 Root 管理器授权后重试。")
            }
            val ps = read("进程表", "ps -A -o UID,PID,NAME")
            val tcp4 = read("IPv4 socket 表", "cat /proc/net/tcp")
            val tcp6 = read("IPv6 socket 表", "cat /proc/net/tcp6")
            val sampledAt = System.currentTimeMillis()
            val greezer = read("greeze 策略", "dumpsys -t 5 greezer")
            val boot = read("启动标识", "cat /proc/sys/kernel/random/boot_id")
            val millet = read("MILLET 免限名单", "settings --user 0 get system MILLET_NO_RESTRICT_APP")
            val aurogon = read("Aurogon 门控", "settings get global aurogon_enable")
            val deviceIdle = read("Doze 白名单", "dumpsys deviceidle whitelist")
            evidence += listOf(ps, tcp4, tcp6, greezer, boot, millet, aurogon, deviceIdle)
            val processRows = if (ps.ok && uid != null) DiagnosticsParser.allGmsProcesses(ps.lines, uid) else null
            val verifiedUids = processRows.orEmpty().map { it.uid }.toSet() + listOfNotNull(uid)
            val processes = processRows?.map { it.display }
            val v4 = if (tcp4.ok) DiagnosticsParser.allGmsSockets(tcp4.lines, verifiedUids) else null
            val v6 = if (tcp6.ok) DiagnosticsParser.allGmsSockets(tcp6.lines, verifiedUids) else null
            val sockets = if (v4 == null && v6 == null) null
                else (v4.orEmpty() + v6.orEmpty()).distinctBy { it.key }

            // 仅枚举已知的当前日志目录，不递归读取其他模块的私有数据。
            val paths = read("LSPosed 日志定位", """
                for d in /data/adb/lspd/log /data/adb/lspd/log.old /data/adb/lsposed/log /data/adb/lsposed/log.old; do
                    if [ -d "${'$'}d" ]; then
                        for f in "${'$'}d"/modules*.log; do
                            [ -f "${'$'}f" ] && printf '%s\n' "${'$'}f"
                        done
                    fi
                done
                true
            """.trimIndent())
            evidence += paths
            val allowed = Regex("/data/adb/(?:lspd|lsposed)/log(?:\\.old)?/modules[A-Za-z0-9_.:+-]*\\.log")
            // 只读取有预算的现存片段，不能假定 LSPosed 保留了完整七天。
            val candidates = paths.lines.filter { allowed.matches(it) }.distinct()
                .sortedByDescending { it.substringAfterLast('/') }.take(20)
            val records = mutableListOf<ModuleLogParser.Line>()
            val sources = mutableListOf<String>()
            var readable = false
            for (path in candidates) {
                val part = read(path, "tail -n 3000 '$path'")
                evidence += part
                if (part.ok) {
                    readable = true
                    sources += path
                    records += ModuleLogParser.parseAll(part.lines)
                }
            }
            // 某些框架将日志经 LSPosedFramework 标签写入 logcat；不能只过滤 HyperGreeze。
            if (records.isEmpty()) {
                val fallback = read("logcat 回退", "logcat -b all -d -v threadtime -t 3000 -s LSPosedFramework LSPosed-Bridge HyperGreeze")
                evidence += fallback
                if (fallback.ok) {
                    readable = true
                    sources += "logcat（LSPosedFramework / LSPosed-Bridge / HyperGreeze）"
                    records += ModuleLogParser.parseAll(fallback.lines)
                }
            }
            val (history, historyNote) = LogHistory.collect(context, records, sampledAt)
            val undated = records.filter { ModuleLogParser.epochMillis(it) == null }
            val log = LogRead((history + undated).distinctBy { it.raw }, readable,
                (sources.ifEmpty { listOf("未取得可读的模块日志") }).joinToString("\n") +
                    "\n每份最多读取末 3000 行，读取失败或被截断的历史可能缺失。\n" + historyNote)
            // 报告只保留该模块和 GMS 的数据，不复制整机 ps 或其他应用的 socket。
            val report = buildString {
                appendLine("网络采样时间：${java.time.Instant.ofEpochMilli(sampledAt)}")
                evidence.forEach { appendLine(it.summary()) }
                appendLine("解析状态：进程=${processes != null}，IPv4=${v4 != null}，IPv6=${v6 != null}")
                appendLine("启动标识：${if (boot.ok) boot.lines.joinToString() else "未知"}")
                appendLine("GMS uid=$uid；来源：PackageManager 当前用户")
                appendLine("GMS processes=${processes ?: "未知"}")
                // 进程解析失败时保留 ps 表头和 GMS 相关行，便于核对 ROM 的列格式。
                appendLine("--- ps 原始输出（表头 + 含 GMS 的行，最多 40 条）---")
                (ps.lines.take(1) + ps.lines.drop(1).filter { it.contains("gms") }).take(40)
                    .forEach { appendLine(it) }
                sockets.orEmpty().forEach { appendLine("socket=${it.endpoint} state=${it.state} inode=${it.inode}") }
                if (greezer.ok) greezer.lines.filter { it.contains("mGmsLimitEnabled") }.forEach { appendLine(it) }
                if (millet.ok) appendLine("MILLET_NO_RESTRICT_APP=${millet.lines.joinToString(" ")}")
                if (aurogon.ok) appendLine("aurogon_enable=${aurogon.lines.joinToString(" ")}")
                if (deviceIdle.ok) appendLine("deviceidle GMS=${DiagnosticsParser.deviceIdleGms(deviceIdle.lines)?.second.orEmpty()}")
                appendLine("--- 本模块日志（最多 500 条）---")
                log.lines.takeLast(500).forEach { appendLine(it.raw) }
            }
            return Snapshot(GmsSample(uid, "PackageManager 当前用户", processes, sockets,
                if (greezer.ok) DiagnosticsParser.gmsLimitEnabled(greezer.lines) else null,
                boot.lines.singleOrNull()?.trim()?.takeIf {
                    boot.ok && Regex("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}").matches(it)
                },
                bootEpoch, sampledAt, true, report, v4 != null && v6 != null,
                if (millet.ok) DiagnosticsParser.milletContainsGms(millet.lines) else null,
                if (aurogon.ok) DiagnosticsParser.aurogonConfigured(aurogon.lines) else null,
                if (deviceIdle.ok) DiagnosticsParser.deviceIdleGms(deviceIdle.lines)?.first else null,
                verifiedUids, processRows), log)
        } finally {
            runCatching { shell.close() }
        }
    }
}
