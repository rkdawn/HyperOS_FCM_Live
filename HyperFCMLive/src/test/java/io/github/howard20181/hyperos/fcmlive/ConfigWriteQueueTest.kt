package io.github.howard20181.hyperos.fcmlive

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class ConfigWriteQueueTest {
    private class MemoryPrefs(var success: Boolean = true) {
        val data = mutableMapOf<String, Any?>()
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor()
                "getAll" -> data.toMap()
                "contains" -> data.containsKey(args[0])
                else -> if (method.name.startsWith("get")) data[args[0]] ?: args[1] else null
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when {
                    method.name.startsWith("put") -> { pending[args[0] as String] = args[1]; proxy }
                    method.name == "apply" -> { data.putAll(pending); null }
                    method.name == "commit" -> { if (success) data.putAll(pending); success }
                    else -> proxy
                }
            } as SharedPreferences.Editor
        }
    }
    @Test fun failedCommitKeepsPendingAndDoesNotNotify() {
        val local = MemoryPrefs()
        val remote = MemoryPrefs(false)
        var notified = false
        ConfigWriteQueue(Executor { it.run() }).write(local.prefs, remote.prefs, "value", "pending", true) { notified = true }
        assertEquals(true, local.data["pending"])
        assertEquals(true, local.data["value"])
        assertFalse(notified)
    }
    @Test fun olderSuccessfulWriteCannotClearNewerPendingValue() {
        val jobs = mutableListOf<Runnable>()
        val queue = ConfigWriteQueue(Executor { jobs += it })
        val local = MemoryPrefs()
        val remote = MemoryPrefs()
        queue.write(local.prefs, remote.prefs, "value", "pending", true)
        queue.write(local.prefs, remote.prefs, "value", "pending", false)
        jobs[0].run()
        assertEquals(true, local.data["pending"])
        assertEquals(false, local.data["value"])
        jobs[1].run()
        assertEquals(false, local.data["pending"])
        assertEquals(false, remote.data["value"])
    }
    @Test fun offlineAndEmptyAllowlistArePreserved() {
        val local = MemoryPrefs()
        val queue = ConfigWriteQueue(Executor { it.run() })
        queue.write(local.prefs, null, "allow", "pending", setOf("a.b"))
        assertEquals(true, local.data["pending"])
        val remote = MemoryPrefs()
        queue.write(local.prefs, remote.prefs, "allow", "pending", emptySet<String>())
        assertEquals(emptySet<String>(), remote.data["allow"])
        assertEquals(false, local.data["pending"])
    }
}
