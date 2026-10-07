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

    fun gmsProcessName(name: String): Boolean =
        name == GMS || name == "$GMS.persistent" || name.startsWith("$GMS:")

    /**
     * Android UID 按 100000 分段：每个用户（主用户 0、分身 999、工作资料 10+）
     * 里的 GMS 各有自己的 UID。按"UID % 100000 的应用段内包名不可区分"这一
     * 限制，进程名本身已经过滤了 GMS，这里只需确认 UID 是合法应用段，
     * 就能同时覆盖主用户和分身/工作资料的 GMS 进程。
     */
    fun isAppUid(uid: Int): Boolean = uid >= 10000 && uid <= 19999 || uid >= 110000 && uid <= 159999

    fun processes(lines: List<String>, uid: Int): List<String>? {
        val rows = lines.filter { it.isNotBlank() }.map { it.trim().split(whitespace) }
        if (rows.firstOrNull() != listOf("UID", "PID", "NAME")) return null
        val result = mutableListOf<String>()
        for (fields in rows.drop(1)) {
            if (fields.size != 3) return null
            val rowUid = fields[0].toIntOrNull() ?: return null
            if (fields[1].toIntOrNull() == null) return null
            if (rowUid == uid && gmsProcessName(fields[2])) result += fields[2]
        }
        return result.distinct()
    }

    /** 覆盖所有用户的 GMS 进程行：`UID PID NAME`，按用户段标注。 */
    fun allGmsProcesses(lines: List<String>): List<String>? {
        val rows = lines.filter { it.isNotBlank() }.map { it.trim().split(whitespace) }
        if (rows.firstOrNull() != listOf("UID", "PID", "NAME")) return null
        val result = mutableListOf<String>()
        for (fields in rows.drop(1)) {
            if (fields.size != 3) return null
            val rowUid = fields[0].toIntOrNull() ?: return null
            if (fields[1].toIntOrNull() == null) return null
            if (isAppUid(rowUid) && gmsProcessName(fields[2])) {
                result += "${fields[2]}（uid=$rowUid, pid=${fields[1]}）"
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
        /** UID % 100000 得到用户内应用编号；0 是主用户，999 是分身，10+ 是工作资料。 */
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
