package io.github.howard20181.hyperos.fcmlive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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

class FcmDiagnosticsActivity : AppCompatActivity() {
    private val pendingReportFile get() = java.io.File(cacheDir, "pending-fcm-diagnostics.txt")
    private val exportReport = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val report = pendingReportFile.readText(Charsets.UTF_8)
                    check(report.isNotBlank())
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(report) }
                        ?: error("无法打开文件")
                }.isSuccess
            }
            Toast.makeText(this@FcmDiagnosticsActivity, if (saved) "报告已保存" else "报告保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(ThemeSupport.attach(newBase))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSupport.onCreate(this)
        setContentView(ComposeView(this).apply { setContent {
            HyperFCMLiveTheme { DiagnosticsScreen(onBack = { finish() }, onExport = { report ->
                lifecycleScope.launch {
                    val saved = withContext(Dispatchers.IO) { runCatching { pendingReportFile.writeText(report, Charsets.UTF_8) }.isSuccess }
                    if (saved) exportReport.launch("HyperFCMLive-diagnostics.txt")
                    else Toast.makeText(this@FcmDiagnosticsActivity, "无法暂存报告", Toast.LENGTH_SHORT).show()
                }
            }) }
        } })
    }
}

private data class FcmApp(val pkg: String, val label: String, val enabled: Boolean, val stopped: Boolean)
private data class BasicDiagnostics(val installed: Boolean?, val apps: List<FcmApp>, val warning: String)
private data class ScreenData(val basic: BasicDiagnostics, val snapshot: RootDiagnosticsReader.Snapshot?)

private fun basicDiagnostics(context: Context): BasicDiagnostics {
    val pm = context.packageManager
    val installed = try { pm.getApplicationInfo(DiagnosticsParser.GMS, 0); true }
        catch (_: PackageManager.NameNotFoundException) { false } catch (_: Exception) { null }
    return try {
        val candidates = pm.getInstalledApplications(0)
        val supported = linkedSetOf<String>()
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE
        pm.queryIntentServices(Intent("com.google.firebase.MESSAGING_EVENT"), flags).mapNotNullTo(supported) { it.serviceInfo?.packageName }
        pm.queryBroadcastReceivers(Intent("com.google.android.c2dm.intent.RECEIVE"), flags).mapNotNullTo(supported) { it.activityInfo?.packageName }
        for (app in candidates) {
            if (app.packageName in supported) continue
            val service = runCatching { pm.getServiceInfo(ComponentName(app.packageName, "com.google.firebase.messaging.FirebaseMessagingService"), flags) }.isSuccess
            val receiver = runCatching { pm.getReceiverInfo(ComponentName(app.packageName, "com.google.firebase.iid.FirebaseInstanceIdReceiver"), flags) }.isSuccess
            if (service || receiver) supported += app.packageName
        }
        val excluded = setOf("android", context.packageName, DiagnosticsParser.GMS, "com.google.android.gsf")
        BasicDiagnostics(installed, candidates.filter { it.packageName in supported && it.packageName !in excluded }.map {
            FcmApp(it.packageName, runCatching { it.loadLabel(pm).toString() }.getOrDefault(it.packageName),
                it.enabled, it.flags and ApplicationInfo.FLAG_STOPPED != 0)
        }.sortedBy { it.label }, "只列出当前用户可见且含 FCM 组件的应用，不代表已经注册或正在使用 FCM。")
    } catch (_: Exception) {
        BasicDiagnostics(installed, emptyList(), "应用列表未读取成功，请检查应用列表权限。")
    }
}

