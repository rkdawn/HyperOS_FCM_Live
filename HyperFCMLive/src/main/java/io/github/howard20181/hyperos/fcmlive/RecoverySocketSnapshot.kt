package io.github.howard20181.hyperos.fcmlive

import java.io.InputStream

/** 不执行命令；宿主无法读取时保留未知，不把权限失败变成掉线。 */
internal object RecoverySocketSnapshot {
    const val MAX_BYTES = 512 * 1024
    const val MAX_LINES = 6000

    fun readTable(open: () -> InputStream): List<String>? = try {
        open().use { input ->
            val bytes = input.readNBytes(MAX_BYTES + 1)
            if (bytes.size > MAX_BYTES || bytes.any { it < 0 }) null
            else bytes.toString(Charsets.US_ASCII).lineSequence().toList().takeIf { it.size <= MAX_LINES }
        }
    } catch (_: Exception) { null }

    fun states(ipv4: List<String>?, ipv6: List<String>?, verifiedUids: Set<Int>): Map<Int, GmsConnectionState> {
        val v4 = ipv4?.let { DiagnosticsParser.allGmsSockets(it, verifiedUids) }
        val v6 = ipv6?.let { DiagnosticsParser.allGmsSockets(it, verifiedUids) }
        val connected = (v4.orEmpty() + v6.orEmpty()).filter { it.established && it.pushCandidate }.map { it.uid }.toSet()
        return verifiedUids.associateWith { uid -> when {
            uid in connected -> GmsConnectionState.PRESENT
            v4 != null && v6 != null -> GmsConnectionState.ABSENT
            else -> GmsConnectionState.UNKNOWN
        } }
    }
}
