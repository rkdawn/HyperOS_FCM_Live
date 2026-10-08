package io.github.howard20181.hyperos.fcmlive

import java.util.Properties

/** 仅存放字符串和单调时间，避免热重载后跨 ClassLoader 保留旧模块对象。 */
internal class RecoveryEpoch(private val properties: Properties = System.getProperties()) {
    private val generation = synchronized(properties) {
        val next = (properties.getProperty(GENERATION)?.toLongOrNull() ?: 0L) + 1
        properties.setProperty(GENERATION, next.toString())
        next.toString()
    }

    fun active(): Boolean = synchronized(properties) { properties.getProperty(GENERATION) == generation }

    fun lastRequest(uid: Int): Long? = synchronized(properties) {
        properties.getProperty("$LAST_REQUEST$uid")?.toLongOrNull()
    }

    fun claim(uid: Int, now: Long): Boolean = synchronized(properties) {
        if (!DiagnosticsParser.isAppUid(uid) || properties.getProperty(GENERATION) != generation) return@synchronized false
        val last = properties.getProperty("$LAST_REQUEST$uid")?.toLongOrNull()
        if (last != null && now - last < GmsRecoveryPolicy.MIN_REQUEST_GAP_MS) return@synchronized false
        properties.setProperty("$LAST_REQUEST$uid", now.toString())
        true
    }

    fun retire() = synchronized(properties) {
        if (properties.getProperty(GENERATION) == generation) {
            properties.setProperty(GENERATION, (generation.toLong() + 1L).toString())
        }
    }

    companion object {
        private const val GENERATION = "hyperfcmlive.recovery.generation"
        private const val LAST_REQUEST = "hyperfcmlive.recovery.last."
    }
}
