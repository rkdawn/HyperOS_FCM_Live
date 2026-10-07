# 3.6.3.44 修复与验证记录

日期：2026-10-07。目标设备：Android 17 / API 37、HyperOS 4、Root + LSPosed。

## 1. 基准与范围

- 作者基准：`iamqwert/HyperOS_FCM_Live` 的 `main`，`ca1edfa4dbe2189f33e1bfba4dc7adf4f2ac5a86`（3.6.2 / 42）。本次通过 GitHub API 重新确认，作者分支没有更新。
- 个人仓库修复前远端：`7d1f4cf02e86f71374d6302338996251f83d6346`。
- 本次不是重新实现模块，更不是把界面退回原版。保留作者的系统策略、严格模式、实验开关及 Material 3 主题。
- 新测试包：3.6.3 / versionCode 44。报告从实际安装包读取版本，不再写死。

## 2. 原作者已有内容与本地新增内容

根据基准版本的源码、提交历史和 CHANGELOG 核对：

| 内容 | 归属与本次处理 |
| --- | --- |
| Compose + Material 3 Expressive、动态配色、应用列表、设置、批量选择 | 作者已有；本次不替换主题体系 |
| GMS 冻结/广播延迟/网络限制/闹钟放行等 Hook、严格模式、白名单 | 作者已有；不能把这些功能算作本次新增 |
| 国内版 greeze 分支、UDP 限制处理、GMS UID 兜底、只读流量/唤醒探针 | 作者已有；本次保留，不新增 OEM 策略绕过 |
| 睡眠模式联网、WiFi 弱信号、自启动相关实验开关 | 作者已有；本次不修改开关默认值或启用条件 |
| 首次加载、应用列表授权回调、Xposed 服务绑定、显示系统应用切换 | 作者已有；全部保留 |
| 手动刷新后回到列表顶部，避免列表随重新排序的条目跳走 | 作者已有；保留 |
| 工具栏菜单内的 FCM 诊断入口、工具栏下留白与细线刷新、可读 Root 诊断与导出 | 本地新增；本轮重点修正前几版的动画与误判问题 |

静态对照结果：`MainActivity.loadApps()` 完整函数体与作者基准一致；所有 `loadApps()` 调用表达式一致。`onResume()` 只重应用主题，没有新增扫描。

## 3. 改动依据

### 手动刷新

- 原问题：手势与自定义动画使用多份位移，收回时旧值可能钳住新值；负向移动的刷新头没有在工具栏下裁剪。
- 修改：Material3 仍识别手势，单一 `Animatable` 驱动列表和刷新头；内容区先留出工具栏高度，再裁剪；列表只向下移动，不缩小后再反向补偿高度。
- 原问题：框架异步的 `animateToThreshold` 与扫描完成动画可能争用同一动画值，互相取消。
- 修改：框架只负责手势通知，提交后的动画由单一协程串行执行。未过阈值直接回收；达到阈值后等待真实扫描完成，快速扫描至少展示约 1 秒，然后完成线条、淡出、收回列表。
- 用代次覆盖扫描在同一帧内完成的情况，不要求界面先绘制一次 `refreshing=true`。销毁/取消路径复位状态。
- 连续位移在绘制层读取；没有在每帧位移时更新整个页面的组合状态。该实现不等于已经测出真机无掉帧。

### Root 与网络判定

- 只在 UI 进程、用户点按“以 Root 检测”后创建短期 Root shell；用 `id -u=0` 验证权限，不通过“有没有弹窗”猜测授权状态。
- 命令逐项保留退出码与错误，限制等待时间和输出。命令创建本身也在异常保护内。失败或格式不认识时返回未知，不默认正常。
- 当前用户的 GMS UID 统一来自 PackageManager；概览和连接明细使用同一份 Root 采样。
- 匹配 GMS 主进程、`.persistent` 和冒号子进程，不再用普通应用不可完整读取的 `runningAppProcesses` 判断其他 UID。
- 正确解析 IPv4/IPv6；5228–5230 只是推送端口候选。443 不直接算 FCM；TCP 已建立不等于 MCS 登录或消息送达。
- 新增只读检测 `Settings.System.MILLET_NO_RESTRICT_APP`、`Settings.Global.aurogon_enable`、`dumpsys deviceidle whitelist`：分别回答模块 hook 的核心防护面（免限名单）、Aurogon 广播门控、Doze 省电豁免。三者与 greezer 是相互独立路径，不互相替代；读不到就返回未知，不判为已失效。
- `mGmsLimitEnabled` 必须明确解析到 true/false，缺失、失败或冲突为未知。
- 进程表与 socket 证据不一致时明确提示冲突，不宣称“未运行但属正常”。整轮检测异常时清除旧快照，避免把上一轮正常状态当作本轮结果。

### 日志、应用列表与统计

