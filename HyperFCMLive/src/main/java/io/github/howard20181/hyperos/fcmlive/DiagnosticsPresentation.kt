package io.github.howard20181.hyperos.fcmlive

/** 当前采样与历史记录分离；其他用户的连接不会替主用户报在线。 */
internal data class GmsSample(
    val uid: Int?, val uidEvidence: String, val processes: List<String>?,
    val sockets: List<DiagnosticsParser.Socket>?, val gmsLimitEnabled: Boolean?,
    val bootId: String?, val bootEpochMs: Long?, val nowMs: Long,
    val rootAvailable: Boolean, val raw: String, val socketReadComplete: Boolean,
    val milletContainsGms: Boolean? = null, val aurogonConfigured: Boolean? = null,
    val deviceIdleGms: Boolean? = null,
    val verifiedUids: Set<Int> = listOfNotNull(uid).toSet(),
    val processRows: List<DiagnosticsParser.ProcessRow>? = null
) {
    val pushSockets get() = sockets.orEmpty().filter { it.uid == uid && it.established && it.pushCandidate }
    val observedOnline: Boolean? get() = when {
        !rootAvailable || uid == null -> null
        pushSockets.isNotEmpty() -> true
        socketReadComplete -> false
        else -> null
    }
}
internal data class LogRead(val lines: List<ModuleLogParser.Line>, val ok: Boolean, val evidence: String)
internal enum class Verdict(val label: String) { OK("已核实"), INFO("供参考"), UNKNOWN("未确认"), ATTENTION("需检查") }
internal data class DiagnosticItem(val id: String, val title: String, val detail: String, val verdict: Verdict)

internal fun currentModuleRecords(sample: GmsSample, log: LogRead?): List<ModuleLogParser.Line> {
    val boot = sample.bootEpochMs ?: return emptyList()
    return log?.lines.orEmpty().filter { line ->
        ModuleLogParser.epochMillis(line)?.let { it in boot..sample.nowMs } == true
    }
}

internal fun statusItems(sample: GmsSample?, log: LogRead?, bound: Boolean): List<DiagnosticItem> = buildList {
    if (sample == null || !sample.rootAvailable) {
        add(DiagnosticItem("root", "检测权限", if (sample == null) "尚未检测。点“开始检查”，按提示授予 Root 权限。"
            else "未取得 Root 权限，本次不能检查系统状态。\n${sample.raw}", Verdict.UNKNOWN))
        return@buildList
    }
    add(DiagnosticItem("root", "检测权限", "本次已验证 Root 身份，检查过程只读。", Verdict.OK))
    val currentProcesses = sample.processRows?.filter { it.uid == sample.uid }?.map { it.display } ?: sample.processes
    add(DiagnosticItem("process", "谷歌服务进程", when {
        !currentProcesses.isNullOrEmpty() -> "本次发现当前用户的谷歌服务进程。\n${currentProcesses.joinToString("\n")}"
        currentProcesses == null -> "进程信息未能完整读取，请查看报告里的命令结果。"
        else -> "本次未匹配到当前用户的谷歌服务进程，不能仅据此判断被杀或已停止。"
    }, if (!currentProcesses.isNullOrEmpty()) Verdict.OK else Verdict.UNKNOWN))
    add(DiagnosticItem("socket", "谷歌推送连接", when (sample.observedOnline) {
        true -> "发现 ${sample.pushSockets.size} 条谷歌服务的推送端口连接。是否真正收到消息，还需要消息测试或官方诊断确认。"
        false -> "本次没有发现推送端口连接。先确认网络可用，再复查；也可能使用其他连接方式。"
        null -> "没有取得完整连接数据，目前无法判断。"
    }, if (sample.observedOnline == true) Verdict.INFO else Verdict.UNKNOWN))
    add(DiagnosticItem("gate", "系统限制开关", when (sample.gmsLimitEnabled) {
        true -> "这一项谷歌服务限制开关开启。若通知延迟，请结合防冻结名单和实际日志排查。"
        false -> "这一项限制开关关闭；不代表所有网络、省电限制都已解除。"
        null -> "当前系统未提供可识别的开关数据。"
    }, when (sample.gmsLimitEnabled) { true -> Verdict.ATTENTION; false -> Verdict.OK; null -> Verdict.UNKNOWN }))
    add(DiagnosticItem("millet", "防冻结名单", when (sample.milletContainsGms) {
        true -> "谷歌服务已在名单中。这是一项保护条件，不是正在运行或永不冻结的证明。"
        false -> "已读到名单，但缺少谷歌服务。可在确认后补回这一项，不改动其他应用。"
        null -> "名单不可读、格式不认识或设置键不存在，暂不自动更改。"
    }, when (sample.milletContainsGms) { true -> Verdict.OK; false -> Verdict.ATTENTION; null -> Verdict.UNKNOWN }))
    add(DiagnosticItem("aurogon", "广播规则设置", when (sample.aurogonConfigured) {
        true -> "发现该设置值；不能只凭这个值判断广播是否被拦截。"
        false -> "该设置键没有配置；不能推断系统没有其他广播限制。"
        null -> "未取得可读设置。"
    }, if (sample.aurogonConfigured == null) Verdict.UNKNOWN else Verdict.INFO))
    add(DiagnosticItem("doze", "省电豁免名单", when (sample.deviceIdleGms) {
        true -> "名单中有谷歌服务，但仍可能受其他省电或网络策略影响。"
        false -> "名单中没有谷歌服务；若锁屏通知延迟，可到系统电池设置检查。"
        null -> "没有取得可识别的省电名单。"
    }, when (sample.deviceIdleGms) { true -> Verdict.OK; false -> Verdict.ATTENTION; null -> Verdict.UNKNOWN }))
    val current = currentModuleRecords(sample, log)
    val scopes = current.map { it.process }.filter { it == "system" || it == "com.miui.powerkeeper" }.distinct()
    val errors = current.count { it.level == "E" || it.level == "F" }
    add(DiagnosticItem("hook", "模块运行依据", when {
        errors > 0 -> "本次启动日志中有 $errors 条模块错误记录，请导出报告排查。"
        scopes.isNotEmpty() -> "本次启动记录到${scopes.joinToString("、") { if (it == "system") "系统侧" else "电源管理侧" }}模块活动。只能证明这些代码执行过，不代表全部功能有效。"
        else -> "当前片段不足以确认本次启动的模块活动。历史日志仍可查看，不据此判定安装失败。"
    }, if (errors > 0) Verdict.ATTENTION else if (scopes.isNotEmpty()) Verdict.INFO else Verdict.UNKNOWN))
    add(DiagnosticItem("prefs", "配置服务连接", if (bound) "已连接配置服务。系统是否应用了最新设置，还需结合执行记录。"
        else "尚未连接配置服务，可返回主界面检查模块启用情况。", if (bound) Verdict.INFO else Verdict.UNKNOWN))
    add(DiagnosticItem("logs", "日志读取", log?.evidence ?: "本次未读取日志。", if (log?.ok == true) Verdict.INFO else Verdict.UNKNOWN))
}

