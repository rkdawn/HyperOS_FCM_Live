package io.github.howard20181.hyperos.fcmlive

/** 面向用户的说明。日志只证明记录到的动作，不等同于通知送达或持续在线。 */
internal data class LogExplanation(
    val kind: String,
    val title: String,
    val happened: String,
    val impact: String,
    val advice: String,
    val needsAttention: Boolean = false
) {
    val text get() = "发生了什么：$happened\n有什么影响：$impact\n你需要做：$advice"
}

internal data class ExplainedLogGroup(val explanation: LogExplanation, val records: List<ModuleLogParser.Line>)

internal fun explainedLogGroups(lines: List<ModuleLogParser.Line>): List<ExplainedLogGroup> = lines
    .distinctBy { it.raw }.sortedBy { it.stamp }.groupBy { interpretLogEvent(it).kind }
    .values.map { ExplainedLogGroup(interpretLogEvent(it.last()), it) }
    .sortedWith(compareByDescending<ExplainedLogGroup> { it.explanation.needsAttention }
        .thenBy { it.explanation.kind == "routine" }.thenByDescending { it.records.size })

private val failedWord = Regex("\\bfailed\\b", RegexOption.IGNORE_CASE)
private val changedRows = Regex("\\bcount=(\\d+)\\b")

internal fun interpretLogEvent(line: ModuleLogParser.Line): LogExplanation {
    val m = line.message
    if (m.startsWith("recovery:")) return explainRecovery(m)
    if (m.startsWith("freeze-guard:")) return if (m.startsWith("freeze-guard: request-suppressed")) LogExplanation(
        "freeze-request", "拦下了一次冻结谷歌服务的请求", "模块阻止了这一次针对谷歌服务的冻结请求。",
        "只证明这条路径被拦下，不代表所有冻结路径都有效。", "一般不用操作；夜间通知延迟时保留这条记录。")
    else LogExplanation("freeze-decision", "系统查询时给谷歌服务免冻待遇", "模块在一次省电判断中返回了谷歌服务的豁免结果。",
        "这是判断过程，不代表刚刚发生过冻结或解冻。", "无需操作；排查时再对照实际冻结记录。")
    if (m.startsWith("MILLET_NO_RESTRICT_APP: repair")) return if (m.trim() == "MILLET_NO_RESTRICT_APP: repair repaired") LogExplanation(
        "freeze-list-verified", "保护名单已补回并复查", "补入谷歌服务后，重新读取确认名单包含它并保留了原有条目。",
        "只确认这项名单，不保证网络已经恢复。", "通常不用操作，连接恢复会另行检查。")
    else LogExplanation("freeze-list-unconfirmed", "保护名单这次没能确认修好", "读取、写入或回读确认有一步未完成。",
        "不会因此宣称修复成功，也不会仅凭这条记录请求重连。", "如果反复出现或通知有问题，导出报告。", true)
    if (line.level in setOf("E", "F") || failedWord.containsMatchIn(m)) return LogExplanation(
        "failure", "有一项操作没完成", "模块在执行某一步时记录了失败。",
        "这一步的结果没确认，不等于整个模块都失效。", "如果反复出现或通知有问题，导出报告排查。", true)
    if (ModuleLogParser.gatePackage(m) != null) return LogExplanation(
        "gate", "模块帮忙放行了推送请求", "模块允许系统继续把一次推送请求交给应用。",
        "不代表应用已经收到消息或显示了通知。", "通知正常就不用处理；没收到时，检查该应用的通知设置。")
    return when {
        m.startsWith("isAllowBroadcast: c2dm not intercepted") -> LogExplanation(
            "gate-pass-through", "这次由系统自行处理", "这次推送请求没有由模块接管。",
            "不能据此判断系统最后允许了还是拦截了它。", "需要模块保护该应用时，到主界面核对勾选和严格模式。")
        m.startsWith("isAllowBroadcast: c2dm allowed") -> LogExplanation(
            "gate-first", "首次记到模块放行请求", "模块首次记录了帮助处理推送请求。",
            "这类首次记录不会逐次打印，不能当作全部通知次数。", "一般不用处理，实际收没收到仍看应用通知。")
        m.contains("superseded, retire") -> LogExplanation(
            "retired", "旧的检查任务已结束", "模块更新后，旧版本的检查任务停止运行。",
            "这是为了避免重复检查，不是谷歌服务掉线。", "无需操作。")
        m.startsWith("gms probe") -> explainTraffic(m)
        m.contains("GMS missing from doze whitelist") -> LogExplanation(
            "doze-add", "帮谷歌服务补了一项省电例外", "模块在这次省电名单查询中补上了谷歌服务。",
            "目的是减少后台限制；这里是省电设置，与免打扰模式无关。", "通常不用操作；若锁屏后仍收消息慢，再复查。")
        m.startsWith("doze-wl-sentinel") -> LogExplanation(
            "doze-check", "正在查看省电名单", "模块检查了系统提供的省电名单。",
            "这条是检查记录，不表示已经修改设置。", "无需因为这一条日志重复修复。")
        m.startsWith("userTable: update") -> explainSettingsWrite(m)
        m.trim() == "userTable: GMS current bgControl=noRestrict" -> LogExplanation(
            "settings-checked", "这项省电设置目前是不限制", "检查时，谷歌服务这一项省电设置是“不限制”。",
            "这里只是查看结果，没有说刚刚又改了一次。", "通常不用操作；它也不能证明其他限制全部解除。")
        m.startsWith("userTable: GMS current") -> LogExplanation(
            "settings-read", "读到一项省电设置", "模块读到了谷歌服务当时的省电配置。",
            "仅凭这一条，不能判断推送被拦或设置已修好。", "通知有问题时，结合后续调整结果一起排查。")
        m.startsWith("userTable:") -> LogExplanation(
            "settings-check", "正在检查后台省电设置", "模块正在读取或尝试维护谷歌服务的省电设置。",
            "这是过程记录，不能直接当作修复成功。", "先看后续结果；不用看到这条就重启手机。")
        m.startsWith("P3: rewrote") -> LogExplanation(
            "power-change", "调整了一次后台省电处理", "模块改写了谷歌服务的一次省电处理方案。",
            "目的是减少后台限制，不保证每条消息都及时送达。", "这是自动处理记录，一般不用操作。")
        m.startsWith("MILLET_NO_RESTRICT_APP:") -> LogExplanation(
            "freeze-list", "尝试补齐后台保护名单", "模块记录了一次给谷歌服务补保护名单的操作。",
            "这项名单用于减少被系统暂停的机会，最新状态还要复查。", "回到上方重新检查，以实际名单结果为准。")
        m.startsWith("standby-firewall:") -> LogExplanation(
            "network-limit", "阻止了一次待机限网指令", "模块拦下了系统准备执行的待机限网指令。",
            "目的是减少待机对联网的影响；日志可能省略重复记录。", "一般不用处理，日志条数不是全部拦截次数。")
        m.contains("re-allowed denied GMS alarm") -> LogExplanation(
            "alarm-help", "帮谷歌服务放行定时任务", "系统原本拒绝的一次谷歌服务定时任务被模块改为允许。",
            "它可能用于保活或重连，但不代表收到了一条消息。", "一般不用操作。")
        m.startsWith("checkAlarmIsAllowedSend:") -> LogExplanation(
            "alarm-check", "系统允许了谷歌服务定时任务", "记录显示，系统这次允许谷歌服务的定时任务执行。",
            "这类任务可能用于保活或重连，不是收到通知的记录。", "无需处理。")
        m.contains("DENIED") -> LogExplanation(
            "wake-denied", "系统拒绝过后台唤醒请求", "模块观察到系统拒绝了一次唤醒应用的请求。",
            "仅凭调用方信息，不能认定某个应用的推送被拦。", "有对应的通知延迟时，把时间和报告一起提供给排查者。")
        m.startsWith("wake-path probe") -> LogExplanation(
            "wake-check", "记录了后台唤醒检查结果", "模块整理了系统检查后台唤醒请求的情况。",
            "这部分只观察，不会因为记录日志就放行或拒绝请求。", "日常不用处理；通知延迟时才需要排查这些细节。")
        m.startsWith("P4: recovery") -> LogExplanation(
            "reconnect", "尝试让谷歌服务重新连接", "模块记录了一次请求重新连接的操作。",
            "发出请求不等于已经恢复，尚不能确认连接成功。", "稍等再复查，或查看谷歌官方连接记录。")
        m.startsWith("Hot reload requested") -> LogExplanation(
            "reload", "正在重新加载模块", "模块收到了更新或重新加载的请求。",
            "这是开始加载的记录，不是全部功能已恢复的证明。", "通常稍等即可；如果有失败记录，再导出报告。")
        m.startsWith("allowlist loaded") || m.startsWith("Allowlist receiver installed") -> LogExplanation(
            "config", "正在同步你选中的应用", "模块读取了应用名单，或准备接收你的配置变化。",
            "这是为了决定照顾哪些应用，不是收到推送的记录。", "刚修改过勾选时看到这类信息很正常。")
        m.contains(" hooked") || m.startsWith("HyperFCMLive active in") -> LogExplanation(
            "installed", "记录了一次模块加载", "启动或更新时，模块记录了功能挂载信息。",
            "只能说明安装步骤执行过，不是实测推送成功。", "一般不用操作；如果出现加载失败，再导出报告。")
        m.startsWith("sleep-mode: kept") -> LogExplanation(
            "sleep-keep", "阻止了本次睡眠模式关网", "模块阻止了睡眠模式关闭对应网络开关。",
            "仅是这一次操作，不代表之后始终联网。", "只有你开启相关实验功能时才会执行，一般不用额外操作。")
        else -> LogExplanation("routine", "一条例行运行信息", "模块记录了一次检查或运行过程。",
            "这条信息本身不能说明推送成功或失败。", "通常不用处理；排查时保留原文即可。")
    }
}

