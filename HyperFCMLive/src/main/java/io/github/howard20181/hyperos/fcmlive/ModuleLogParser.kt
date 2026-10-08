package io.github.howard20181.hyperos.fcmlive

import java.time.LocalDateTime

/** 同时识别 LSPosed 文件和 logcat；先核实模块身份，再解释消息。 */
internal object ModuleLogParser {
    const val MODULE_TAG = "HyperGreeze"
    const val MODULE_PACKAGE = "io.github.howard20181.hyperos.fcmlive"
    private val stamp = Regex("(?:\\d{4}-)?\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}\\.\\d{3,9}")
    private val bracketHeader = Regex("\\s([VDIWEF])/([^]]+)]\\s*(.*)$")
    private val logcatHeader = Regex("\\s+.*?\\b([VDIWEF])(?:/|\\s+)([^:]+):\\s*(.*)$")
    private val packageField = Regex("(?:^|\\s)(?:pkg|callee)=([a-zA-Z0-9_.]+)(?=\\s|[,)]|$)")
    private val rxField = Regex("(?:^|\\s)rx=(\\+?)(\\d+)B")
    private val txField = Regex("(?:^|\\s)tx=(\\+?)(\\d+)B")

    data class Line(
        val stamp: String,
        val time: String,
        val level: String,
        val message: String,
        val raw: String,
        val process: String = ""
    )

    fun parse(raw: String): Line? {
        val text = raw.trim()
        val date = stamp.find(text) ?: return null
        if (text.substring(0, date.range.first).trim() !in listOf("", "[")) return null
        val afterDate = text.substring(date.range.last + 1)
        val header = if (text.startsWith("[")) bracketHeader.find(afterDate)
            else logcatHeader.matchEntire(afterDate)
        header ?: return null
        val level = header.groupValues[1]
        val tag = header.groupValues[2].trim().substringBefore('(').trim()
        var body = header.groupValues[3]
        var process = ""
        // API 102 常用 LSPosedFramework 标签，模块自己的 tag 在消息封套里。
        val moduleEnvelope = "[$MODULE_PACKAGE,$MODULE_TAG,"
        if (tag != MODULE_TAG) {
            if (tag !in setOf("LSPosedFramework", "LSPosed-Bridge")) return null
            val begin = body.indexOf(moduleEnvelope)
            if (begin < 0) return null
            // 封套必须位于框架消息开头，不能把其他模块引用的日志文本算进来。
            val prefix = body.substring(0, begin).trim()
            process = prefix.removeSurrounding("(", ")")
            if (prefix.isNotEmpty() && !Regex("\\([^\\[\\]\\r\\n()]*\\)").matches(prefix)) return null
            val end = body.indexOf(']', begin)
            if (end < 0) return null
            body = body.substring(end + 1).trimStart()
        }
        val normalized = date.value.replace('T', ' ')
        val time = normalized.substringAfter(' ').substringBefore('.')
        return Line(normalized, time, level, body, text, process)
    }

    /** 日志合并可能重复读取同一行；按完整记录去重，不按消息文字去重。 */
    fun parseAll(lines: List<String>): List<Line> = lines.mapNotNull(::parse)
        .distinctBy { it.raw }.sortedBy { it.stamp }

    fun gatePackage(message: String): String? {
        if (!message.startsWith("fcm-gate: ") && !message.startsWith("delivery: ")) return null
        val pkg = packageField.find(message)?.groupValues?.get(1) ?: return null
        return pkg.takeIf { it != "null" && it.contains('.') }
    }

    fun calleePackage(message: String): String? = packageField.find(message)?.groupValues?.get(1)

    data class Traffic(val rx: Long, val tx: Long, val delta: Boolean)
    fun trafficOf(message: String): Traffic? {
        val rx = rxField.find(message) ?: return null
        val tx = txField.find(message) ?: return null
        if (rx.groupValues[1] != tx.groupValues[1]) return null
        return Traffic(rx.groupValues[2].toLongOrNull() ?: return null,
            tx.groupValues[2].toLongOrNull() ?: return null,
            rx.groupValues[1] == "+" && tx.groupValues[1] == "+")
    }

    data class GateCount(val count: Int, val lastStamp: String)
    fun gateCounts(lines: List<Line>): Map<String, GateCount> {
        val result = linkedMapOf<String, GateCount>()
        for (line in lines.distinctBy { it.raw }.sortedBy { it.stamp }) {
            val pkg = gatePackage(line.message) ?: continue
            result[pkg] = GateCount((result[pkg]?.count ?: 0) + 1, line.stamp)
        }
        return result
    }

    private val timestampFormat = java.time.format.DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd HH:mm:ss")
        .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 3, 9, true)
        .toFormatter().withResolverStyle(java.time.format.ResolverStyle.STRICT)

    fun epochMillis(line: Line): Long? = runCatching {
        // 无年份的 logcat 只用于近期事件，不据此确认“本次启动已注入”。
        if (line.stamp.length < 23) return null
        LocalDateTime.parse(line.stamp, timestampFormat)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
}
