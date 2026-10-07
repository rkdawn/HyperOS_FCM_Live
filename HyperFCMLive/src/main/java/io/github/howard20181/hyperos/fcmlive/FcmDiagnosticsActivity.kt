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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
private data class BasicDiagnostics(val installed: Boolean?, val apps: List<FcmApp>, val warning: String,
    val strictMode: Boolean = false, val allowlist: Set<String> = emptySet())

private fun basicDiagnostics(context: Context): BasicDiagnostics {
    val pm = context.packageManager
    val installed = try { pm.getApplicationInfo(DiagnosticsParser.GMS, 0); true }
        catch (_: PackageManager.NameNotFoundException) { false } catch (_: Exception) { null }
    // 严格模式与勾选名单用于解释"没有放行记录"：严格模式下未勾选的应用
    // 门控本来就不插手，这不是"没收到推送"。
    val strictMode = Prefs.readLocalStrictMode(context)
    val allowlist = Prefs.readLocalAllowlist(context)
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
            "名单由本机可见的组件声明识别；受应用列表权限限制时可能不完整。声明 FCM 不等于已成功注册，混合推送应用也可能使用其他通道。" +
                if (strictMode) "严格模式已开启：未勾选的应用模块不插手，因此它们不会产生放行记录。" else "",
            strictMode, allowlist)
    } catch (e: Exception) {
        BasicDiagnostics(installed, emptyList(), "应用列表读取失败：${e.javaClass.simpleName}。请检查系统授予本模块的应用列表权限。")
    }
}