- 识别 `modules_<时间>.log`、LSPosedFramework 模块封套、直接 HyperGreeze 标签和 logcat 回退；先检查模块身份，过滤其他模块及重复行。
- 门控日志改称 `fcm-gate:`；兼容旧 `delivery:`。每次记录只证明模块返回放行，不证明应用处理或通知展示。
- 应用列表由可见的 FCM 组件声明识别，同时展示日志中观察到的其他包；不要求先收到推送才出现在名单里。
- 组件声明不是实际注册证明，混合推送应用不一定使用 FCM。通常由 GMS 代持共享长连接，不能编造每个应用独立的连接时长。
- 页面内相邻有效手动采样可观察候选连接消失；超过 60 秒、缺测、重启则重建观察段。0 次不等于一直在线，观察跨度也不是实际连接寿命。
- GMS 累计流量和采样增量分别解释，不把全部 GMS 流量或启动基线称为“30 分钟 FCM 流量”。Doze 表述为省电豁免，不是免打扰。
- 导出包含中文摘要、应用声明与门控记录、采样范围、近期活动解释、命令来源及有限原始依据。私有缓存保护文档选择器期间的 Activity 重建；不自动上传。
- 删除了不再使用且含误导断言的旧诊断资源；菜单和桌面快捷方式统一进入新诊断页，页内保留 GMS 官方诊断入口。

### 保留的早期本地修复

- 更新缓存：覆盖安装后重新比较缓存版本与本地完整版本号，避免旧更新徽标继续显示；不禁用作者的更新检查。
- 安装计数：每次安装/热重载开始清零，避免重复累积。安装数量不代表运行时每个 Hook 均有效。
- ProcessPolicy：只返回扩充后的副本，不另外修改 ROM 返回列表；与作者“每次查询追加”的设计一致。
- 五处补充 `deoptimize`：保留与现有注册方式的一致性；这不能证明 HyperOS 4 上所有调用方内联已解除，也不能替代真机日志。
- Root 依赖仅为 libsu Core 6.0.0，JitPack 仓库限定该依赖组，并补上应用内 Apache-2.0 许可条目。

## 4. 验证结果

单元测试 40 个全部通过（DiagnosticsParser 9、DiagnosticsPresentation 4、McuColor 6、ModuleLogParser 5、RootDiagnosticsEvidence 2、RefreshFeedbackState 8、UpdateCheckerVersion 6）。发布包 `HyperFCMLive-3.6.3.44-release.apk` 构建成功，versionCode 44 / versionName 3.6.3，签名核验通过（V2，Android Debug，测试包）。SHA-256：`bc294fba368711a8de0668ead1b45411f3f6baeee3c1b9716aa3ba0ae2781b80`。

Lint 静态检查因离线缺 `com.android.tools.lint:lint-gradle:32.4.1` 依赖未能运行，属环境依赖问题，不是源码检查报错。

已确认的检查：

- 作者版本与个人远端版本核实。
- `loadApps()` 函数及调用表达式对照一致。
- `git diff --check` 无空白错误。
- 旧 `diag_*` 文案没有引用后才清理。
- ADB 当前没有连接设备。

## 5. 真机验证边界与测试清单

没有可连接的手机，因此本轮不能验证真实帧率、ROM 命令格式、Root 管理器交互、实际 LSPosed 日志路径、Hook 命中与真实推送送达。不宣称“零错误”或“实机已全部通过”。

安装新版后建议依次检查：

1. 轻拉未到阈值后松手：不扫描、不强制停留 1 秒，列表平滑回收。
2. 拉过阈值：列表整体下移，细线始终在标题栏下方的留白中；完成后淡出并收回。
3. 连续操作与进出设置：不突然跳位、不出现固定底部空洞；返回设置页不额外扫描。
4. 打开 FCM 诊断，点“以 Root 检测”；授权缓存时不弹窗正常，页面应显示实际 UID 0 验证结果或具体失败原因。
5. 检查应用声明列表、GMS 连接明细、最近模块活动；点击活动可以展开原始依据。
6. 导出报告核对中文摘要、错误原因与日志来源。无日志/不识别命令应显示未知，不直接断言模块失效。
7. 要比较网络变化，在 60 秒内手动采样；这仍不是后台连续监控，不用于证明全天在线。
8. 更新模块后按 LSPosed 提示重载或重启，再核对运行日志的新记录。仅 APK 安装成功不证明系统侧已经执行新 Hook。

## 6. 参考依据

- 作者基准：https://github.com/iamqwert/HyperOS_FCM_Live/tree/ca1edfa4dbe2189f33e1bfba4dc7adf4f2ac5a86
- AndroidX PullToRefresh 实现：https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/pulltorefresh/PullToRefresh.kt
- LSPosed 日志格式：https://github.com/LSPosed/LSPosed/blob/master/daemon/src/main/jni/logcat.cpp
- LSPosed 日志命名：https://github.com/LSPosed/LSPosed/blob/master/daemon/src/main/java/org/lsposed/lspd/service/ConfigFileManager.java
- libsu 6.0.0：https://github.com/topjohnwu/libsu/tree/6.0.0
- dingwen07/hyperos-fcm-fix — greezer/MILLET 取证文档：https://github.com/dingwen07/hyperos-fcm-fix/blob/master/docs/xiaomi-hyperos-gms-fcm-greezer-investigation.md
- Kr328/HyperOSFCMFix — 当前实现与机制（MILLET/aurogon/deviceidle 排查命令）：https://github.com/Kr328/HyperOSFCMFix/blob/master/docs/HOWTO.md

AndroidX/LSPosed 的公开主分支源码用于解释机制，不保证等同手机上安装的框架版本；编译与单元测试使用本项目锁定的依赖。