private fun explainRecovery(message: String): LogExplanation = when {
    message.startsWith("recovery: request-sent") -> LogExplanation("recovery-requested", "已尝试让谷歌服务恢复连接",
        "模块根据连接检查或明确的保护修复事件，发出了一次重连请求。", "请求发出不等于恢复成功，稍后会再看连接。", "先稍等，不要连续手动重连。")
    message.startsWith("recovery: connection-present") -> LogExplanation("recovery-present", "这次检查发现推送端口连接",
        "检查时发现了谷歌服务已建立的推送端口连接。", "这次不需要继续请求重连；仍不能证明每条消息都已送达。", "通知正常就无需操作。")
    message.startsWith("recovery: request-failed") || message.startsWith("recovery: check-failed") -> LogExplanation(
        "recovery-failed", "这次连接检查或恢复请求没完成", "模块没有完成这一步操作。", "不会把失败算成连接已恢复。", "反复出现且通知有问题时导出报告。", true)
    message.startsWith("recovery: retry-limit") -> LogExplanation("recovery-limited", "这轮自动恢复已停止重试",
        "这轮恢复已达到三次尝试上限。", "不再不断发送请求，以免增加耗电或反复重建连接。", "检查网络或代理，再用官方连接记录排查。", true)
    message.startsWith("recovery: network-offline") || message.startsWith("recovery: network-changed") -> LogExplanation(
        "recovery-network", "网络尚不可用或正在切换", "网络条件还不适合发送恢复请求。", "模块不会因为暂时断网就反复重连。", "先让网络恢复，再观察通知。")
    message.startsWith("recovery: network-callback-unavailable") -> LogExplanation("recovery-callback", "暂时无法监听网络切换",
        "系统没有接受网络变化监听。", "仍会低频复查，但可能无法及时发现刚发生的切换。", "如果网络切换后经常收不到消息，导出报告排查。")
    message.startsWith("recovery: ineligible") -> LogExplanation("recovery-ineligible", "这个用户的谷歌服务暂不自动处理",
        "应用状态或用户解锁条件没有满足自动恢复要求。", "不会默认改为操作主用户，也不会强行启用已禁用的服务。", "需要时核对对应用户空间的应用状态。")
    message.startsWith("recovery: compatibility-") -> LogExplanation("recovery-compatibility", "这次兼容恢复没有继续重试",
        "系统策略事件触发的单次恢复已结束，或被冷却时间限制。", "不是恢复成功的通知，也不会在状态未知时一直重试。", "如仍有延迟，检查实际消息和官方连接记录。")
    else -> LogExplanation("recovery-unavailable", "自动连接检查暂时拿不到可靠依据",
        "宿主没能确认网络、用户身份或连接数据。", "读不到不等于掉线；通用自动恢复不会据此触发。", "已有系统保护仍保留，需要排查时手动运行 Root 检查。")
}

