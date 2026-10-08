package io.github.howard20181.hyperos.fcmlive

import java.net.InetAddress

/** 格式不认识返回未知；不以端口或包名前缀代替 UID 身份核验。 */
internal object DiagnosticsParser {
    const val GMS = "com.google.android.gms"
    private val whitespace = Regex("\\s+")
    private val gate = Regex("\\bmGmsLimitEnabled\\s*[:=]\\s*(true|false)\\b", RegexOption.IGNORE_CASE)
    private val packageName = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")

    fun gmsLimitEnabled(lines: List<String>): Boolean? = lines
        .flatMap { gate.findAll(it).map { m -> m.groupValues[1].lowercase() }.toList() }
        .distinct().singleOrNull()?.toBooleanStrictOrNull()

    fun milletContainsGms(lines: List<String>): Boolean? {
        val text = lines.singleOrNull()?.trim() ?: return null
        if (text == "null") return null // 键不存在，不能据此判断该 ROM 是否使用此机制。
        if (text.isEmpty()) return false
        val entries = text.split(',').map { it.trim() }
        if (entries.any { !packageName.matches(it) }) return null
        return GMS in entries
    }

    /** 只报告键是否有值，不把未配置等同于系统没有广播限制。 */
    fun aurogonConfigured(lines: List<String>): Boolean? {
        val text = lines.singleOrNull()?.trim() ?: return null
        if (text == "null" || text.isEmpty()) return false
        if (text.contains("Exception") || text.startsWith("Error") || text.contains("Permission denied")) return null
        return true
    }

    fun deviceIdleGms(lines: List<String>): Pair<Boolean, List<String>>? {
        val entries = lines.filter { it.isNotBlank() }
        if (entries.isEmpty()) return null
        val valid = Regex("(?:system-excidle|system|user),[A-Za-z0-9_.]+,\\d+")
        if (entries.any { !valid.matches(it.trim()) }) return null
        val matches = entries.filter { it.trim().split(',')[1] == GMS }.map { it.trim() }
        return matches.isNotEmpty() to matches
    }

    fun gmsProcessName(name: String): Boolean = name == GMS || name == "$GMS.persistent" ||
        name == "$GMS.unstable" || (name.startsWith("$GMS:") && name.length > GMS.length + 1)

    fun isAppUid(uid: Int): Boolean = uid >= 0 && uid % 100000 in 10000..19999

    fun userLabel(uid: Int): String = when (val user = uid / 100000) {
        0 -> "主用户"
        999 -> "用户 999（分身空间）"
        else -> "用户 $user"
    }

    data class ProcessRow(val uid: Int, val pid: Int, val name: String) {
        val display get() = "${userLabel(uid)} · $name（UID $uid，PID $pid）"
    }

    /** NAME 可以换列；只有在最后一列时才合并余下文字。 */
    fun processRows(lines: List<String>): List<ProcessRow>? {
        val rows = lines.filter { it.isNotBlank() }.map { it.trim().split(whitespace) }
        val header = rows.firstOrNull()?.map { it.uppercase() } ?: return null
        val uidColumn = header.indexOf("UID").takeIf { it >= 0 } ?: header.indexOf("USER")
        val pidColumn = header.indexOf("PID")
        val nameColumn = header.indexOf("NAME")
        if (uidColumn < 0 || pidColumn < 0 || nameColumn < 0) return null
        val result = mutableListOf<ProcessRow>()
        for (fields in rows.drop(1)) {
            if (fields.size < header.size) return null
            val name = if (nameColumn == header.lastIndex) fields.drop(nameColumn).joinToString(" ") else fields[nameColumn]
            if (!gmsProcessName(name)) continue
            val uid = parseUid(fields[uidColumn]) ?: return null
            val pid = fields[pidColumn].toIntOrNull()?.takeIf { it > 0 } ?: return null
            result += ProcessRow(uid, pid, name)
        }
        return result.distinct()
    }

