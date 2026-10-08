package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.ComposeView
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.ThemeEngine
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.howard20181.hyperos.fcmlive.ui.AboutActions
import io.github.howard20181.hyperos.fcmlive.ui.AboutScreen
import io.github.howard20181.hyperos.fcmlive.ui.UpdateOutcome
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Settings: appearance, allowlist backup, links, update check.
 *
 * The whole screen is Compose — [AboutScreen] owns the rows, the menus and the
 * update offer. What stays here is only what a composable cannot do: the SAF
 * contracts need an `ActivityResultRegistry`, the update check is a network
 * call, and the release it offers has to be opened with this Activity's own
 * `startActivity`. The offer itself is screen state, so it is drawn by the same
 * dialog stack as the licences page — rather than a second, framework-owned one
 * the runtime palette could not reach.
 */
class AboutActivity : AppCompatActivity() {
    private val serviceListener: (io.github.libxposed.service.XposedService?) -> Unit = {}

    /** The M3 feedback line. Owned here, drawn by [AboutScreen]'s Scaffold. */
    private val snackbarHostState = SnackbarHostState()

    /** Scope for showing a message; cancelled with the Activity. */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Where the release the check just offered lives. Held here rather than on
     * the screen because opening it launches another app, which only this class
     * can do.
     */
    private var pendingDownloadUrl: String? = null