private fun explainTraffic(message: String): LogExplanation {
    val traffic = ModuleLogParser.trafficOf(message)
    return when {
        traffic == null -> LogExplanation("traffic-unreadable", "这轮没有拿到流量读数",
            "模块尝试检查谷歌服务的网络数据，但这条没有可用读数。",
            "数据不足，不能据此说谷歌服务离线。", "以后复查即可；持续读不到时再导出报告。")
        !traffic.delta -> LogExplanation("traffic-baseline", "记下了累计网络流量",
            "模块记下了谷歌服务当时的累计收发流量。", "它是后续比较的起点，不是收到通知的次数。", "无需操作。")
        traffic.rx == 0L && traffic.tx == 0L -> LogExplanation("traffic-idle", "这轮没有新增网络数据",
            "自上次采样检查后，没有读到新增的收发流量。", "可能只是正在待机，不能据此认定掉线。", "通知正常就不用处理；确实收不到时再检查网络。")
        else -> LogExplanation("traffic-active", "这段时间有网络数据往来",
            "谷歌服务自上次采样检查后，有新增的网络数据。", "这些数据不全是推送，不能换算成通知条数。", "通常不用操作；具体数值可展开原文查看。")
    }
}

private fun explainSettingsWrite(message: String): LogExplanation {
    val count = changedRows.find(message)?.groupValues?.get(1)?.toLongOrNull()
    return when {
        count != null && count > 0 -> LogExplanation("settings-written", "省电配置有了修改结果",
            "这次调整谷歌服务省电配置，返回了已修改的结果。", "这是当时的写入结果，不保证以后不会被系统改回。", "先正常使用；若仍有通知延迟，再复查。")
        count == 0L -> LogExplanation("settings-no-change", "这次没有改到配置",
            "这次省电配置写入没有改到任何项目。", "可能没有匹配项，不能说修复已成功。", "结合后续记录查看；反复出现且通知有问题时导出报告。")
        else -> LogExplanation("settings-attempt", "正在尝试调整省电配置",
            "模块尝试调整设置，但这条没有明确的写入结果。", "还不能判断是否改成功。", "先复查实际设置，不要连续反复修复。")
    }
}
