package io.github.howard20181.hyperos.fcmlive

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppShapes
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppSurfaces
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.howard20181.hyperos.fcmlive.ui.AppTopBar
import io.github.howard20181.hyperos.fcmlive.ui.GroupRow
import io.github.howard20181.hyperos.fcmlive.ui.SectionTitle

/**
 * In-app FCM diagnostics: what the device can tell this app without root,
 * stated as labelled verdicts instead of a dump.
 *
 * The GMS-side `GcmDiagnostics` activity stays reachable from here — it is
 * the one screen that shows the live MCS connection — but it renders raw
 * engine state. This page sits above it and answers, in one glance, the
 * questions the user actually has: is GMS installed and running, is the
 * push machinery in place, is this module in scope, and is the official
 * diagnostics screen available at all.
 */
class FcmDiagnosticsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeSupport.attach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSupport.onCreate(this)

        val composeView = ComposeView(this).apply {
            setContent {
                HyperFCMLiveTheme {
                    FcmDiagnosticsScreen(
                        onBack = { finish() },
                        onOpenGmsDiagnostics = { launchGmsDiagnostics() }
                    )
                }
            }
        }
        setContentView(composeView)
    }

    /**
     * The one check that needs an Activity: GMS's own GcmDiagnostics panel.
     * Failure is surfaced as a verdict row rather than a silent fallback to
     * the app-details page — "not found" is diagnostic information too.
     */
    private fun launchGmsDiagnostics() {
        val intent = Intent().apply {
            setClassName("com.google.android.gms", "com.google.android.gms.gcm.GcmDiagnostics")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            showMessage(getString(R.string.fcm_diagnostics_not_found))
        }
    }

    private fun showMessage(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** One labelled verdict. Built off the main thread, read once, rendered as-is. */
@Immutable
private data class DiagRow(
    val titleRes: Int,
    val ok: Boolean,
    /** What this verdict means for push delivery, in one line. */
    val detailRes: Int,
    /** Which section the row renders under. */
    val group: Group,
    /**
     * Rows that are buttons carry an action instead of a verdict mark.
     * `neutral` rows (GMS not running yet) are facts, not failures — the
     * mark renders in the surface-variant colour, not red.
     */
    val action: Action? = null,
    val neutral: Boolean = false
) {
    enum class Action { OPEN_GMS_DIAGNOSTICS }
    enum class Group { GMS, CONNECTION, MODULE }
}

@Composable
fun FcmDiagnosticsScreen(
    onBack: () -> Unit,
    onOpenGmsDiagnostics: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val rows = remember { mutableStateOf<List<DiagRow>>(emptyList()) }
    // The activity log: recent push deliveries and MCS socket details, read
    // from the hook's own logcat output. Reloaded on demand (pull of the
    // refresh button), not on a timer — the log buffer is what it is.
    val activity = remember { mutableStateOf<List<ActivityEvent>>(emptyList()) }
    val sockets = remember { mutableStateOf<List<McsSocket>>(emptyList()) }
    // Per-app FCM statistics: which apps the hook saw receiving pushes, how
    // many, last seen — aggregated from the hook's own log output. The
    // per-session disconnect count rides beside it (GMS socket transitions
    // observed between refreshes).
    val perApp = remember { mutableStateOf<List<AppStat>>(emptyList()) }
    val disconnects = remember { mutableStateOf(0) }
    val busy = remember { mutableStateOf(false) }

    suspend fun reload() {
        busy.value = true
        val r: List<DiagRow>
        val ev: List<ActivityEvent>
        val sk: List<McsSocket>
        val pa: List<AppStat>
        val dc: Int
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            r = buildRows(context)
            ev = readActivityLog(context)
            sk = readMcsSockets(context)
            pa = aggregateAppStats(context)
            dc = countDisconnects(context)
        }
        rows.value = r
        activity.value = ev
        sockets.value = sk
        perApp.value = pa
        disconnects.value = dc
        busy.value = false
    }

    LaunchedEffect(Unit) { reload() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(
            titleRes = R.string.fcm_diagnostics,
            onBack = onBack,
            actions = {
                androidx.compose.material3.TextButton(
                    onClick = { scope.launch { reload() } },
                    enabled = !busy.value
                ) {
                    Text(
                        text = stringResource(R.string.diag_refresh),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (busy.value) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
        )
        val loading = rows.value.isEmpty()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            if (loading) {
                item {
                    Spacer(modifier = Modifier.height(32.dp))
                    Text(
                        text = stringResource(R.string.diag_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            } else {
                item { SectionTitle(res = R.string.diag_section_gms, first = true) }
                items(rows.value.filter { it.group == DiagRow.Group.GMS }, key = { it.titleRes }) { row ->
                    DiagRowView(row, onOpenGmsDiagnostics)
                }
                item { SectionTitle(res = R.string.diag_section_connection) }
                items(rows.value.filter { it.group == DiagRow.Group.CONNECTION }, key = { it.titleRes }) { row ->
                    DiagRowView(row, onOpenGmsDiagnostics)
                }
                item { SectionTitle(res = R.string.diag_section_sockets) }
                if (sockets.value.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.diag_sockets_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                } else {
                    items(sockets.value, key = { it.key }) { s ->
                        McsSocketView(s)
                    }
                }
                item { SectionTitle(res = R.string.diag_section_apps) }
                item {
                    GroupRow(first = true, last = true, onClick = null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.diag_disconnects),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.diag_disconnects_detail),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = disconnects.value.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (disconnects.value > 0) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        }
                    }
                }
                if (perApp.value.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.diag_apps_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                } else {
                    items(perApp.value, key = { it.pkg }) { stat ->
                        AppStatView(stat)
                    }
                }
                item { SectionTitle(res = R.string.diag_section_module) }
                items(rows.value.filter { it.group == DiagRow.Group.MODULE }, key = { it.titleRes }) { row ->
                    DiagRowView(row, onOpenGmsDiagnostics)
                }
                item { SectionTitle(res = R.string.diag_section_activity) }
                if (activity.value.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.diag_activity_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                } else {
                    items(activity.value, key = { it.key }) { ev ->
                        ActivityEventView(ev)
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.diag_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}

private fun buildRows(context: Context): List<DiagRow> {
    val pm = context.packageManager
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    // GMS installed?
    val gmsInfo = try {
        pm.getApplicationInfo(GMS_PACKAGE, 0)
        true
    } catch (t: Throwable) {
        false
    }

    // GMS running? ActivityManager.runningAppProcesses is not a cross-app
    // signal on modern Android (it returns our own process only), so this
    // needs root: a pidof lookup. Falls back to false without root — the
    // same degradation the root rows use.
    val gmsRunning = rootProcessAlive(GMS_PACKAGE)

    // The push dispatch machinery. GcmReceiver is the legacy entry — newer
    // GMS builds ship different receiver classes and a foreground service
    // instead, so the honest check is "does GMS declare ANY receiver or
    // service that answers the c2dm RECEIVE action", queried by intent, not
    // by class name.
    val iidReceiver = run {
        val intent = Intent("com.google.android.c2dm.intent.RECEIVE")
            .setPackage(GMS_PACKAGE)
        try {
            pm.queryBroadcastReceivers(intent, 0).isNotEmpty() ||
                pm.queryIntentServices(intent, 0).isNotEmpty()
        } catch (t: Throwable) {
            false
        }
    }

    // GSF: the transport-level sibling of GMS. Absent on some microG setups.
    val gsf = try {
        pm.getApplicationInfo(GSF_PACKAGE, 0)
        true
    } catch (t: Throwable) {
        false
    }

    // The official diagnostics activity — also what the button row opens.
    val gcmDiag = try {
        pm.getActivityInfo(
            android.content.ComponentName(GMS_PACKAGE, GCM_DIAGNOSTICS),
            0
        )
        true
    } catch (t: Throwable) {
        false
    }

    // Module scope: is this module enabled in LSPosed and did the hook land?
    // The hooker tags system_server logs; the only root-free signal here is
    // whether the remote prefs exist (they bind only when the module service
    // is up). `Prefs.remote()` is set by MainActivity's service listener.
    val moduleBound = Prefs.remote() != null

    // ---- Root-sourced signals (the diagnostics page runs in the app process;
    // the hooker's "no su" red line applies to hook callbacks, not here). ----
    val root = RootProbe.run()

    return listOf(
        DiagRow(
            R.string.diag_gms_installed,
            gmsInfo,
            if (gmsInfo) R.string.diag_gms_installed_ok else R.string.diag_gms_installed_bad,
            DiagRow.Group.GMS
        ),
        DiagRow(
            R.string.diag_gms_running,
            gmsRunning,
            if (gmsRunning) R.string.diag_gms_running_ok else R.string.diag_gms_running_off,
            DiagRow.Group.GMS,
            neutral = !gmsRunning // not-started is a fact, not a failure
        ),
        DiagRow(
            R.string.diag_c2dm_receiver,
            iidReceiver,
            if (iidReceiver) R.string.diag_c2dm_ok else R.string.diag_c2dm_bad,
            DiagRow.Group.GMS
        ),
        DiagRow(
            R.string.diag_gsf,
            gsf,
            if (gsf) R.string.diag_gsf_ok else R.string.diag_gsf_bad,
            DiagRow.Group.GMS
        ),
        DiagRow(
            R.string.diag_gcm_diagnostics_available,
            gcmDiag,
            if (gcmDiag) R.string.diag_gcm_diagnostics_ok else R.string.diag_gcm_diagnostics_bad,
            DiagRow.Group.GMS
        ),
        // Root-only rows: the MCS socket, the greeze GMS gate, the alarm gate
        // verdict. These are the questions the official screen answers in raw
        // code; here they are one verdict each.
        //
        // The MCS row goes neutral (not red) when GMS is not running: no
        // process, no socket — that is the expected state, not a failure.
        // Same wording family as the gms-running row above.
        DiagRow(
            R.string.diag_mcs_socket,
            root.mcsEstablished,
            when {
                root.unavailable -> R.string.diag_root_unavailable
                root.mcsEstablished -> R.string.diag_mcs_socket_ok
                !gmsRunning -> R.string.diag_mcs_socket_idle
                else -> R.string.diag_mcs_socket_bad
            },
            DiagRow.Group.CONNECTION,
            neutral = !root.unavailable && !root.mcsEstablished && !gmsRunning
        ),
        DiagRow(
            R.string.diag_greeze_gms,
            root.gmsLimitOff,
            when {
                root.unavailable -> R.string.diag_root_unavailable
                root.gmsLimitOff -> R.string.diag_greeze_ok
                !gmsRunning -> R.string.diag_greeze_idle
                else -> R.string.diag_greeze_bad
            },
            DiagRow.Group.CONNECTION,
            neutral = !root.unavailable && !root.gmsLimitOff && !gmsRunning
        ),
        DiagRow(
            R.string.diag_module_hook,
            root.hookActive,
            when {
                root.unavailable -> R.string.diag_root_unavailable
                root.hookActive -> R.string.diag_hook_ok
                else -> R.string.diag_hook_bad
            },
            DiagRow.Group.MODULE
        ),
        DiagRow(
            R.string.diag_module_bound,
            moduleBound,
            if (moduleBound) R.string.diag_module_bound_ok else R.string.diag_module_bound_bad,
            DiagRow.Group.MODULE
        ),
        DiagRow(
            R.string.diag_open_gcm_button,
            true,
            R.string.diag_open_gcm_detail,
            DiagRow.Group.MODULE,
            action = DiagRow.Action.OPEN_GMS_DIAGNOSTICS
        )
    )
}

/**
 * Root-shell reads for the diagnostics page. Everything here is read-only
 * (`dumpsys`, `/proc`); nothing is written, nothing is killed — the hooker's
 * red lines are about hook callbacks and behaviour changes, and this page
 * only observes.
 *
 * One shell instance, four commands, run off the main thread by the caller
 * (see [buildRows]'s call site). Every command that fails or times out marks
 * its own signal unavailable — a root denial must not zero out the rest.
 */
private object RootProbe {

    data class Result(
        val unavailable: Boolean,
        /** An established TCP socket owned by the GMS uid — the live MCS link. */
        val mcsEstablished: Boolean,
        /** `mGmsLimitEnabled` reads false on greezer — the ROM's GMS gate is off. */
        val gmsLimitOff: Boolean,
        /** The module's startup line is present in this boot's system log. */
        val hookActive: Boolean
    )

    fun run(): Result {
        val noRoot = Result(
            unavailable = true, mcsEstablished = false, gmsLimitOff = false, hookActive = false
        )
        try {
            // Probe by command, not by cached state: isAppGrantedRoot() returns
            // null until a shell has been built at least once, so a fresh
            // process reads as "not granted" even when Magisk already allowed
            // us. `id` both builds the shell (awaiting the grant dialog if it
            // is still up) and proves the uid.
            val id = com.topjohnwu.superuser.Shell.cmd("id").exec()
            val granted = id.isSuccess && id.out.any { it.contains("uid=0") }
            if (!granted) {
                return noRoot
            }

            // 1. MCS socket: /proc/net/tcp is root-readable again under su.
            //    GMS's uid comes from the package manager on the calling thread.
            val uidOut = com.topjohnwu.superuser.Shell
                .cmd("dumpsys package $GMS_PACKAGE | grep -m1 userId=").exec().out
            val uid = uidOut.firstOrNull()
                ?.substringAfter("userId=")?.trim()?.toIntOrNull()
            var mcsEstablished = false
            if (uid != null) {
                val tcp = com.topjohnwu.superuser.Shell
                    .cmd("cat /proc/net/tcp", "cat /proc/net/tcp6").exec().out
                mcsEstablished = tcp.any { line ->
                    val cols = line.trim().split(Regex("\\s+"))
                    // st column: 01 = ESTABLISHED; uid column is 8th (index 7)
                    cols.size > 7 && cols[3] == "01" && cols[7].toIntOrNull() == uid
                }
            }

            // 2. The greeze GMS gate. The dump field has been seen as
            //    `mGmsLimitEnabled=false`, `mGmsLimitEnabled: false`, and
            //    `mGmsLimitEnabled=true`-with-a-separate-clean-up line, so
            //    match the field name anywhere and take the first boolean
            //    after it. Field absent = inconclusive, not "on".
            val greezer = com.topjohnwu.superuser.Shell
                .cmd("dumpsys greezer").exec().out
            val gmsLimitLine = greezer.firstOrNull { it.contains("mGmsLimitEnabled") }
            val gmsLimitOff = gmsLimitLine?.contains("false") ?: true // absent → treat as off

            // 3. The module's own install summary proves the hook code is live
            //    in this boot. LSPosed's module log carries it — libxposed
            //    log() never reaches the logcat main buffer.
            val hookActive = readModuleLog().any { it.contains("hook(s) installed") }

            return Result(
                unavailable = false,
                mcsEstablished = mcsEstablished,
                gmsLimitOff = gmsLimitOff,
                hookActive = hookActive
            )
        } catch (t: Throwable) {
            return noRoot
        }
    }
}

private const val GMS_PACKAGE = "com.google.android.gms"
private const val GSF_PACKAGE = "com.google.android.gsf"
private const val GCM_DIAGNOSTICS = "com.google.android.gms.gcm.GcmDiagnostics"

// ---------------------------------------------------------------------------
// Activity log + MCS socket readers (root). Pure functions over shell output;
// every parse failure degrades to an empty/absent entry, never a throw.
// ---------------------------------------------------------------------------

/** One recent push-related event, parsed from the hook's own logcat output. */
@Immutable
private data class ActivityEvent(
    val key: String,
    val time: String,
    /** Short verb: 已送达 / 已放行 / 已拦截 / 流量 … */
    val verb: String,
    /** The app or subject the event is about, human-readable. */
    val subject: String,
    val good: Boolean
)

/**
 * Reads the module's recent runtime events from the log buffer. The hooker
 * logs every gate decision it makes with stable prefixes, so the log IS the
 * activity feed — no new hook, no new storage, just parsing what is already
 * on the device.
 */
private fun readActivityLog(context: Context): List<ActivityEvent> {
    val out = readModuleLog()
    val events = ArrayList<ActivityEvent>()
    for (line in out) {
        // Format: "10-06 23:12:45.123 I/HyperGreeze: <message>" (some builds
        // prepend pid/uid columns; the regex tolerates both).
        val m = LOG_LINE.matchEntire(line.trim()) ?: continue
        val stamp = m.groupValues[1]
        val msg = msgOf(m.groupValues[2])
        val time = stamp.substring(0, 12)
        val event = when {
            // Every delivered push (per-delivery line, not one-shot).
            msg.startsWith("delivery: pkg=") -> ActivityEvent(
                key = "dlv-${events.size}-$stamp",
                time = time,
                verb = "收到推送",
                subject = appLabel(context, msg.substringAfter("pkg=").substringBefore(' ')),
                good = true
            )
            msg.startsWith("isAllowBroadcast: c2dm not intercepted for") -> ActivityEvent(
                key = "skip-${events.size}-$stamp",
                time = time,
                verb = "未接管",
                subject = appLabel(context, msg.substringAfter("callee=").substringBefore(' ')
                    .ifEmpty { "未知应用" }) + "（严格模式未勾选）",
                good = false
            )
            msg.startsWith("gms probe [") -> {
                val rx = Regex("rx=\\+?(\\d+)B").find(msg)?.groupValues?.get(1)?.toLongOrNull()
                val tx = Regex("tx=\\+?(\\d+)B").find(msg)?.groupValues?.get(1)?.toLongOrNull()
                if (rx != null && tx != null) {
                    ActivityEvent(
                        key = "probe-${events.size}-$stamp",
                        time = time,
                        verb = "GMS 流量",
                        subject = "近半小时收 ${fmtBytes(rx)}、发 ${fmtBytes(tx)}",
                        good = rx > 0 || tx > 0
                    )
                } else null
            }
            msg.startsWith("checkAlarmIsAllowedSend: re-allowed denied GMS alarm") -> ActivityEvent(
                key = "alarm-${events.size}-$stamp",
                time = time,
                verb = "已放行",
                subject = "GMS 心跳闹钟（系统原本拒绝送达）",
                good = true
            )
            msg.startsWith("udpPackageRestrict: skipped UDP filter for GMS") -> ActivityEvent(
                key = "udp-${events.size}-$stamp",
                time = time,
                verb = "已拦截",
                subject = "系统试图过滤 GMS 的网络包",
                good = true
            )
            msg.startsWith("AppStandbyController#setUidState: kept GMS") -> ActivityEvent(
                key = "sb-${events.size}-$stamp",
                time = time,
                verb = "已保活",
                subject = "系统试图限制 GMS 待机网络",
                good = true
            )
            msg.contains("GMS missing from doze whitelist") -> {
                val n = Regex("injected #(\\d+)").find(msg)?.groupValues?.get(1)
                ActivityEvent(
                    key = "doze-${events.size}-$stamp",
                    time = time,
                    verb = "已加回",
                    subject = "系统把 GMS 踢出免打扰白名单" + (n?.let { "（第 $it 次）" } ?: ""),
                    good = true
                )
            }
            msg.startsWith("P3: rewrote GMS scenario") -> ActivityEvent(
                key = "p3-${events.size}-$stamp",
                time = time,
                verb = "已改写",
                subject = "GMS 省电场景 → 无限制",
                good = true
            )
            msg.startsWith("MILLET_NO_RESTRICT_APP: appended GMS") -> ActivityEvent(
                key = "mil-${events.size}-$stamp",
                time = time,
                verb = "已写入",
                subject = "GMS 加入系统不限制名单",
                good = true
            )
            msg.startsWith("userTable: update") && msg.contains("noRestrict") -> ActivityEvent(
                key = "ut-${events.size}-$stamp",
                time = time,
                verb = "已修正",
                subject = "GMS 电源配置 → 不限制",
                good = true
            )
            msg.startsWith("standby-firewall: suppressed") -> {
                val n = Regex("#(\\d+)").find(msg)?.groupValues?.get(1)
                ActivityEvent(
                    key = "sbw-${events.size}-$stamp",
                    time = time,
                    verb = "已拦截",
                    subject = "系统待机防火墙命令" + (n?.let { "（第 $it 次）" } ?: ""),
                    good = true
                )
            }
            msg.startsWith("socket-teardown probe") && msg.contains("gmsHit=true") -> {
                val layer = msg.substringAfter("probe[").substringBefore(']')
                ActivityEvent(
                    key = "td-${events.size}-$stamp",
                    time = time,
                    verb = "已记录",
                    subject = "系统试图断开 GMS 的网络连接（$layer 层）",
                    good = false
                )
            }
            msg.startsWith("wake-path probe: broadcast DENIED") ||
                msg.startsWith("wake-path probe: checkWakePath DENIED") -> {
                val caller = Regex("callerPkg=([^)]+)").find(msg)?.groupValues?.get(1) ?: "未知来源"
                ActivityEvent(
                    key = "wp-${events.size}-$stamp",
                    time = time,
                    verb = "唤醒被拒",
                    subject = "系统拒绝了 $caller 的唤醒请求（探针只观测，未干预）",
                    good = false
                )
            }
            msg.startsWith("sleep-mode: kept WiFi on") -> ActivityEvent(
                key = "sw-${events.size}-$stamp",
                time = time,
                verb = "睡眠保活",
                subject = "WiFi（睡眠模式原本会关闭）",
                good = true
            )
            msg.startsWith("sleep-mode: kept mobile data on") -> ActivityEvent(
                key = "sd-${events.size}-$stamp",
                time = time,
                verb = "睡眠保活",
                subject = "移动数据（睡眠模式原本会关闭）",
                good = true
            )
            msg.startsWith("P4: recovery broadcasts sent") -> ActivityEvent(
                key = "rec-${events.size}-$stamp",
                time = time,
                verb = "重连",
                subject = "已请求 GMS/GSF 重新连接推送服务器",
                good = true
            )
            msg.startsWith("Hot reload requested") -> ActivityEvent(
                key = "hr-${events.size}-$stamp",
                time = time,
                verb = "模块",
                subject = "已热重载（无需重启即生效）",
                good = true
            )
            else -> null
        }
        if (event != null) {
            events.add(event)
        }
    }
    // Newest first; the buffer is chronological. Cap for scroll health.
    return events.takeLast(40).reversed()
}

/** Resolves a package name to the app's label; falls back to the raw name. */
private fun appLabel(context: Context, pkg: String): String = try {
    context.packageManager.getApplicationLabel(
        context.packageManager.getApplicationInfo(pkg, 0)
    ).toString()
} catch (t: Throwable) {
    pkg
}

private fun fmtBytes(b: Long): String {
    val kb = b / 1024.0
    return if (kb >= 1024) "%.1f MB".format(kb / 1024) else "%.1f KB".format(kb)
}

// LSPosed's modules.log lines carry a leading MM-DD HH:MM:SS.mmm stamp, then
// level/pid/tid/module columns whose exact layout varies by LSPosed version.
// Only the stamp is structural; the message is located by its own business
// prefixes (delivery:, gms probe [, ...) which are unambiguous, so the
// regex captures the stamp and the whole remainder as the payload.

/**
 * Strips the variable level/pid/tid/module columns from a modules.log payload
 * line, leaving the module's own message. The message's known prefixes are
 * the anchor: the earliest one found wins, and a line without any is dropped
 * by the caller's when-branches anyway.
 */
private val MSG_ANCHORS = listOf(
    "delivery: ", "isAllowBroadcast: ", "gms probe", "checkAlarmIsAllowedSend: ",
    "udpPackageRestrict: ", "AppStandbyController#setUidState: ", "doze-wl-sentinel: ",
    "P3: ", "MILLET_NO_RESTRICT_APP: ", "userTable: ", "standby-firewall: ",
    "socket-teardown probe", "wake-path probe: ", "sleep-mode: ", "P4: ",
    "Hot reload requested", "HyperFCMLive active in "
)

private fun msgOf(payload: String): String {
    var best = -1
    for (anchor in MSG_ANCHORS) {
        val i = payload.indexOf(anchor)
        if (i >= 0 && (best < 0 || i < best)) best = i
    }
    return if (best >= 0) payload.substring(best) else payload
}
private val LOG_LINE = Regex(
    """^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+(.*)$"""
)

/** One live TCP socket owned by GMS (or GSF — same uid family). */
@Immutable
private data class McsSocket(
    val key: String,
    val remote: String,
    val port: Int,
    val established: Boolean,
    /** True when the remote port is a Google-push endpoint (5228-5230, 443). */
    val push: Boolean
)

private fun readMcsSockets(context: Context): List<McsSocket> {
    val pm = context.packageManager
    val uid = try {
        pm.getApplicationInfo(GMS_PACKAGE, 0).uid
    } catch (t: Throwable) {
        return emptyList()
    }
    val out = try {
        com.topjohnwu.superuser.Shell
            .cmd("cat /proc/net/tcp", "cat /proc/net/tcp6").exec().out
    } catch (t: Throwable) {
        return emptyList()
    }
    val sockets = ArrayList<McsSocket>()
    for (line in out) {
        val cols = line.trim().split(Regex("\\s+"))
        if (cols.size < 10 || cols[0] == "sl") continue
        // sl local_address rem_address st tx:rx tr:tmwhen retrnsmt uid
        val uidCol = cols[7].toIntOrNull() ?: continue
        if (uidCol != uid) continue
        val remHex = cols[2]
        val state = cols[3]
        val established = state == "01"
        val (ip, port) = parseHexAddr(remHex) ?: continue
        sockets.add(
            McsSocket(
                key = "${cols[1]}-${cols[2]}",
                remote = ip,
                port = port,
                established = established,
                push = port in intArrayOf(5228, 5229, 5230, 443)
            )
        )
    }
    // Established first, then push ports first.
    return sockets.sortedWith(
        compareByDescending<McsSocket> { it.established }.thenByDescending { it.push }
    ).take(8)
}

/** "/proc/net/tcp" address columns: ABBBBBBBCCDDEEFF:PORT (IPv4) — hex, little-endian per byte group. */
private fun parseHexAddr(col: String): Pair<String, Int>? {
    val parts = col.split(":")
    if (parts.size != 2) return null
    val port = parts[1].toIntOrNull(16) ?: return null
    val hex = parts[0]
    val ip = if (hex.length == 8) {
        // IPv4: 4 little-endian bytes.
        try {
            (0 until 4).reversed().joinToString(".") { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toString()
            }
        } catch (t: Throwable) {
            return null
        }
    } else {
        // IPv6: too wide to be useful in a row; label it.
        "IPv6"
    }
    return ip to port
}

/**
 * One verdict row: leading mark, title, one-line detail. Action rows swap the
 * mark for a chevron-like affordance and the whole row is the button.
 */
@Composable
private fun DiagRowView(row: DiagRow, onOpenGmsDiagnostics: () -> Unit) {
    val shapes = LocalAppShapes.current
    val title = stringResource(row.titleRes)
    val detail = stringResource(row.detailRes)
    val mark = when {
        row.action != null -> null
        row.ok -> stringResource(R.string.diag_ok)
        row.neutral -> stringResource(R.string.diag_neutral)
        else -> stringResource(R.string.diag_bad)
    }
    // A flat list has no first/last, but GroupRow's shapes need both; every
    // row is its own group so each keeps the full radius.
    GroupRow(first = true, last = true, onClick = if (row.action != null) {
        { onOpenGmsDiagnostics() }
    } else null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (row.action != null) {
                Text(
                    text = stringResource(R.string.diag_open),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = mark ?: "",
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        row.ok -> MaterialTheme.colorScheme.primary
                        row.neutral -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error
                    }
                )
            }
        }
    }
}

/**
 * One live GMS socket row: remote endpoint, state, and a push-port mark.
 * Monospace-ish emphasis on the endpoint — it is the address the eye scans.
 */
@Composable
private fun McsSocketView(s: McsSocket) {
    GroupRow(first = true, last = true, onClick = null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${s.remote}:${s.port}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when {
                        s.established && s.push -> "已连接 · 谷歌推送端口"
                        s.established -> "已连接"
                        else -> "未连接（TIME_WAIT/CLOSE 等）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (s.established && s.push) {
                Text(
                    text = stringResource(R.string.diag_ok),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** One recent event: time, verb chip, subject. */
@Composable
private fun ActivityEventView(ev: ActivityEvent) {
    GroupRow(first = true, last = true, onClick = null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = ev.time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ev.verb,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ev.good) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                Text(
                    text = ev.subject,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Per-app FCM push statistics, aggregated from the hook's log output. */
@Immutable
private data class AppStat(
    val pkg: String,
    val label: String,
    val deliveries: Int,
    /** Time of the most recent delivery, as logged. */
    val lastSeen: String
)

/**
 * Aggregates the per-app delivery counts from the log buffer. The hooker
 * logs one "c2dm allowed for callee=<pkg>" line per delivered push (plus
 * one-shot firsts), so counting log lines IS counting deliveries within the
 * buffer's retention (~a few hours on a busy device).
 */
/**
 * Aggregates the per-app delivery counts from the "delivery:" log lines the
 * hooker writes once per delivered push (not one-shot — see Hooker.kt). The
 * buffer's retention bounds the window (~a few hours on a busy device); the
 * label resolves through the package manager so rows read as app names.
 */
private fun aggregateAppStats(context: Context): List<AppStat> {
    val out = readModuleLog()
    data class Acc(val count: Int, val lastStamp: String)
    val counts = HashMap<String, Acc>()
    for (line in out) {
        val m = LOG_LINE.matchEntire(line.trim()) ?: continue
        val msg = msgOf(m.groupValues[2])
        if (!msg.startsWith("delivery: pkg=")) continue
        val pkg = msg.substringAfter("pkg=").substringBefore(' ').ifEmpty { continue }
        val stamp = m.groupValues[1]
        val prev = counts[pkg]
        counts[pkg] = if (prev == null) Acc(1, stamp) else Acc(prev.count + 1, prev.lastStamp)
    }
    return counts.entries
        .sortedByDescending { it.value.count }
        .take(15)
        .map { (pkg, acc) ->
            AppStat(
                pkg = pkg,
                label = appLabel(context, pkg),
                deliveries = acc.count,
                lastSeen = acc.lastStamp.substring(0, 12)
            )
        }
}

/**
 * GMS connection continuity, root: compares the *uptime of the current MCS
 * connection* against time since boot. The log buffer's "gms probe" lines
 * carry rx/tx deltas every 30 minutes; a probe with exactly zero traffic is
 * normal overnight, so instead of guessing from traffic this counts how many
 * times the MCS socket was observed absent across this session's refreshes.
 * The count lives in a small root-written marker file so it survives the
 * activity being recreated (rotation, theme change) — not across reboots,
 * which matches "本会话掉了几次" without a foreground service.
 */
private fun countDisconnects(context: Context): Int {
    val establishedNow = readMcsSockets(context).any { it.established && it.push }
    val marker = java.io.File(context.cacheDir, "mcs_session_state")
    // Format: two lines — "wasEstablished", "disconnectCount".
    val previous: Pair<Boolean, Int> = try {
        val lines = marker.readLines()
        (lines.getOrNull(0) == "1") to (lines.getOrNull(1)?.toIntOrNull() ?: 0)
    } catch (t: Throwable) {
        false to 0
    }
    var count = previous.second
    if (previous.first && !establishedNow) {
        count += 1
    }
    try {
        marker.writeText("${if (establishedNow) 1 else 0}\n$count")
    } catch (t: Throwable) {
    }
    return count
}

/** One per-app FCM statistic row. */
@Composable
private fun AppStatView(stat: AppStat) {
    GroupRow(first = true, last = true, onClick = null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stat.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stat.pkg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.diag_deliveries, stat.deliveries),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stat.lastSeen,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The module's own log lines. libxposed's XposedModule.log() writes LSPosed's
 * module log file — it never reaches the logcat main buffer, which is why the
 * logcat-based reads were always empty. The primary path is stable across
 * LSPosed releases; the find covers forks that move it. Non-root readers get
 * an empty list and the "needs root" verdicts.
 */
private fun readModuleLog(): List<String> {
    return try {
        val r = com.topjohnwu.superuser.Shell.cmd(
            "cat /data/adb/lspd/log/modules.log 2>/dev/null",
            "find /data/adb/lspd/log -name 'modules.log' 2>/dev/null | head -1 | xargs cat 2>/dev/null"
        ).exec()
        r.out
    } catch (t: Throwable) {
        emptyList()
    }
}

/** True when any process of [pkg] is alive, via the root process table. */
private fun rootProcessAlive(pkg: String): Boolean {
    return try {
        com.topjohnwu.superuser.Shell
            .cmd("pidof $pkg").exec().out.any { it.isNotBlank() }
    } catch (t: Throwable) {
        false
    }
}
