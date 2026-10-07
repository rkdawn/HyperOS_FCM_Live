package io.github.howard20181.hyperos.fcmlive

import java.net.InetAddress

/** 纯数据解析：格式无法识别就返回未知，不把缺失值默认为正常。 */
internal object DiagnosticsParser {
    const val GMS = "com.google.android.gms"
    private val whitespace = Regex("\\s+")
    private val gate = Regex("\\bmGmsLimitEnabled\\s*[:=]\\s*(true|false)\\b", RegexOption.IGNORE_CASE)

    fun gmsLimitEnabled(lines: List<String>): Boolean? {
        val values = lines.flatMap { line -> gate.findAll(line).map { it.groupValues[1].lowercase() }.toList() }.distinct()
        return values.singleOrNull()?.toBooleanStrictOrNull()
    }

    /** `settings get system MILLET_NO_RESTRICT_APP`：null=读不到，true/false=名单是否含 GMS。 */
    fun milletContainsGms(lines: List<String>): Boolean? {
        val joined = lines.joinToString(" ").trim()
        return when {
            joined.isEmpty() -> null
            joined == "null" -> false
            else -> joined.split(',').any { it.trim() == GMS }
        }
    }

    /** `settings get global aurogon_enable`：null=读不到，true/false=是否已配置。 */
    fun aurogonConfigured(lines: List<String>): Boolean? {
        val joined = lines.joinToString(" ").trim()
        return when {
            joined.isEmpty() -> null
            joined == "null" -> false
            else -> true
        }
    }

    /** `dumpsys deviceidle whitelist`：返回 (是否含 GMS, 含 GMS 的行)。null=表不可读或格式不认识。 */
    fun deviceIdleGms(lines: List<String>): Pair<Boolean, List<String>>? {
        if (lines.isEmpty()) return null
        val gmsLines = lines.filter { it.contains(GMS) }.map { it.trim() }
        return (gmsLines.isNotEmpty()) to gmsLines
    }

    /** GMS 及其子进程。子进程名用已知白名单（unstable/persistent 及冒号服务），
     *  不用任意点号前缀——gms.feedback、gms.evil 这类同前缀包会被误收。 */
    fun gmsProcessName(name: String): Boolean =
        name == GMS || name == "$GMS.persistent" || name.startsWith("$GMS:") ||
            name.startsWith("$GMS.unstable")

    /**
     * 应用段 UID 判断：Android UID = 用户号 × 100000 + 应用号，应用号占
     * 10000–19999。对 UID 取模即可覆盖主用户（10132）、手机分身（99910132）
     * 和工作资料（1010132）里的所有应用进程，不必枚举用户段。
     */
    fun isAppUid(uid: Int): Boolean {
        val appId = uid % 100000
        return appId in 10000..19999
    }

    /**
     * 定位 ps 表头里的 UID/PID/NAME 三列，而不是假设它们固定在第 0/1/2 列。
     * 不同 ROM 的 toybox ps 表头大小写、列顺序可能不同，写死位置会误判。
     */
    private data class PsColumns(val uid: Int, val pid: Int, val name: Int)

    private fun psColumns(header: List<String>): PsColumns? {
        val uid = header.indexOfFirst { it.equals("UID", true) }
        val pid = header.indexOfFirst { it.equals("PID", true) }
        val name = header.indexOfFirst { it.equals("NAME", true) }
        if (uid < 0 || pid < 0 || name < 0) return null
        return PsColumns(uid, pid, name)
    }

    fun processes(lines: List<String>, uid: Int): List<String>? {
        val rows = lines.filter { it.isNotBlank() }.map { it.trim().split(whitespace) }
        val header = rows.firstOrNull() ?: return null
        val cols = psColumns(header) ?: return null
        val result = mutableListOf<String>()
        for (fields in rows.drop(1)) {
            if (fields.size <= maxOf(cols.uid, cols.pid, cols.name)) return null
            val rowUid = fields[cols.uid].toIntOrNull() ?: return null
            if (fields[cols.pid].toIntOrNull() == null) return null
            val name = fields.drop(cols.name).joinToString(" ")
            if (rowUid == uid && gmsProcessName(name)) result += name
        }
        return result.distinct()
    }

    /** 覆盖所有用户的 GMS 进程行，标注用户与进程角色。 */
    fun allGmsProcesses(lines: List<String>): List<String>? {
        val rows = lines.filter { it.isNotBlank() }.map { it.trim().split(whitespace) }
        val header = rows.firstOrNull() ?: return null
        val cols = psColumns(header) ?: return null
        val result = mutableListOf<String>()
        for (fields in rows.drop(1)) {
            if (fields.size <= maxOf(cols.uid, cols.pid, cols.name)) return null
            val rowUid = fields[cols.uid].toIntOrNull() ?: return null
            val pid = fields[cols.pid].toIntOrNull() ?: return null
            val name = fields.drop(cols.name).joinToString(" ")
            if (isAppUid(rowUid) && gmsProcessName(name)) {
                val user = when (rowUid / 100000) {
                    0 -> ""
                    999 -> "，手机分身"
                    else -> "，用户 ${rowUid / 100000}"
                }
                val role = when (name) {
                    GMS -> "主进程"
                    "$GMS.persistent" -> "常驻进程"
                    else -> if (name.startsWith("$GMS.unstable")) "主工作进程"
                        else if (name.startsWith("$GMS.")) "子进程"
                        else "子服务"
                }
                result += "$name（pid=$pid$user，$role）"
            }
        }
        return result.distinct()
    }