@Composable
private fun DiagnosticsScreen(onBack: () -> Unit, onExport: (String) -> Unit) {
    val activity = LocalContext.current
    val context = activity.applicationContext
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<ScreenData?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionResult by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<DiagnosticsActions.Action?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var selectedWindow by remember { mutableStateOf("24 小时") }

    suspend fun check(useRoot: Boolean, action: DiagnosticsActions.Action? = null) {
        if (busy) return
        busy = true
        error = null
        try {
            val next = withContext(Dispatchers.IO) {
                val result = action?.let { DiagnosticsActions.execute(context, it) }
                val nextData = ScreenData(basicDiagnostics(context), if (useRoot) RootDiagnosticsReader.collect(context) else null)
                nextData to result
            }
            data = next.first
            next.second?.let { actionResult = it }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            data = data?.copy(snapshot = null)
            error = "检查未完成：${e.javaClass.simpleName}。请重试或导出报告。"
        } finally { busy = false }
    }
    LaunchedEffect(Unit) { check(false) }
    fun open(intent: Intent) {
        runCatching { activity.startActivity(intent) }.onFailure {
            Toast.makeText(context, "当前系统无法打开这个页面", Toast.LENGTH_SHORT).show()
        }
    }
    val sample = data?.snapshot?.sample
    val log = data?.snapshot?.log
    val bound = Prefs.remote() != null
    val windows = remember(log, sample?.nowMs) { DiagnosticsParser.windowStats(log?.lines.orEmpty(), sample?.nowMs ?: System.currentTimeMillis()) }
    val selectedStats = windows.firstOrNull { it.windowLabel == selectedWindow }
    val days = when (selectedWindow) { "3 天" -> 3L; "7 天" -> 7L; else -> 1L }
    val recent = remember(log, days, sample?.nowMs) {
        val now = sample?.nowMs ?: System.currentTimeMillis()
        log?.lines.orEmpty().filter { ModuleLogParser.epochMillis(it)?.let { time -> time in (now - days * 86_400_000)..now } == true }
    }
    val groups = remember(recent) { recent.groupBy {
        if (it.level in setOf("E", "F") || it.message.contains("failed", true)) "执行失败记录"
        else DiagnosticsParser.describeAction(it.message)
    }.entries.sortedWith(compareByDescending<Map.Entry<String, List<ModuleLogParser.Line>>> { it.key == "执行失败记录" }
        .thenBy { it.key == "其他检查记录" }.thenByDescending { it.value.size }) }
    val counts = remember(recent) { ModuleLogParser.gateCounts(recent) }
    val version = remember { UpdateChecker.localVersionTag(context) }
    val report = remember(data, actionResult, error, windows, bound) { buildString {
        appendLine("HyperFCMLive $version 诊断报告")
        appendLine("连接只是采样；放行记录不是消息送达。历史仅包含已采集片段。")
        error?.let { appendLine(it) }
        actionResult?.let { appendLine("最近主动操作：$it") }
        statusItems(sample, log, bound).forEach { appendLine("${it.title} [${it.verdict.label}]：${it.detail}") }
        appendLine("--- 当前用户应用 ---")
        data?.basic?.warning?.let { appendLine(it) }
        data?.basic?.apps?.forEach { appendLine("${it.label} (${it.pkg}) FCM组件=true enabled=${it.enabled} stopped=${it.stopped}；接收状态未验证") }
        appendLine("--- 日志时间窗口（非完整监控）---")
        windows.forEach { w ->
            appendLine("${w.windowLabel}：放行记录 ${w.totalGates} 条，有记录日期 ${w.coveredDays} 个；${w.firstStamp ?: "无"} 至 ${w.lastStamp ?: "无"}")
            w.gateRecords.forEach { (pkg, n) -> appendLine("  $pkg：$n 条放行记录（非送达次数）") }
            w.actionCounts.forEach { (label, n) -> appendLine("  $label：$n 条日志") }
        }
        appendLine("--- 最近活动解释 ---")
        log?.lines.orEmpty().takeLast(40).forEach { appendLine("${it.stamp} ${explainModuleEvent(it)}") }
        appendLine("--- 原始依据 ---")
        sample?.let { appendLine(it.raw) }
    } }

    pendingAction?.let { action -> AlertDialog(
        onDismissRequest = { pendingAction = null }, title = { Text(action.title) }, text = { Text(action.explanation) },
        confirmButton = { TextButton(onClick = { pendingAction = null; scope.launch { check(true, action) } }) { Text("确认执行") } },
        dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("取消") } }
    ) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        AppTopBar(R.string.fcm_diagnostics, onBack, actions = {
            TextButton(enabled = !busy, onClick = { scope.launch { check(true) } }) { Text(if (busy) "检查中" else "复查") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("check") {
                Button(enabled = !busy, onClick = { scope.launch { check(true) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (busy) "正在检查…" else "开始检查（Root）", style = MaterialTheme.typography.titleMedium)
                }
                Note("检查只读。需要更改设置或请求重连时，会另行确认。")
            }
            if (busy) item("progress") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { item("error") { Note(it) } }
            if (data?.basic?.installed == false) item("gms-missing") { DetailCard("谷歌服务安装信息", "未查询到安装信息。请检查系统谷歌基础服务和应用可见性权限。", Verdict.UNKNOWN) }
            items(primaryStatus(sample, log, bound), key = { "primary-${it.id}" }) { DetailCard(it.title, it.detail, it.verdict) }
            item("actions") {
                Column {
                    TextButton(onClick = { open(Intent().setClassName(DiagnosticsParser.GMS, "com.google.android.gms.gcm.GcmDiagnostics")) }) { Text("查看谷歌官方连接记录") }
                    if (sample?.rootAvailable == true) {
                        TextButton(enabled = !busy, onClick = { pendingAction = DiagnosticsActions.Action.RECONNECT }) { Text("通知延迟？尝试重新连接") }
                        if (sample.milletContainsGms == false && context.applicationInfo.uid / 100000 == 0) {
                            TextButton(enabled = !busy, onClick = { pendingAction = DiagnosticsActions.Action.REPAIR_MILLET }) { Text("补回防冻结名单") }
                        }
                    }
                    TextButton(enabled = data != null && !busy, onClick = { onExport(report) }) { Text("导出排查报告") }
                }
            }
            actionResult?.let { item("result") { DetailCard("操作结果", it) } }
            item("apps-toggle") { SectionToggle("检查某个应用（${data?.basic?.apps?.size ?: 0}）", showApps) { showApps = !showApps } }
            if (showApps) {
                item("app-help") { Note(data?.basic?.warning ?: "尚未读取应用列表。")
                    Note("没有独立的应用在线状态。可检查是否被禁用、强行停止及通知设置；日志没记录不能判为收不到。") }
                items(data?.basic?.apps.orEmpty(), key = { "app-${it.pkg}" }) { app ->
                    GroupRow(true, true, onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))) }) {
                        Text(app.label, style = MaterialTheme.typography.titleMedium)
                        Text(when { !app.enabled -> "应用被禁用，请检查系统设置"; app.stopped -> "应用处于停止状态，建议先手动打开一次"; else -> "检测到 FCM 组件 · 接收情况未验证" }, style = MaterialTheme.typography.bodyMedium)
                        counts[app.pkg]?.let { Text("$selectedWindow 内 ${it.count} 条广播放行记录，不是通知次数", style = MaterialTheme.typography.bodySmall) }
                        Text("点按打开系统应用设置", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            item("history-toggle") { SectionToggle("日志解读：24 小时 / 3 天 / 7 天", showHistory) { showHistory = !showHistory } }
            if (showHistory) {
                item("windows") {
                    Row(Modifier.fillMaxWidth()) { listOf("24 小时", "3 天", "7 天").forEach { label ->
                        TextButton(onClick = { selectedWindow = label }, modifier = Modifier.weight(1f)) { Text(if (label == selectedWindow) "✓ $label" else label) }
                    } }
                    Note("只分析实际读到并保存的日志片段，无法保证覆盖整段时间；没有记录不等于没收到推送。")
                    selectedStats?.let { Note("$selectedWindow：${it.totalGates} 条广播放行记录；在 ${it.coveredDays} 个日期留有记录。\n最早：${it.firstStamp ?: "无"}；最新：${it.lastStamp ?: "无"}") }
                    if (selectedStats == null) Note("尚无可按日期统计的记录。")
                }
                items(groups, key = { "event-${it.key}" }) { group ->
                    var expanded by remember(group.key) { mutableStateOf(false) }
                    GroupRow(true, true, onClick = { expanded = !expanded }) {
                        Text("${group.key} · ${group.value.size} 条日志", style = MaterialTheme.typography.titleMedium)
                        Text(explainModuleEvent(group.value.last()), style = MaterialTheme.typography.bodyMedium)
                        Text("最近 ${group.value.last().stamp} · 点按${if (expanded) "收起" else "查看原文"}", style = MaterialTheme.typography.labelMedium)
                        if (expanded) group.value.takeLast(3).forEach { Text(it.raw, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item("details-toggle") { SectionToggle("技术详情与数据来源", showDetails) { showDetails = !showDetails } }
            if (showDetails) {
                items(statusItems(sample, log, bound), key = { "detail-${it.id}" }) { DetailCard(it.title, it.detail, it.verdict) }
                items(sample?.sockets.orEmpty(), key = { "socket-${it.key}" }) { socket ->
                    DetailCard("${socket.userLabel} · ${socket.endpoint}", "UID ${socket.uid} · ${socket.stateLabel} · ${if (socket.pushCandidate) "推送端口候选" else "用途未确认"}")
                }
                sample?.processRows?.let { rows -> item("processes-all") { DetailCard("所有已确认的谷歌进程", rows.joinToString("\n") { it.display }) } }
            }
            item("privacy") { Note("报告含应用包名和网络地址，分享前请检查。不会自动上传，也没有新增常驻监控。") }
        }
    }
}

@Composable
private fun SectionToggle(title: String, expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text("${if (expanded) "收起" else "展开"} · $title", style = MaterialTheme.typography.titleMedium)
    }
}
@Composable
private fun Note(text: String) = Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
@Composable
private fun DetailCard(title: String, detail: String, verdict: Verdict = Verdict.INFO) {
    GroupRow(true, true, onClick = null) {
        Text("$title · ${verdict.label}", style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
