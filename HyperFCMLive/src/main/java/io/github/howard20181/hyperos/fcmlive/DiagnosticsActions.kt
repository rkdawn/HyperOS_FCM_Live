package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.os.SystemClock
import com.topjohnwu.superuser.Shell
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/** 只由确认对话框触发。检测和打开页面不会自动写设置或发重连广播。 */
internal object DiagnosticsActions {
    enum class Action(val title: String, val explanation: String) {
        RECONNECT("尝试重新连接", "向当前用户的谷歌服务发送重连请求，可能短暂重建现有连接。不清除应用数据，也不能保证恢复。继续吗？"),
        REPAIR_MILLET("补回防冻结名单", "仅当已读到的主用户防冻结名单缺少谷歌服务时补入，保留其他应用。不会禁用系统省电或修改其他策略。继续吗？")
    }
    private val lock = ReentrantLock()
    private val lastRequest = AtomicLong(0L)

    fun execute(context: Context, action: Action): String {
        if (!lock.tryLock()) return "另一项操作正在执行，请稍后再试。"
        try {
            val now = SystemClock.elapsedRealtime()
            if (lastRequest.get() != 0L && now - lastRequest.get() < 30_000L) return "请间隔至少 30 秒再执行，避免反复重建连接或写入。"
            lastRequest.set(now)
            return try {
                Shell.Builder.create().setTimeout(20).build().use { shell ->
                    fun read(source: String, command: String) = RootDiagnosticsReader.read(shell, source, command)
                    val identity = read("操作权限", "id -u")
                    if (!identity.ok || identity.lines.singleOrNull()?.trim() != "0") return "操作未执行：没有取得 Root 权限。"
                    val userId = context.applicationInfo.uid / 100000
                    when (action) {
                        Action.RECONNECT -> {
                            val result = read("请求重连", "am broadcast --user $userId -a com.google.android.intent.action.GCM_RECONNECT -p com.google.android.gms")
                            if (!result.ok || result.lines.any { it.contains("Exception") || it.startsWith("Error") })
                                "重连请求未成功执行：${result.summary()}"
                            else "已发送重连请求。下方会重新检查连接；真正恢复与否请结合官方诊断或实际消息测试。"
                        }
                        Action.REPAIR_MILLET -> {
                            if (userId != 0) return "未执行：此名单由主用户维护，请在主用户空间操作。"
                            val before = read("读取防冻结名单", "settings --user 0 get system MILLET_NO_RESTRICT_APP")
                            if (!before.ok) return "未修改：原名单读取失败。${before.summary()}"
                            when (DiagnosticsParser.milletContainsGms(before.lines)) {
                                true -> "原名单已经包含谷歌服务，无需修改。"
                                null -> "未修改：设置键不存在或格式无法确认，不能安全合并名单。"
                                false -> {
                                    val entries = before.lines.single().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                    // 解析器已验证每个包名，只允许字母、数字、下划线和点。
                                    val value = (entries + DiagnosticsParser.GMS).distinct().joinToString(",")
                                    val written = read("补回防冻结名单", "settings --user 0 put system MILLET_NO_RESTRICT_APP '$value'")
                                    if (!written.ok) return "写入未确认：${written.summary()}"
                                    val after = read("复核防冻结名单", "settings --user 0 get system MILLET_NO_RESTRICT_APP")
                                    if (after.ok && DiagnosticsParser.milletContainsGms(after.lines) == true)
                                        "已补回谷歌服务，并重新读取确认。仅这一项设置已核实，不代表所有限制都已解除。"
                                    else "已尝试写入，但复核未确认成功。请导出报告，不要反复点击。"
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                "操作未完成：${e.javaClass.simpleName}。请检查 Root 授权后重试。"
            }
        } finally {
            lock.unlock()
        }
    }
}