    data class Socket(
        val key: String,
        val remote: String,
        val port: Int,
        val state: String,
        val inode: String,
        val uid: Int = 0
    ) {
        val established get() = state == "01"
        // 443 是通用 HTTPS，不能仅凭端口判定是推送连接。
        val pushCandidate get() = port in 5228..5230
        /** UID 取模得到应用号所在用户；0 是主用户，999 是手机分身，10+ 是工作资料。 */
        val userLabel get() = when (val u = uid / 100000) {
            0 -> "主用户"
            999 -> "手机分身"
            else -> "用户 $u"
        }
        val stateLabel get() = when (state) {
            "01" -> "TCP 已建立"
            "02" -> "正在发起连接"
            "03" -> "等待握手确认"
            "04", "05", "08", "09", "0B" -> "正在关闭"
            "06" -> "关闭后等待回收"
            "07" -> "已关闭"
            "0A" -> "监听中"
            else -> "未知状态（$state）"
        }
        val endpoint get() = if (remote.contains(':')) "[$remote]:$port" else "$remote:$port"
    }

    /** null 表示表不可读或格式不认识；空列表表示成功读取但没有该 UID 的连接。 */
    fun sockets(lines: List<String>, uid: Int): List<Socket>? {
        val rows = lines.filter { it.isNotBlank() }.map { it.trim().split(whitespace) }
        val header = rows.firstOrNull() ?: return null
        if (header.firstOrNull() != "sl" || "local_address" !in header || "uid" !in header) return null
        val result = mutableListOf<Socket>()
        for (fields in rows.drop(1)) {
            if (fields.size < 10) return null
            val rowUid = fields[7].toIntOrNull() ?: return null
            if (rowUid != uid) continue
            val address = hexAddress(fields[2]) ?: return null
            val state = fields[3].uppercase()
            if (!Regex("[0-9A-F]{2}").matches(state)) return null
            result += Socket("${fields[1]}-${fields[2]}-${fields[9]}", address.first,
                address.second, state, fields[9], rowUid)
        }
        return result.distinctBy { it.key }
    }

    /**
     * 覆盖所有用户的 GMS socket：主用户、分身、工作资料各自的 GMS UID 不同，
     * 只按当前用户单 UID 匹配会把另一实例的连接漏掉。
     */
    fun allGmsSockets(lines: List<String>): List<Socket>? {
        val rows = lines.filter { it.isNotBlank() }.map { it.trim().split(whitespace) }
        val header = rows.firstOrNull() ?: return null
        if (header.firstOrNull() != "sl" || "local_address" !in header || "uid" !in header) return null
        val result = mutableListOf<Socket>()
        for (fields in rows.drop(1)) {
            if (fields.size < 10) return null
            val rowUid = fields[7].toIntOrNull() ?: return null
            if (!isAppUid(rowUid)) continue
            val address = hexAddress(fields[2]) ?: return null
            val state = fields[3].uppercase()
            if (!Regex("[0-9A-F]{2}").matches(state)) return null
            val remote = address.first
            val port = address.second
            // 只保留 GMS UID 段内的连接：按进程表确认的 UID 匹配做不到时，
            // 用"推送端口 + 应用段 UID"作为候选过滤，非推送端口一律不算。
            if (port !in 5228..5230) continue
            result += Socket("${fields[1]}-${fields[2]}-${fields[9]}", remote,
                port, state, fields[9], rowUid)
        }
        return result.distinctBy { it.key }
    }

    /** Android arm64 的 /proc 地址按 32 位小端字输出；IPv6 需要逐字反转。 */
    fun hexAddress(value: String): Pair<String, Int>? {
        val parts = value.split(':')
        if (parts.size != 2 || parts[0].length !in setOf(8, 32)) return null
        val port = parts[1].toIntOrNull(16)?.takeIf { it in 0..65535 } ?: return null
        val hex = parts[0]
        if (!hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
        val bytes = hex.chunked(8).flatMap { word ->
            word.chunked(2).reversed().map { it.toInt(16).toByte() }
        }.toByteArray()
        val address = runCatching { InetAddress.getByAddress(bytes).hostAddress }.getOrNull() ?: return null
        return address to port
    }

    /** 只记录相邻有效采样的变化；缺测、切换启动周期和长时间间隔不算掉线。 */
    data class Observation(
        val boot: String, val at: Long, val online: Boolean,
        val since: Long?, val observedLosses: Int
    )

    fun observe(previous: Observation?, boot: String?, at: Long, online: Boolean?): Observation? {
        if (boot.isNullOrBlank() || online == null) return null
        val adjacent = previous != null && previous.boot == boot && at >= previous.at && at - previous.at <= 60_000L
        if (!adjacent) return Observation(boot, at, online, if (online) at else null, 0)
        previous!!
        return Observation(boot, at, online,
            if (online) if (previous.online) previous.since else at else null,
            previous.observedLosses + if (previous.online && !online) 1 else 0)
    }
}
