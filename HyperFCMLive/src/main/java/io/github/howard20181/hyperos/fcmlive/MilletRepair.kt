package io.github.howard20181.hyperos.fcmlive

/** 只维护已有且格式明确的名单。回读确认前，不把写入尝试报告为修复。 */
internal object MilletRepair {
    enum class Status { UNCHANGED, REPAIRED, UNAVAILABLE, WRITE_FAILED, UNCONFIRMED }
    data class Result(val status: Status)

    fun ensure(read: () -> String?, write: (String) -> Boolean): Result {
        fun parsed(): Pair<String, Boolean>? {
            val raw = read() ?: return null
            val contains = DiagnosticsParser.milletContainsGms(listOf(raw)) ?: return null
            return raw to contains
        }
        return try {
            val first = parsed() ?: return Result(Status.UNAVAILABLE)
            if (first.second) return Result(Status.UNCHANGED)
            // 合并前重读，不用过早的快照覆盖其他写入者的新条目。
            val current = parsed() ?: return Result(Status.UNAVAILABLE)
            if (current.second) return Result(Status.UNCHANGED)
            val entries = current.first.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val expected = entries + DiagnosticsParser.GMS
            if (!write(expected.joinToString(", "))) return Result(Status.WRITE_FAILED)
            val after = parsed() ?: return Result(Status.UNCONFIRMED)
            val actual = after.first.split(',').map { it.trim() }.toSet()
            Result(if (after.second && actual.containsAll(expected)) Status.REPAIRED else Status.UNCONFIRMED)
        } catch (_: Exception) {
            Result(Status.UNAVAILABLE)
        }
    }
}