internal fun statusItems(sample: GmsSample?, log: LogRead?, bound: Boolean): List<DiagnosticItem> = buildList {
    if (sample == null) {
        add(DiagnosticItem("root", "还没做 Root 检测",
            "点上面的“以 Root 检测”按钮就能查：推送连接、系统有没有拦、模块干没干活都需要它。\n已授权过的手机不会再弹窗，直接点就行。", Verdict.UNKNOWN))
        return@buildList
    }
    add(DiagnosticItem("root", "Root 权限",
        "正常，已用 Root 读取系统信息（只读，不改任何设置）。", Verdict.OK))
    add(DiagnosticItem("process", "谷歌服务在运行吗",
        when {
            !sample.processes.isNullOrEmpty() ->
                "在运行。共 ${sample.processes.size} 个谷歌服务进程" +
                    (if (sample.processes.any { it.contains("手机分身") }) "（含手机分身里的）" else "") + "。"
            sample.sockets.orEmpty().any { it.established } ->
                "进程列表没读到，但它的网络连接是活的，所以大概率在运行。"
            sample.processes == null -> "读不到进程列表，无法确认。"
            else -> "本次没看到谷歌服务进程。如果推送正常就不用管；收不到推送时先检查谷歌服务是否被禁用。"
        } + when {
            sample.processes == null -> "\n技术细节：ps 表解析失败"
            else -> "\n技术细节：${sample.processes.joinToString("；")}"
        }, when {
            !sample.processes.isNullOrEmpty() -> Verdict.OK
            sample.processes == null || sample.sockets.orEmpty().any { it.established } -> Verdict.UNKNOWN
            else -> Verdict.ATTENTION
        }))
    add(DiagnosticItem("socket", "能收到推送吗（连接状态）", when (sample.observedOnline) {
        true -> "能。谷歌服务已连上推送服务器${if (sample.pushSockets.size > 1) "（${sample.pushSockets.size} 条连接）" else ""}，推送消息从这条通道下来。\n注意：连接在线只说明通道是通的，不保证每条消息都成功送达。"
        false -> "本次没有发现推送连接。可能是刚断开还没重连，也可能网络有问题——稍等再刷新一次，或用底部“打开 GMS 官方诊断”看实时状态。"
        null -> "读不到网络连接表，无法确认。"
    }, if (sample.observedOnline == true) Verdict.OK else Verdict.UNKNOWN))
    add(DiagnosticItem("gate", "系统在限制谷歌服务吗", when (sample.gmsLimitEnabled) {
        false -> "这一项限制是关的，系统没有主动卡谷歌推送。"
        true -> "系统有个针对谷歌服务的限制开关是开着的。不用慌：你的模块就是来对付它的，且推送连接仍然在线。\n只有当推送变慢时才需要关注这项。"
        null -> "读不到这个开关的状态，无法判断。"
    } + "\n技术细节：dumpsys greezer 的 mGmsLimitEnabled=${sample.gmsLimitEnabled ?: "未知"}",
        if (sample.gmsLimitEnabled == null) Verdict.UNKNOWN else if (sample.gmsLimitEnabled) Verdict.ATTENTION else Verdict.OK))
    add(DiagnosticItem("millet", "谷歌服务在防冻结名单里吗", when (sample.milletContainsGms) {
        true -> "在。这是模块最关键的保护——防止系统锁屏后把谷歌服务“冻住”导致推送收不到。"
        false -> "不在！模块没起作用。请确认：LSPosed 里模块已启用、作用域勾了“系统”和“电源管理”，然后重启手机。"
        null -> "读不到这个名单，无法确认。"
    } + "\n技术细节：MILLET_NO_RESTRICT_APP 设置项",
        when (sample.milletContainsGms) { true -> Verdict.OK; false -> Verdict.ATTENTION; null -> Verdict.UNKNOWN }))
    add(DiagnosticItem("aurogon", "广播拦截配置", when (sample.aurogonConfigured) {
        true -> "系统配置了广播拦截规则（具体放行情况看下面的模块活动记录）。"
        false -> "你的系统没启用这套广播拦截，属于正常，不是故障。"
        null -> "读不到，无法判断。"
    } + "\n技术细节：Settings.Global.aurogon_enable", if (sample.aurogonConfigured == null) Verdict.UNKNOWN else Verdict.INFO))
    add(DiagnosticItem("doze", "省电模式会卡推送吗", when (sample.deviceIdleGms) {
        true -> "不会。谷歌服务在系统省电豁免名单里，深度省电时也保持联网。"
        false -> "谷歌服务不在省电豁免名单里。锁屏深度省电时推送可能变慢，模块会尝试自动补入。"
        null -> "读不到省电白名单，无法确认。"
    } + "\n技术细节：dumpsys deviceidle whitelist", when (sample.deviceIdleGms) {
        true -> Verdict.OK; false -> Verdict.ATTENTION; null -> Verdict.UNKNOWN
    }))
    val installations = log?.lines.orEmpty().filter { it.message.startsWith("HyperFCMLive active in ") }
    add(DiagnosticItem("hook", "模块装好了吗",
        // 安装摘要行会被日志窗口滚掉；有实际动作记录就是模块在工作的直接证据。
        if (installations.isEmpty() && (log?.lines?.size ?: 0) < 5)
            "没找到模块在工作的证据。可能刚装还没生效，建议重启手机后再看。"
        else if (installations.isEmpty())
            "在正常工作。日志里有 ${log?.lines?.size ?: 0} 条模块实际动作记录（定期检查、防护、观察），说明钩子都活着。\n技术细节：本次片段没有安装摘要行（较早期的记录），不影响判断"
        else
            "在正常工作。模块已注入系统核心和电源管理，最近一次 ${installations.lastOrNull()?.stamp?.substringBefore('.') ?: ""}。\n技术细节：${installations.lastOrNull()?.message ?: ""}",
        Verdict.OK.takeIf { installations.isNotEmpty() || (log?.lines?.size ?: 0) >= 5 } ?: Verdict.UNKNOWN))
    add(DiagnosticItem("prefs", "设置同步", if (bound)
        "正常，你在主界面的勾选能实时传给模块。"
        else "未连接。主界面改的名单可能传不到模块——检查 LSPosed 是否启用了本模块。",
        if (bound) Verdict.OK else Verdict.UNKNOWN))
    add(DiagnosticItem("logs", "诊断依据", "从 ${log?.evidence?.lineSequence()?.firstOrNull()?.substringAfterLast('/') ?: "日志"} 读取了 ${log?.lines?.size ?: 0} 条模块记录。完整数据可点底部“导出诊断报告”。",
        if (log?.ok == true) Verdict.INFO else Verdict.UNKNOWN))
}

