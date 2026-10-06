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
    /** Rows that are buttons carry an action instead of a verdict mark. */
    val action: Action? = null
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
    // The root leg shells out four commands; never on the main thread.
    LaunchedEffect(Unit) {
        val nonRoot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            buildRows(context)
        }
        rows.value = nonRoot
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(titleRes = R.string.fcm_diagnostics, onBack = onBack)
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
                item { SectionTitle(res = R.string.diag_section_module) }
                items(rows.value.filter { it.group == DiagRow.Group.MODULE }, key = { it.titleRes }) { row ->
                    DiagRowView(row, onOpenGmsDiagnostics)
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

    // GMS running? Process importance <= CACHED means it is alive and warm;
    // an empty list only means "not started since boot", which is itself
    // worth saying.
    val gmsRunning = am != null && am.runningAppProcesses.orEmpty().any {
        it.processName == GMS_PACKAGE || it.processName.startsWith("$GMS_PACKAGE:")
    }

    // The push receiver Firebase apps register against. Its presence is what
    // the whole c2dm chain dispatches through.
    val iidReceiver = try {
        pm.getReceiverInfo(
            android.content.ComponentName(
                GMS_PACKAGE,
                "com.google.android.gms.gcm.GcmReceiver"
            ),
            0
        )
        true
    } catch (t: Throwable) {
        false
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
            DiagRow.Group.GMS
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
        DiagRow(
            R.string.diag_mcs_socket,
            root.mcsEstablished,
            when {
                root.unavailable -> R.string.diag_root_unavailable
                root.mcsEstablished -> R.string.diag_mcs_socket_ok
                else -> R.string.diag_mcs_socket_bad
            },
            DiagRow.Group.CONNECTION
        ),
        DiagRow(
            R.string.diag_greeze_gms,
            root.gmsLimitOff,
            when {
                root.unavailable -> R.string.diag_root_unavailable
                root.gmsLimitOff -> R.string.diag_greeze_ok
                else -> R.string.diag_greeze_bad
            },
            DiagRow.Group.CONNECTION
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
        // libsu 6: the static entry points build/await the shell themselves.
        // `rootAccess()` returns null while the grant dialog is still pending.
        val granted = try {
            com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true ||
                com.topjohnwu.superuser.Shell.rootAccess() == true
        } catch (t: Throwable) {
            false
        }
        if (!granted) {
            return noRoot
        }
        try {
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

            // 2. The greeze GMS gate: `dumpsys greezer` prints mGmsLimitEnabled.
            val greezer = com.topjohnwu.superuser.Shell
                .cmd("dumpsys greezer").exec().out
            val gmsLimitOff = greezer.any {
                it.contains("mGmsLimitEnabled=false") || it.contains("mGmsLimitEnabled: false")
            }

            // 3. The module's own install summary proves the hook code is live
            //    in this boot: the log buffer filtered to the module's tag.
            val logs = com.topjohnwu.superuser.Shell
                .cmd("logcat -d -s HyperGreeze -t 500").exec().out
            val hookActive = logs.any { it.contains("hook(s) installed") }

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
                    color = if (row.ok) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }
    }
}