internal fun primaryStatus(sample: GmsSample?, log: LogRead?, bound: Boolean): List<DiagnosticItem> {
    val rows = statusItems(sample, log, bound)
    if (sample == null || !sample.rootAvailable) return rows
    val protection = when {
        sample.milletContainsGms == false -> DiagnosticItem("protection", "系统保护条件", "防冻结名单缺少谷歌服务，可检查或补回。", Verdict.ATTENTION)
        sample.milletContainsGms == null || sample.deviceIdleGms == null -> DiagnosticItem("protection", "系统保护条件", "有保护项目未能读取，展开详细信息查看原因。", Verdict.UNKNOWN)
        sample.deviceIdleGms == false -> DiagnosticItem("protection", "系统保护条件", "省电豁免名单未包含谷歌服务，通知延迟时可检查系统电池设置。", Verdict.ATTENTION)
        sample.gmsLimitEnabled == true -> DiagnosticItem("protection", "系统保护条件", "名单中有谷歌服务，但系统限制开关仍开启；通知延迟时需排查。", Verdict.ATTENTION)
        else -> DiagnosticItem("protection", "系统保护条件", "已检查防冻结与省电名单，详情可展开查看；这不是推送送达保证。", Verdict.INFO)
    }
    return listOf(rows.first { it.id == "socket" }, protection, rows.first { it.id == "hook" })
}

internal fun overallSummary(sample: GmsSample?, log: LogRead?, bound: Boolean): Pair<String, Verdict> {
    if (sample == null || !sample.rootAvailable) return "需要完成 Root 检查后才能给出系统读数。" to Verdict.UNKNOWN
    val issues = primaryStatus(sample, log, bound).count { it.verdict == Verdict.ATTENTION }
    return (if (issues > 0) "有 $issues 项需要检查，请看下方原因。" else "检查完成；连接和设置是现场读数，不代表每个应用已收到消息。") to
        if (issues > 0) Verdict.ATTENTION else Verdict.INFO
}

internal fun explainModuleEvent(line: ModuleLogParser.Line): String {
    val m = line.message
    if (line.level in setOf("E", "F") || m.contains("failed", ignoreCase = true)) return "模块记录了执行失败；保留原文用于排查。"
    if (ModuleLogParser.gatePackage(m) != null) return "模块放行了一次推送广播，不代表应用已经处理或展示通知。"
    return when {
        m.startsWith("gms probe") -> ModuleLogParser.trafficOf(m)?.let {
            "谷歌服务${if (it.delta) "自上次采样" else "累计"}接收 ${it.rx}、发送 ${it.tx} 字节；不是推送消息数量。"
        } ?: "流量探针状态记录，不用于判断消息是否送达。"
        m.contains("GMS missing from doze whitelist") -> "模块向查询结果补入谷歌服务的省电豁免项；与免打扰模式无关。"
        m.startsWith("userTable: update") -> "模块尝试调整谷歌服务省电配置，修改条数以原文为准。"
        m.startsWith("userTable:") -> "读取或维护谷歌服务省电配置，是否有修改以原文为准。"
        m.startsWith("P3: rewrote") -> "模块改写了一次谷歌服务省电场景。"
        m.startsWith("standby-firewall:") -> "模块跳过了待机限网命令；日志有节流，记录条数不是实际执行总次数。"
        m.startsWith("P4: recovery") -> "发出了重连请求，尚不能确认连接恢复。"
        m.contains("DENIED") -> "系统拒绝了一次唤醒请求；仅凭调用方不能判定某个应用的 FCM 被拦。"
        m.contains(" hooked") || m.startsWith("HyperFCMLive active in") -> "模块安装记录，不代表该功能之后一定触发。"
        else -> "例行检查记录，不直接代表推送成功或失败。"
    }
}