    /**
     * SAF export/import via Activity Result API (no startActivityForResult).
     * The returned URI is externally supplied, so both paths are re-checked
     * before any stream is opened — see [isGrantedContentUri].
     */
    private val createAllowlistDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                writeAllowlistTo(uri)
            }
        }
    private val openAllowlistDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                readAllowlistFrom(uri)
            }
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeSupport.attach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSupport.onCreate(this)
        ModuleServiceConnection.subscribe(this, serviceListener)
        // Keep launcher shortcut icons in sync when the settings page opens.
        ShortcutPublisher.publish(this)

        val versionLine = versionLine()
        val composeView = ComposeView(this).apply {
            setContent {
                HyperFCMLiveTheme {
                    AboutScreen(
                        onBack = { finish() },
                        actions = AboutActions(
                            onViewSource = { openLink(REPO_URL) },
                            onLicenses = {
                                startActivity(
                                    Intent(this@AboutActivity, LicensesActivity::class.java)
                                )
                            },
                            onPrivacy = {
                                startActivity(
                                    Intent(this@AboutActivity, PrivacyActivity::class.java)
                                )
                            },
                            onExperiment = {
                                startActivity(
                                    Intent(this@AboutActivity, ExperimentActivity::class.java)
                                )
                            },
                            onExport = ::exportAllowlist,
                            onImport = ::importAllowlist,
                            onHelp = { openLink(HELP_URL) },
                            onCheckUpdate = ::checkForUpdates,
                            onUpdateOpen = ::openPendingUpdate,
                            versionLine = versionLine,
                            onAppearanceChange = ::applyAppearanceChange
                        ),
                        snackbarHostState = snackbarHostState
                    )
                }
            }
        }
        setContentView(composeView)
    }

    /**
     * The version line is bound from PackageManager — the same source the
     * update check compares against — so it cannot drift the way a hard-coded
     * string did.
     */
    private fun versionLine(): String {
        val info = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot read own package info", t)
            null
        }
        val name = info?.versionName ?: return ""
        return getString(R.string.about_sub_check_update, name, info.longVersionCode)
    }

    /**
     * Runs the check and hands the answer straight back to the screen, which
     * decides what it looks like. This side keeps only the two things the
     * screen cannot hold: the URL to open, and the badge flag that is cleared
     * when that URL is opened.
     */
    private fun checkForUpdates(report: (UpdateOutcome) -> Unit) {
        UpdateChecker.checkAsync(this, object : UpdateChecker.Callback {
            override fun onResult(
                updateAvailable: Boolean,
                latestVersion: String,
                downloadUrl: String
            ) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    if (!updateAvailable) {
                        report(UpdateOutcome.None)
                        return@runOnUiThread
                    }
                    pendingDownloadUrl = downloadUrl
                    report(UpdateOutcome.Available(latestVersion))
                }
            }

            override fun onError() {
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    report(UpdateOutcome.Error)
                }
            }
        })
    }

    /** Opens the release the check offered, and drops the badge with it. */
    private fun openPendingUpdate() {
        val url = pendingDownloadUrl ?: return
        pendingDownloadUrl = null
        UpdateChecker.clearBadge(this)
        openLink(url)
    }

    /**
     * Opens a link, and says so in the page when nothing can take it. The URL
     * itself is the message: a device with no browser can still be shown where
     * it was meant to go.
     */
    private fun openLink(url: String) {
        if (!UiUtils.openUrl(this, url)) {
            showMessage(url)
        }
    }

    /**
     * In-place re-skin: the Compose tree follows ThemeEngine's generation
     * counter and the window chrome is repainted here. The old path was
     * `recreate()` — a whole-window rebuild that read as a visible jump every
     * time an appearance menu option was picked.
     */
    private fun applyAppearanceChange() {
        ThemeEngine.invalidate()
        ThemeSupport.reapplyWindow(this)
    }

    private fun exportAllowlist() {
        try {
            createAllowlistDoc.launch("fcmlive-allowlist.txt")
        } catch (t: Throwable) {
            showMessage(R.string.allowlist_export_failed)
        }
    }

    private fun importAllowlist() {
        try {
            openAllowlistDoc.launch(arrayOf("text/plain"))
        } catch (t: Throwable) {
            showMessage(R.string.allowlist_import_failed)
        }
    }

    private fun currentAllowlist(): Set<String> =
        if (Prefs.hasPendingPush(this) || Prefs.remote() == null) Prefs.readLocalAllowlist(this)
        else Prefs.readAllowlist(Prefs.remote())

    private var documentIoBusy = false

    private fun writeAllowlistTo(uri: Uri) {
        if (documentIoBusy) return

        // ACTION_CREATE_DOCUMENT returns an externally supplied URI. Require a
        // content URI and an explicit write grant before resolving it. This
        // prevents an arbitrary caller/provider URI from being used as a
        // ContentResolver target.
        if (!isGrantedContentUri(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
            showMessage(R.string.allowlist_export_failed)
            return
        }
        // The authority decides which provider answers; the *path* is what the
        // provider itself interprets, and a provider is free to mean
        // "/data/data/<app>/..." by it. Resolving such a URI is what turns a
        // document picker into a way to overwrite this app's own private files.
        // Normalising first is what makes the prefix test mean anything:
        // "/safe/../../data/data/<app>/x" names the /data path it normalises to.
        if (!isSafeDocumentPath(uri)) {
            showMessage(R.string.allowlist_export_failed)
            return
        }

        documentIoBusy = true
        uiScope.launch {
            try {
                val count = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val sorted = currentAllowlist().sorted()
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(sorted.joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8))
                    } ?: throw IOException("null stream")
                    sorted.size
                }
                showMessage(getString(R.string.allowlist_export_done, count))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { showMessage(R.string.allowlist_export_failed) }
            finally { documentIoBusy = false }
        }
    }

    private fun readAllowlistFrom(uri: Uri) {
        if (documentIoBusy) return

        // Same checks as the export side: the provider interprets the path, so
        // it is normalised and the private roots are refused before a stream is
        // opened — otherwise a provider can answer with this app's own files.
        if (!isGrantedContentUri(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
            showMessage(R.string.allowlist_import_failed)
            return
        }
        if (!isSafeDocumentPath(uri)) {
            showMessage(R.string.allowlist_import_failed)
            return
        }

        documentIoBusy = true
        uiScope.launch {
            try {
                val allow = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { AllowlistDocument.read(it) }
                        ?: throw IOException("null stream")
                }
                if (allow.isEmpty()) showMessage(R.string.allowlist_import_empty)
                else {
                    Prefs.writeAllowlist(this@AboutActivity, Prefs.remote(), allow)
                    showMessage(getString(R.string.allowlist_import_done, allow.size))
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { showMessage(R.string.allowlist_import_failed) }
            finally { documentIoBusy = false }
        }
    }

    /**
     * Refuses paths that escape upwards or name a root we must never write to.
     * A leftover ".." means the path still escapes: `normalize()` keeps it when
     * there is nothing above it to collapse into.
     */
    private fun isSafeDocumentPath(uri: Uri): Boolean {
        val path = uri.path ?: return false
        val normalized = FileSystems.getDefault().getPath(path).normalize()
        if (normalized.startsWith("/data")) {
            return false
        }
        val resolved = normalized.toString()
        return !resolved.contains("..") &&
            !resolved.startsWith("/system") &&
            !resolved.startsWith("/vendor") &&
            !resolved.startsWith("/proc") &&
            !resolved.startsWith("/dev")
    }

    /**
     * Accept only content:// URIs for which this process currently has the
     * requested explicit URI permission. The permission check is performed
     * against this process UID/PID, so an arbitrary URI supplied by another
     * component cannot be resolved unless Android has actually granted access.
     */
    private fun isGrantedContentUri(uri: Uri?, grantFlag: Int): Boolean {
        if (uri == null || uri.scheme != "content" ||
            uri.authority.isNullOrEmpty()
        ) {
            return false
        }
        return checkUriPermission(uri, Process.myPid(), Process.myUid(), grantFlag) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * One line of feedback, in the M3 way: drawn by the page it belongs to, in
     * that page's colours, rather than floating over it in the system's.
     */
    private fun showMessage(resId: Int) {
        showMessage(getString(resId))
    }

    private fun showMessage(text: String) {
        uiScope.launch {
            // A second message replaces the first instead of queueing behind it.
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    override fun onDestroy() {
        ModuleServiceConnection.unsubscribe(serviceListener)
        // A message still waiting its turn must not outlive the window it was
        // going to be drawn in.
        uiScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AboutActivity"

        private const val REPO_URL = "https://github.com/iamqwert/HyperOS_FCM_Live"

        /**
         * Online help, opened in the browser from the top-bar help icon and
         * from the "help" launcher shortcut (see [ShortcutPublisher]).
         *
         * The in-app help page was removed on purpose: its text had to be kept
         * in sync with `Hooker` by hand, which drifted more than once. Keeping
         * a single copy in the repository means a wording fix ships without an
         * APK release. Point this at a URL that renders Markdown.
         */
        const val HELP_URL = "https://github.com/iamqwert/HyperOS_FCM_Live/blob/main/HELP.md"
    }
}
