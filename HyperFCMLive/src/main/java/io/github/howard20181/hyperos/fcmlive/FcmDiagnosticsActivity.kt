package io.github.howard20181.hyperos.fcmlive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.howard20181.hyperos.fcmlive.ui.AppTopBar
import io.github.howard20181.hyperos.fcmlive.ui.GroupRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI 进程中的只读诊断；不会在 system_server 中运行 su，也不更改手机策略。 */
class FcmDiagnosticsActivity : AppCompatActivity() {
    private val pendingReportFile get() = java.io.File(cacheDir, "pending-fcm-diagnostics.txt")
    private val exportReport = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            // 暂存于私有缓存，文档选择器期间 Activity 重建也不会导出空报告。
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val report = pendingReportFile.readText(Charsets.UTF_8)
                        check(report.isNotBlank()) { "暂存报告为空" }
                        contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(report) }
                            ?: error("无法打开文件")
                    }.isSuccess
                }
                android.widget.Toast.makeText(this@FcmDiagnosticsActivity,
                    if (saved) "报告已保存" else "报告保存失败", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(ThemeSupport.attach(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSupport.onCreate(this)
        setContentView(ComposeView(this).apply {
            setContent {
                HyperFCMLiveTheme {
                    FcmDiagnosticsScreen(onBack = { finish() },
                        onOpenOfficial = {
                            runCatching {
                                startActivity(Intent().setClassName(DiagnosticsParser.GMS,
                                    "com.google.android.gms.gcm.GcmDiagnostics"))
                            }.onFailure {
                                android.widget.Toast.makeText(this@FcmDiagnosticsActivity,
                                    R.string.fcm_diagnostics_not_found, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }, onExport = { report ->
                            lifecycleScope.launch {
                                val staged = withContext(Dispatchers.IO) {
                                    runCatching { pendingReportFile.writeText(report, Charsets.UTF_8) }.isSuccess
                                }
                                if (staged) exportReport.launch("HyperFCMLive-diagnostics.txt")
                                else android.widget.Toast.makeText(this@FcmDiagnosticsActivity,
                                    "无法暂存报告，请重试", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        })
                }
            }
        })
    }
}

/** 概览与连接明细共享这一份采样，不能另查一个 UID 或重读一次 socket 表。 */
internal data class GmsSample(
    val uid: Int?, val uidEvidence: String, val processes: List<String>?,
    val sockets: List<DiagnosticsParser.Socket>?, val gmsLimitEnabled: Boolean?,
    val bootId: String?, val bootEpochMs: Long?, val nowMs: Long,
    val rootAvailable: Boolean, val raw: String, val socketReadComplete: Boolean,
    val milletContainsGms: Boolean? = null,
    val aurogonConfigured: Boolean? = null,
    val deviceIdleGms: Boolean? = null
) {
    val pushSockets get() = sockets.orEmpty().filter { it.established && it.pushCandidate }
    val observedOnline: Boolean? get() = when {
        !rootAvailable -> null
        pushSockets.isNotEmpty() -> true
        socketReadComplete -> false
        else -> null
    }
}
internal data class LogRead(val lines: List<ModuleLogParser.Line>, val ok: Boolean, val evidence: String)
internal enum class Verdict(val label: String) { OK("已确认"), INFO("说明"), UNKNOWN("未知"), ATTENTION("需检查") }
internal data class DiagnosticItem(val id: String, val title: String, val detail: String, val verdict: Verdict)
private data class FcmApp(val pkg: String, val label: String, val declared: Boolean)
private data class BasicDiagnostics(val installed: Boolean?, val apps: List<FcmApp>, val warning: String)

private fun basicDiagnostics(context: Context): BasicDiagnostics {
    val pm = context.packageManager
    val installed = try { pm.getApplicationInfo(DiagnosticsParser.GMS, 0); true }
        catch (_: PackageManager.NameNotFoundException) { false } catch (_: Exception) { null }
    return try {
        // 与主列表相同的四类声明标记。不把“声明 SDK”说成“已注册/正在使用 FCM”。
        val candidates = pm.getInstalledApplications(0)
        val supported = linkedSetOf<String>()
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DIRECT_BOOT_AWARE or
            PackageManager.MATCH_DIRECT_BOOT_UNAWARE
        pm.queryIntentServices(Intent("com.google.firebase.MESSAGING_EVENT"), flags)
            .mapNotNullTo(supported) { it.serviceInfo?.packageName }
        pm.queryBroadcastReceivers(Intent("com.google.android.c2dm.intent.RECEIVE"), flags)
            .mapNotNullTo(supported) { it.activityInfo?.packageName }
        for (app in candidates) {
            if (app.packageName in supported) continue
            val service = runCatching { pm.getServiceInfo(ComponentName(app.packageName,
                "com.google.firebase.messaging.FirebaseMessagingService"), flags) }.isSuccess
            val receiver = runCatching { pm.getReceiverInfo(ComponentName(app.packageName,
                "com.google.firebase.iid.FirebaseInstanceIdReceiver"), flags) }.isSuccess
            if (service || receiver) supported += app.packageName
        }
        BasicDiagnostics(installed, candidates.filter { it.packageName in supported && it.packageName != context.packageName }
            .map { FcmApp(it.packageName, it.loadLabel(pm).toString(), true) }.sortedBy { it.label },
            "名单由本机可见的组件声明识别；受应用列表权限限制时可能不完整。声明 FCM 不等于已成功注册，混合推送应用也可能使用其他通道。")
    } catch (e: Exception) {
        BasicDiagnostics(installed, emptyList(), "应用列表读取失败：${e.javaClass.simpleName}。请检查系统授予本模块的应用列表权限。")
    }
}

internal fun statusItems(sample: GmsSample?, log: LogRead?, bound: Boolean): List<DiagnosticItem> = buildList {
    if (sample == null) {
        add(DiagnosticItem("root", "Root 检测", "点击“以 Root 检测”申请并验证 UID 0。已被 Root 管理器记住的授权可能不再弹窗。", Verdict.UNKNOWN))
        return@buildList
    }
    add(DiagnosticItem("root", "Root 检测", if (sample.rootAvailable) "本次 shell 的 id -u 返回 0；仅执行只读命令。"
        else sample.raw, if (sample.rootAvailable) Verdict.OK else Verdict.UNKNOWN))
    add(DiagnosticItem("process", "GMS 进程", when {
        !sample.processes.isNullOrEmpty() -> sample.processes.joinToString("\n") + "\n来源：Root ps，UID ${sample.uid}"
        sample.sockets.orEmpty().any { it.established } -> "进程表未匹配，但该 UID 有已建立的 TCP socket。不能据此断言 GMS 未运行，请查看报告中的原始依据。"
        sample.processes == null -> "未取得可识别的进程表，不能判断运行状态。"
        else -> "本次进程表未发现 GMS。请检查 Google Play 服务是否启用；这不证明进程被杀，也不能保证下一条推送会将其拉起。"
    }, when {
        !sample.processes.isNullOrEmpty() -> Verdict.OK
        sample.processes == null || sample.sockets.orEmpty().any { it.established } -> Verdict.UNKNOWN
        else -> Verdict.ATTENTION
    }))
    add(DiagnosticItem("socket", "GMS 推送通道", when (sample.observedOnline) {
        true -> "发现 ${sample.pushSockets.size} 条 UID ${sample.uid} 的 5228–5230 端口 TCP 已建立连接。仅是推送通道候选，不能证明 FCM 已登录或消息已送达。"
        false -> "本次完整 socket 表未发现推送端口连接。443 可能用于回退，也可能是普通 HTTPS；请结合官方诊断页检查，不能直接判为掉线。"
        null -> "网络表或 UID 读取不完整，状态未知；不会计作掉线。"
    }, if (sample.observedOnline == true) Verdict.OK else Verdict.UNKNOWN))
    add(DiagnosticItem("gate", "系统 GMS 限制开关", when (sample.gmsLimitEnabled) {
        true -> "dumpsys greezer：mGmsLimitEnabled=true。表示这一策略开关开启，不代表当前已断网。若消息延迟，请导出报告核对模块作用域与实际拦截记录。"
        false -> "dumpsys greezer：mGmsLimitEnabled=false。仅这一项限制关闭，不代表其他省电/网络限制全部解除。"
        null -> "命令失败、字段缺失或输出格式不认识，无法判断。没有把它默认成“关闭”。"
    }, if (sample.gmsLimitEnabled == null) Verdict.UNKNOWN else if (sample.gmsLimitEnabled) Verdict.ATTENTION else Verdict.OK))
    add(DiagnosticItem("millet", "MILLET 免限名单", when (sample.milletContainsGms) {
        true -> "Settings.System.MILLET_NO_RESTRICT_APP 已含 com.google.android.gms。这是本模块 hook 的关键防护面；名单存在不等于 GMS 此刻未被冻结，也不等于消息已送达。"
        false -> "MILLET_NO_RESTRICT_APP 当前不含 GMS。请确认模块已启用并在 LSPosed 勾选作用域；若仍缺失，导出报告核对 greezer 与模块日志。"
        null -> "读不到该设置（可能非 HyperOS 或字段名不同），状态未知；不会判为已失效。"
    }, when (sample.milletContainsGms) { true -> Verdict.OK; false -> Verdict.ATTENTION; null -> Verdict.UNKNOWN }))
    add(DiagnosticItem("aurogon", "Aurogon 广播门控", when (sample.aurogonConfigured) {
        true -> "Settings.Global.aurogon_enable 已配置。仅表示存在门控设置，具体放行规则需结合 c2dm 声明与模块日志判断。"
        false -> "未配置 Aurogon 门控（本 ROM 可能不启用该路径，属正常）。不是故障。"
        null -> "读不到该设置，状态未知。"
    }, if (sample.aurogonConfigured == null) Verdict.UNKNOWN else Verdict.INFO))
    add(DiagnosticItem("doze", "Doze 省电白名单", when (sample.deviceIdleGms) {
        true -> "deviceidle 白名单含 GMS（user/system/system-excidle）。这是省电豁免，与 greezer 冻结是两条独立路径，不能互相替代。"
        false -> "deviceidle 白名单未含 GMS。睡眠保活相关的网络保持可能受影响，请结合模块日志。"
        null -> "读不到 Doze 白名单，状态未知。"
    }, when (sample.deviceIdleGms) { true -> Verdict.OK; false -> Verdict.ATTENTION; null -> Verdict.UNKNOWN }))
    val installations = log?.lines.orEmpty().filter { it.message.startsWith("HyperFCMLive active in ") }
    add(DiagnosticItem("hook", "模块安装日志", if (installations.isEmpty())
        "当前读取片段没有安装摘要。可能是日志轮转、路径不同或没有记录；这不等于未注入。请在 LSPosed 确认作用域，导出报告查看日志来源。"
        else installations.takeLast(2).joinToString("\n") { "${it.stamp} ${it.message}" } +
            "\n这是历史安装记录，不是当前每个 Hook 的健康证明。",
        if (installations.isEmpty()) Verdict.UNKNOWN else Verdict.INFO))
    add(DiagnosticItem("prefs", "模块配置通道", if (bound)
        "已取得 libxposed 远程配置通道；不能据此证明系统侧全部 Hook 生效。"
        else "当前未绑定配置服务。若一直如此，请检查 LSPosed 中的模块启用情况。",
        if (bound) Verdict.OK else Verdict.UNKNOWN))
    add(DiagnosticItem("logs", "日志来源", (log?.evidence ?: "未读取") +
        "\n识别出 ${log?.lines?.size ?: 0} 条本模块记录。", if (log?.ok == true) Verdict.INFO else Verdict.UNKNOWN))
}

/** 人话解释只表达日志真正证明的事实，保留原文供核对。 */
internal fun explainModuleEvent(line: ModuleLogParser.Line): String {
    val m = line.message
    if (ModuleLogParser.gatePackage(m) != null) return "已放行推送广播；不代表目标应用已处理或显示通知"
    return when {
        m.startsWith("isAllowBroadcast: c2dm not intercepted") -> "该广播交回系统策略判断，模块未接管；不等于系统拒绝"
        m.startsWith("isAllowBroadcast: c2dm allowed") -> "首次观察到推送广播放行（一次性日志，不用于次数统计）"
        m.startsWith("gms probe [") -> ModuleLogParser.trafficOf(m)?.let {
            "GMS UID ${if (it.delta) "自上次采样" else "累计"}接收 ${it.rx} 字节、发送 ${it.tx} 字节；不是纯 FCM 流量，零增量也不证明掉线"
        } ?: "GMS 流量探针未提供有效读数"
        m.contains("re-allowed denied GMS alarm") -> "模块改写了 GMS 闹钟的拒绝结果，允许继续投递"
        m.startsWith("udpPackageRestrict:") -> "模块跳过了针对 GMS 的 UDP 过滤设置"
        m.startsWith("AppStandbyController#setUidState:") -> "模块请求保持 GMS 不受这一待机策略限制"
        m.contains("GMS missing from doze whitelist") -> "查询到的 Doze 省电豁免名单中缺少 GMS，模块尝试补入；与免打扰模式无关"
        m.startsWith("P3: rewrote") -> "模块改写了 GMS 的省电场景"
        m.startsWith("MILLET_NO_RESTRICT_APP:") -> "模块尝试将 GMS 加入系统不限制名单"
        m.startsWith("userTable: update") -> "模块尝试更新 GMS 省电配置，实际修改条数见原文"
        m.startsWith("standby-firewall:") -> "模块跳过了系统待机防火墙命令"
        m.startsWith("socket-teardown probe") -> "观察到系统连接清理方法执行；不能据此证明服务器连接实际断开"
        m.contains("DENIED") -> "观察到系统拒绝了一次唤醒请求；来源和目标见原文"
        m.startsWith("sleep-mode: kept") -> "模块阻止了本次睡眠模式关闭对应网络开关"
        m.startsWith("P4: recovery") -> "模块发出了重连请求；尚不能确认重连成功"
        m.startsWith("Hot reload requested") -> "收到模块热重载请求；不是重载成功通知"
        m.startsWith("HyperFCMLive active in ") -> "模块安装过程输出摘要；缺失目标和安装数量见原文"
        line.level == "E" || line.level == "F" -> "模块报告错误，请保留原文和同时间段的异常堆栈"
        else -> "模块运行记录（详细依据见原文）"
    }
}

private data class ScreenData(val basic: BasicDiagnostics, val snapshot: RootDiagnosticsReader.Snapshot?)

@Composable
private fun FcmDiagnosticsScreen(onBack: () -> Unit, onOpenOfficial: () -> Unit, onExport: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<ScreenData?>(null) }
    var busy by remember { mutableStateOf(false) }
    var rootRequested by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var observation by remember { mutableStateOf<DiagnosticsParser.Observation?>(null) }

    suspend fun reload(useRoot: Boolean) {
        if (busy) return
        busy = true
        error = null
        try {
            val next = withContext(Dispatchers.IO) {
                ScreenData(basicDiagnostics(context), if (useRoot) RootDiagnosticsReader.collect(context) else null)
            }
            val sample = next.snapshot?.sample
            observation = DiagnosticsParser.observe(observation, sample?.bootId, sample?.nowMs ?: 0L, sample?.observedOnline)
            data = next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            data = data?.copy(snapshot = null)
            observation = null
            error = "检测失败：${e.javaClass.simpleName}：${e.message}。可重试，不会将失败结果显示为正常。"
        } finally {
            busy = false
        }
    }
    LaunchedEffect(Unit) { reload(false) }
    val current = data
    val snapshot = current?.snapshot
    val log = snapshot?.log
    val counts = remember(log) { ModuleLogParser.gateCounts(log?.lines.orEmpty()) }
    val apps = remember(current, counts) {
        val declared = current?.basic?.apps.orEmpty()
        (declared + (counts.keys - declared.map { it.pkg }.toSet()).map { FcmApp(it, it, false) })
            .sortedWith(compareByDescending<FcmApp> { counts[it.pkg]?.count ?: 0 }.thenBy { it.label })
    }
    val versionLabel = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }
            .getOrNull()?.let { "${it.versionName} / build ${it.longVersionCode}" } ?: "版本未知"
    }
    val bound = Prefs.remote() != null
    val report = remember(current, error, versionLabel, observation, bound) {
        buildString {
            appendLine("HyperFCMLive $versionLabel 诊断报告")
            appendLine("说明：放行记录不是送达证明；手动采样不覆盖未观测的掉线。")
            error?.let { appendLine(it) }
            current?.basic?.warning?.let { appendLine(it) }
            appendLine("--- 状态与建议 ---")
            statusItems(snapshot?.sample, log, bound).forEach {
                appendLine("${it.title} [${it.verdict.label}]：${it.detail}")
            }
            appendLine("--- 应用声明与近期门控记录 ---")
            apps.forEach { app ->
                val count = counts[app.pkg]
                appendLine("${app.label} (${app.pkg})；FCM 声明=${app.declared}；" +
                    "放行记录=${count?.count ?: 0}；最近=${count?.lastStamp ?: "未观察到"}")
            }
            appendLine("--- 采样观察 ---")
            observation?.let {
                appendLine("相邻采样观察到候选连接消失 ${it.observedLosses} 次；" +
                    "本段首次观察到候选连接至今=${it.since?.let { since -> "${(it.at - since) / 1000} 秒" } ?: "本次未观察到"}。")
            } ?: appendLine("有效采样不足，未知。")
            appendLine("仅页面内手动采样；间隔超过 60 秒、缺测或重启即重建观察段，0 次不等于一直在线。")
            appendLine("--- 最近活动解释（最多 40 条）---")
            log?.lines.orEmpty().takeLast(40).forEach { appendLine("${it.stamp}：${explainModuleEvent(it)}") }
            appendLine("--- 原始依据 ---")
            snapshot?.let { appendLine(it.log.evidence); appendLine(it.sample.raw) }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        AppTopBar(R.string.fcm_diagnostics, onBack, actions = {
            TextButton(enabled = !busy, onClick = { scope.launch { reload(rootRequested) } }) {
                Text(if (busy) "检测中" else "刷新")
            }
        })
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = padding.calculateBottomPadding() + 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) item("progress") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item("root-button") {
                TextButton(enabled = !busy, onClick = {
                    rootRequested = true
                    scope.launch { reload(true) }
                }) { Text(if (rootRequested) "重新进行 Root 检测" else "以 Root 检测") }
                Note("只读检查进程、网络与模块日志；不自动修复或更改系统设置。")
            }
            error?.let { item("error") { Note(it) } }
            item("section-status") { Heading("状态与处理建议") }
            current?.let {
                item("installed") { DetailCard("Google Play 服务（GMS）",
                    when (it.basic.installed) { true -> "已查询到安装信息"; false -> "未查询到安装信息；先检查系统是否启用谷歌基础服务"; null -> "读取失败，安装状态未知" }) }
            }
            items(statusItems(snapshot?.sample, log, bound), key = { it.id }) { row ->
                DetailCard(row.title, row.detail, row.verdict)
            }
            item("section-network") { Heading("GMS 网络连接") }
            val sockets = snapshot?.sample?.sockets
            if (sockets.isNullOrEmpty()) item("socket-empty") {
                Note(if (sockets == null) "未取得可读 socket 表，不代表离线。" else "本次表中没有 GMS UID 的 TCP 连接。")
            } else items(sockets, key = { "socket-${it.key}" }) { socket ->
                DetailCard(socket.endpoint, "${socket.stateLabel} · ${if (socket.pushCandidate) "推送端口候选" else "用途未确认"}\nTCP 状态不能证明消息送达。")
            }
            item("observation") {
                val o = observation
                DetailCard("采样观察范围", if (o == null) "未取得有效采样，不能统计掉线。" else
                    "相邻有效采样观察到连接消失 ${o.observedLosses} 次。" +
                        (o.since?.let { "本段首次观察到候选连接至今 ${(o.at - it) / 1000} 秒。" } ?: "本次未观察到候选连接。") +
                        "\n仅统计本页面手动采样；间隔超过 60 秒、缺测或重启即重建观察段。0 次不等于一直在线。")
            }
            item("section-apps") { Heading("哪些应用支持 FCM") }
            item("app-help") { Note(current?.basic?.warning ?: "正在读取应用列表…")
                Note("FCM 通常由 GMS 代持共享长连接，没有每个应用独立的 FCM 连接时长。下方记录为日志片段中的广播放行次数，不是通知或消息数量。") }
            if (apps.isEmpty()) item("app-empty") { Note("当前未识别到声明 FCM 的应用；请确认应用列表权限。") }
            items(apps, key = { "app-${it.pkg}" }) { app ->
                val count = counts[app.pkg]
                DetailCard(app.label, "${app.pkg}\n${if (app.declared) "声明了 FCM 组件" else "日志中观察到广播放行"}" +
                    (count?.let { "\n放行记录 ${it.count} 条 · 最近 ${it.lastStamp}" } ?: "\n当前日志片段中没有放行记录，不代表从未收到推送。"))
            }
            item("section-events") { Heading("最近模块活动") }
            if (log?.lines.isNullOrEmpty()) item("log-empty") { Note("当前没有可解释的本模块记录。日志不可读、路径不兼容或片段为空都可能造成这一结果，详情请导出报告。") }
            items(log?.lines.orEmpty().takeLast(40).reversed(), key = { "event-${it.raw}" }) { line ->
                var expanded by remember(line.raw) { mutableStateOf(false) }
                GroupRow(first = true, last = true, onClick = { expanded = !expanded }) {
                    Text(line.stamp, style = MaterialTheme.typography.labelMedium)
                    Text(explainModuleEvent(line), style = MaterialTheme.typography.bodyMedium)
                    if (expanded) Text(line.raw, style = MaterialTheme.typography.bodySmall)
                    else Text("点按查看原始依据", style = MaterialTheme.typography.labelSmall)
                }
            }
            item("actions") {
                TextButton(onClick = onOpenOfficial) { Text("打开 GMS 官方诊断") }
                TextButton(enabled = current != null && !busy, onClick = { onExport(report) }) { Text("导出诊断报告") }
                Note("报告包含应用包名、GMS 网络地址及本模块日志。请检查内容后再分享；本应用不会自动上传。")
            }
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text, modifier = Modifier.padding(top = 16.dp, start = 8.dp, bottom = 4.dp),
    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Note(text: String) = Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun DetailCard(title: String, detail: String, verdict: Verdict = Verdict.INFO) {
    GroupRow(first = true, last = true, onClick = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            if (verdict != Verdict.INFO) Text(verdict.label, style = MaterialTheme.typography.labelMedium,
                color = when (verdict) { Verdict.ATTENTION -> MaterialTheme.colorScheme.error
                    Verdict.OK -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant })
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
