package io.github.howard20181.hyperos.fcmlive

import android.content.SharedPreferences
import java.util.concurrent.Executor

/** 提交成功且仍为最新修改，才清除待同步标记；失败的 Binder commit(false) 也会保留。 */
internal class ConfigWriteQueue(private val executor: Executor) {
    private val lock = Any()
    private val generations = mutableMapOf<String, Long>()

    fun write(local: SharedPreferences, remote: SharedPreferences?, key: String, pendingKey: String,
        value: Any, onCommitted: () -> Unit = {}) {
        val snapshot = if (value is Set<*>) value.filterIsInstance<String>().toSet() else value
        val generation = synchronized(lock) {
            val next = (generations[key] ?: 0L) + 1
            generations[key] = next
            put(local.edit(), key, snapshot).putBoolean(pendingKey, true).apply()
            next
        }
        if (remote == null) return
        executor.execute {
            val success = runCatching { put(remote.edit(), key, snapshot).commit() }.getOrDefault(false)
            val current = synchronized(lock) {
                val current = generations[key] == generation
                if (current) local.edit().putBoolean(pendingKey, !success).apply()
                current
            }
            if (success && current) onCommitted()
        }
    }

    private fun put(editor: SharedPreferences.Editor, key: String, value: Any): SharedPreferences.Editor = when (value) {
        is Boolean -> editor.putBoolean(key, value)
        is Int -> editor.putInt(key, value)
        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        else -> error("不支持的配置类型")
    }
}