    private fun parseUid(text: String): Int? {
        text.toIntOrNull()?.takeIf { it >= 0 }?.let { return it }
        val m = Regex("u(\\d+)_a(\\d+)").matchEntire(text) ?: return null
        val user = m.groupValues[1].toLongOrNull() ?: return null
        val app = m.groupValues[2].toLongOrNull()?.takeIf { it in 0..9999 } ?: return null
        return (user * 100000 + 10000 + app).takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    fun processes(lines: List<String>, uid: Int): List<String>? = processRows(lines)
        ?.filter { it.uid == uid }?.map { it.name }?.distinct()

    fun allGmsProcesses(lines: List<String>, verifiedUid: Int): List<ProcessRow>? = processRows(lines)
        ?.filter { isAppUid(it.uid) && it.uid % 100000 == verifiedUid % 100000 }

    data class Socket(val key: String, val remote: String, val port: Int, val state: String,
        val inode: String, val uid: Int = 0) {
        val established get() = state == "01"
        val pushCandidate get() = port in 5228..5230
        val userLabel get() = DiagnosticsParser.userLabel(uid)
        val endpoint get() = if (remote.contains(':')) "[$remote]:$port" else "$remote:$port"
        val stateLabel get() = when (state) {
            "01" -> "TCP 已建立"
            "02", "03" -> "正在建立连接"
            "04", "05", "08", "09", "0B" -> "正在关闭"
            "06" -> "等待回收"
            "07" -> "已关闭"
            "0A" -> "监听中"
            else -> "未知状态（$state）"
        }
    }

    fun sockets(lines: List<String>, uid: Int): List<Socket>? = allGmsSockets(lines, setOf(uid))

    /** 只接受已由 PackageManager/同应用编号进程确认的 UID，其他应用同端口不计入。 */
    fun allGmsSockets(lines: List<String>, verifiedUids: Set<Int>): List<Socket>? {
        if (verifiedUids.isEmpty()) return null
        val rows = lines.filter { it.isNotBlank() }.map { it.trim().split(whitespace) }
        val header = rows.firstOrNull() ?: return null
        if (header.firstOrNull() != "sl" || "local_address" !in header || "uid" !in header) return null
        val result = mutableListOf<Socket>()
        for (fields in rows.drop(1)) {
            if (fields.size < 10) return null
            val uid = fields[7].toIntOrNull() ?: return null
            if (uid !in verifiedUids) continue
            val remote = hexAddress(fields[2]) ?: return null
            val state = fields[3].uppercase()
            if (!Regex("[0-9A-F]{2}").matches(state)) return null
            result += Socket("$uid-${fields[1]}-${fields[2]}-${fields[9]}", remote.first,
                remote.second, state, fields[9], uid)
        }
        return result.distinctBy { it.key }
    }

    fun hexAddress(value: String): Pair<String, Int>? {
        val parts = value.split(':')
        if (parts.size != 2 || parts[0].length !in setOf(8, 32)) return null
        val port = parts[1].toIntOrNull(16)?.takeIf { it in 0..65535 } ?: return null
        if (!parts[0].all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
        val bytes = parts[0].chunked(8).flatMap { word -> word.chunked(2).reversed().map { it.toInt(16).toByte() } }.toByteArray()
        val address = runCatching { InetAddress.getByAddress(bytes).hostAddress }.getOrNull() ?: return null
        return address to port
    }

    data class Observation(val boot: String, val at: Long, val online: Boolean, val since: Long?, val observedLosses: Int)

    fun observe(previous: Observation?, boot: String?, at: Long, online: Boolean?): Observation? {
        if (boot.isNullOrBlank() || online == null) return null
        val adjacent = previous != null && previous.boot == boot && at >= previous.at && at - previous.at <= 60_000L
        if (!adjacent) return Observation(boot, at, online, if (online) at else null, 0)
        previous!!
        return Observation(boot, at, online, if (online) if (previous.online) previous.since else at else null,
            previous.observedLosses + if (previous.online && !online) 1 else 0)
    }

    /** coveredDays 仅表示有记录的自然日数，绝不是连续监测覆盖时长。 */
    data class WindowStats(val windowLabel: String, val coveredDays: Int, val totalGates: Int,
        val gateRecords: Map<String, Int>, val actionCounts: Map<String, Int>, val firstStamp: String?, val lastStamp: String?)

    fun windowStats(lines: List<ModuleLogParser.Line>, nowMs: Long): List<WindowStats> {
        val timed = lines.distinctBy { it.raw }.mapNotNull { line -> ModuleLogParser.epochMillis(line)?.let { line to it } }
            .filter { it.second <= nowMs }
        if (timed.isEmpty()) return emptyList()
        return listOf("24 小时" to 1L, "3 天" to 3L, "7 天" to 7L).map { (label, days) ->
            val entries = timed.filter { it.second >= nowMs - days * 86_400_000L }.map { it.first }
            val gates = ModuleLogParser.gateCounts(entries)
            WindowStats(label, entries.map { it.stamp.take(10) }.distinct().size,
                gates.values.sumOf { it.count }, gates.mapValues { it.value.count },
                entries.filter { ModuleLogParser.gatePackage(it.message) == null }.groupingBy { describeAction(it.message) }.eachCount(),
                entries.minOfOrNull { it.stamp }, entries.maxOfOrNull { it.stamp })
        }
    }

    internal fun describeAction(message: String): String = when {
        ModuleLogParser.gatePackage(message) != null -> "推送广播放行记录"
        message.contains("injected #") && message.startsWith("doze-wl-sentinel") -> "补充省电豁免名单"
        message.startsWith("userTable: update") || message.startsWith("userTable: insert") -> "调整省电配置"
        message.startsWith("P3: rewrote") -> "调整省电场景"
        message.startsWith("MILLET_NO_RESTRICT_APP:") -> "补充防冻结名单"
        message.startsWith("standby-firewall:") -> "阻止待机限网命令"
        message.contains("DENIED") -> "系统拒绝唤醒的观察记录"
        message.startsWith("gms probe") -> "谷歌服务流量采样"
        message.startsWith("Hot reload") -> "模块重新加载请求"
        message.contains(" hooked") || message.startsWith("HyperFCMLive active in") -> "模块安装记录"
        else -> "其他检查记录"
    }
}