/** 顶部总体结论：把所有检测项浓缩成一句人话 + 分数。 */
internal fun overallSummary(sample: GmsSample?, log: LogRead?, bound: Boolean): Pair<String, Verdict> {
    if (sample == null) return "先点“以 Root 检测”，才能告诉你推送链路的整体状况" to Verdict.UNKNOWN
    val items = statusItems(sample, log, bound)
    val attention = items.count { it.verdict == Verdict.ATTENTION }
    val unknown = items.count { it.verdict == Verdict.UNKNOWN }
    val online = sample.observedOnline == true
    val protected = sample.milletContainsGms == true && sample.deviceIdleGms == true
    return when {
        online && protected && attention == 0 ->
            "推送链路正常：连接在线，系统没有卡推送，模块保护已生效" to Verdict.OK
        online && protected ->
            "推送基本正常（连接在线、保护生效），但有 $attention 项建议留意，见下方标黄的项目" to Verdict.INFO
        online ->
            "连接在线但保护不完整，可能有 ${unknown}项无法确认；推送目前能用，建议关注标黄项" to Verdict.INFO
        else ->
            "推送连接未确认在线${if (attention > 0) "，有 $attention 项需要检查" else ""}。如果收不到推送，先看下方标黄的项目" to Verdict.UNKNOWN
    }
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
        m.startsWith("checkAlarmIsAllowedSend:") -> "谷歌服务的闹钟被系统正常放行（推送重连依赖闹钟）"
        m.startsWith("wake-path probe: heartbeat") -> "唤醒路径探针的例行心跳汇总（只读观察，不改变系统行为）"
        m.startsWith("wake-path probe: denied summary") -> "唤醒请求被拒的阶段性汇总（只读观察）"
        m.startsWith("wake-path probe: broadcast gate first reach") -> "首次观察到广播路径检查（只读探针）"
        m.contains("superseded, retire") -> "旧的流量探针代次已退役（热重载后正常更替）"
        m.startsWith("probe:") -> "只读探测系统接口能力（仅记录环境，不做修改）"
        m.contains(" hooked") || m.startsWith("Allowlist receiver installed") ->
            "安装/重装系统钩子（模块注入点，启动或热重载时出现）"
        m.startsWith("userTable: ensure") || m.startsWith("userTable: GMS current") ->
            "例行确认：谷歌服务的省电配置未被系统改回"
        m.startsWith("allowlist loaded") -> "读取你勾选的应用名单"
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

/** 模块活动聚合桶：同一类动作的次数、最近时间与原始行。 */
private data class EventBucket(val explain: String, val count: Int, val last: String, val raws: List<String>)

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
    // 模块活动聚合：在组合层算好，LazyListScope 里不能调用 remember。
    val eventBuckets = remember(log) {
        log?.lines.orEmpty().takeLast(200)
            .groupBy { explainModuleEvent(it) }
            .map { (explain, lines) ->
                EventBucket(explain, lines.size, lines.maxOf { it.stamp }, lines.map { it.raw })
            }.sortedByDescending { it.count }
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
                // 通栏实底按钮：一眼可点的主要操作，不再与正文文字混淆。
                Button(
                    enabled = !busy,
                    onClick = {
                        rootRequested = true
                        scope.launch { reload(true) }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    // 主操作与下方卡片标题同级（titleMedium），比正文更醒目。
                    Text(if (rootRequested) "重新进行 Root 检测" else "以 Root 检测开始全面检查",
                        style = MaterialTheme.typography.titleMedium)
                }
                Note("只读检查推送连接、系统限制与模块日志；不自动修复、不更改任何系统设置。已授权的手机不会重复弹窗。")
            }
            error?.let { item("error") { Note(it) } }
            item("section-status") { Heading("状态与处理建议") }
            item("overall") {
                val (text, verdict) = overallSummary(snapshot?.sample, log, bound)
                DetailCard("总体状态", text, verdict)
            }
            current?.let {
                item("installed") { DetailCard("谷歌服务装了吗",
                    when (it.basic.installed) { true -> "装了，基础条件没问题。"
                        false -> "没装或被禁用——没有谷歌服务，推送不可能工作，先解决这个。"
                        null -> "读取失败，无法确认。" }) }
            }
            items(statusItems(snapshot?.sample, log, bound), key = { it.id }) { row ->
                DetailCard(row.title, row.detail, row.verdict)
            }
            item("section-network") { Heading("GMS 网络连接") }
            val sockets = snapshot?.sample?.sockets
            if (sockets.isNullOrEmpty()) item("socket-empty") {
                Note(if (sockets == null) "未取得可读 socket 表，不代表离线。" else "本次表中没有 GMS UID 的 TCP 连接。")
            } else items(sockets, key = { "socket-${it.key}" }) { socket ->
                DetailCard(socket.endpoint, "${socket.stateLabel} · ${if (socket.pushCandidate) "推送端口候选" else "用途未确认"}" +
                    " · ${socket.userLabel}（uid=${socket.uid}）\nTCP 状态不能证明消息送达。")
            }
            item("observation") {
                val o = observation
                DetailCard("采样观察范围", if (o == null) "未取得有效采样，不能统计掉线。" else
                    "相邻有效采样观察到连接消失 ${o.observedLosses} 次。" +
                        (o.since?.let { "本段首次观察到候选连接至今 ${(o.at - it) / 1000} 秒。" } ?: "本次未观察到候选连接。") +
                        "\n仅统计本页面手动采样；间隔超过 60 秒、缺测或重启即重建观察段。0 次不等于一直在线。")
            }
            item("section-apps") { Heading("哪些应用支持 FCM") }
            item("app-help") { Note("推送由谷歌服务统一代收后转发，所以这里显示的是“谁声明了推送能力”和“日志里谁真的收到过”，而不是每个应用各自的连接。有记录 = 近期确实观察到推送到达。") }
            if (apps.isEmpty()) item("app-empty") { Note("当前未识别到声明 FCM 的应用；请确认应用列表权限。") }
            items(apps, key = { "app-${it.pkg}" }) { app ->
                val count = counts[app.pkg]
                if (count != null) {
                    DetailCard(app.label, "近期观察到推送到达 ${count.count} 次，最近 ${count.lastStamp.substringBefore('.')}\n${app.pkg}",
                        Verdict.OK)
                } else {
                    DetailCard(app.label, "声明支持推送；这几小时的日志里没有它的记录，通常就是期间没收到推送，不是故障。\n${app.pkg}")
                }
            }
            item("section-events") { Heading("模块在干什么") }
            item("events-help") { Note("这是模块在系统里的实际动作清单，用来证明模块在工作。只显示最活跃的前 6 类；更多细节可导出报告查看。") }
            if (eventBuckets.isEmpty()) {
                item("log-empty") { Note("当前没有模块记录。日志不可读、路径不兼容或片段为空都可能造成这一结果，详情可导出报告。") }
            } else {
                items(eventBuckets.take(6), key = { it.explain }) { bucket ->
                    var expanded by remember(bucket.explain) { mutableStateOf(false) }
                    GroupRow(first = true, last = true, onClick = { expanded = !expanded }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(bucket.explain, modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(12.dp))
                            Text("${bucket.count} 次", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("最近 ${bucket.last.substringBefore('.')}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (expanded) {
                            // 只展开最近 3 条原文，更多数据走导出报告。
                            bucket.raws.takeLast(3).forEach { raw ->
                                Text(raw, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                            if (bucket.raws.size > 3) Text("（其余 ${bucket.raws.size - 3} 条见导出报告）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
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

/** 分组小标题：比 Heading 更贴近内容，用于列表内分组。 */
@Composable
private fun SubHeading(text: String) = Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
        // 第一段是人话结论（正常字号），"技术细节："起的内容弱化为小字。
        val splitPoint = detail.indexOf("\n技术细节：")
        if (splitPoint < 0) {
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(detail.take(splitPoint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Text(detail.substring(splitPoint + 1), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
        }
    }
}
