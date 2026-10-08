package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * FCM wake allowlist, shared between the module's settings UI (app process) and
 * the Xposed hooks (system_server) via libxposed's cross-process remote
 * preferences (`XposedInterface.getRemotePreferences`).
 *
 * system_server cannot read the module's private files (SELinux MLS categories)
 * and querying an on-demand provider is unreliable, so we use the framework's
 * own cross-process prefs as the single source of truth. After writing, the app
 * broadcasts [ACTION_ALLOWLIST_CHANGED] so the system_server hook re-reads
 * its in-memory copy. A second action is sent only when the autostart write is
 * turned on — [ACTION_APPLY_AUTOSTART], which asks the hook to run that write
 * over the whole allowlist instead of waiting for each app's next push (see
 * [KEY_WAKE_WRITE_AUTOSTART]).
 *
 * A local private-prefs mirror is kept so the settings UI can sort allowlisted
 * apps to the top immediately on launch, before libxposed finishes binding.
 *
 * ## Master / sub switch rule (read before adding any new pair)
 *
 * A sub switch is rendered inside `AnimatedVisibility(visible = <master>)`, so
 * it is **invisible but not inert** when the master is off — the pref keeps its
 * last value and a hook that reads only the sub key would still act. Any new
 * master/sub pair therefore has to be checked in three places, not one:
 *
 *  1. every hook read site **ANDs the master flag** (including secondary gates
 *     shared by both branches) — see `isSleepKeepaliveDataEnabled()` in
 *     Hooker.kt;
 *  2. the UI keeps the sub switch inside the *same* `AnimatedVisibility` as the
 *     master;
 *  3. the key is added to `MainActivity.reloadAllowlist`'s pending-repair set,
 *     so a value the module never saw gets pushed again.
 *
 * A sub-option that is not a boolean follows the same three, with one
 * relaxation: `wifi_weak_signal_floor` is reached only inside the branch its
 * master already opened, so the AND is structural and needs no extra flag. It
 * still has to sit in the master's `AnimatedVisibility` and still has to be
 * sanitized — a value from a retired option list would otherwise read as a
 * depth nobody offers any more.
 *
 * The current pair is `wake_autostart_relaxed` ⊃ `wake_write_autostart`, and it
 * has a section of its own because it gates a different ROM mechanism from the
 * stopped-state switch above it (the MIUI autostart AppOp rather than the AOSP
 * stopped state); `wake_stopped_packages` used to have a
 * `wake_clear_stopped_state` sub-switch and is now a single switch — the
 * sub-switch reset the package's stopped state, which the master's flag already
 * opens, so it could never change the outcome of a broadcast it ran on. See the
 * ponytail in `hookActivityManagerService` for the bytecode behind that and for
 * the sample that would bring it back. `sleep_keepalive` and
 * `sleep_keepalive_data` used to be one of these and are now **peers**: each
 * radio in sleep mode's cutoff follows its own key, neither gates the other, and
 * the screen shows both rows — see
 * `isSleepKeepaliveDataEnabled()` in Hooker.kt for why rule 1 stopped applying
 * to that pair. A `…_charging_only` sibling
 * used to live beside them and was later removed — see
 * HOOKS_AND_DIAGNOSTICS.md §5.5 for why ("only while charging" narrowed the
 * master and could never widen it, and the premise it was justified by turned
 * out to be false). The WeChat pair was removed with the shield (see
 * HOOKS_AND_DIAGNOSTICS.md Appendix C).
 *
 * Rare paths need an explicit "applied / handed back to the ROM" log line:
 * **never infer that a hook worked from the absence of a log line.**
 */
object Prefs {
    const val MODULE_PKG = "io.github.howard20181.hyperos.fcmlive"
    /** Remote prefs group shared by the app process and system_server. */
    const val GROUP_CONFIG = "config"
    const val KEY_ALLOWLIST = "allowlist"
    /** Local mirror group (UI-only; remote remains source of truth for hooks). */
    const val LOCAL_PREFS = "fcmlive_allowlist_cache"
    /** UI-only: set while the mirror holds edits the module service never saw. */
    private const val KEY_PENDING_PUSH = "allowlist_pending_push"
    /** UI-only: overflow menu "Show FCM-supported apps". */
    const val KEY_SHOW_FCM_ONLY = "show_fcm_supported_only"
    /**
     * UI-only: overflow menu "Exclude MiPush apps". Kept next to
     * [KEY_SHOW_FCM_ONLY] because it is the same kind of setting — a
     * question about what the list offers, not about what the hooks do, so it
     * stays out of [GROUP_CONFIG] and needs no broadcast.
     */
    const val KEY_EXCLUDE_MIPUSH = "exclude_mipush_apps"
    /**
     * Remote + local: overflow menu "Strict mode". Unlike
     * [KEY_SHOW_FCM_ONLY] this one decides what the hooks do, so it sits
     * in [GROUP_CONFIG] next to the allowlist and is re-read by the same
     * broadcast; the local mirror only carries the answer before libxposed binds.
     */
    const val KEY_STRICT_MODE = "strict_mode"
    /** UI-only: set while the mirror holds a strict-mode change the module never saw. */
    private const val KEY_STRICT_PENDING_PUSH = "strict_mode_pending_push"
    /**
     * Remote + local: "keep WiFi up during sleep" experiment — the master
     * switch.
     *
     * On OS4/V816 the sleep mode does *not* filter per uid:
     * `PhoneSleepModeController#applySleepConfig` turns WiFi **and** mobile
     * data off outright, so no per-app whitelist can save the FCM channel —
     * measured 01:38:00→07:08:57 with no network at all.
     *
     * This switch stops the WiFi cutoff. Mobile data is a separate decision
     * ([KEY_SLEEP_KEEPALIVE_DATA]) and stays on the system's own policy by
     * default, because WiFi-only is the cheaper half: an unattended phone at
     * home is on WiFi anyway, and holding the cellular radio open is the part
     * that actually costs power. A night with no WiFi gets nothing out of
     * this switch — that is what the other switch is for.
     *
     * Default **off**, like every other experiment:
     * keeping a radio up all night defeats the power saving the user turned
     * sleep mode on for, and the effect is device-wide rather than scoped to
     * the apps the module watches. It is opt-in on the experiment screen,
     * where the cost is spelled out.
     */
    const val KEY_SLEEP_KEEPALIVE = "sleep_keepalive"
    /** UI-only: set while the mirror holds a keepalive change the module never saw. */
    private const val KEY_SLEEP_KEEPALIVE_PENDING_PUSH = "sleep_keepalive_pending_push"

    /**
     * Remote + local: "keep mobile data up during sleep".
     *
     * A **peer** of [KEY_SLEEP_KEEPALIVE], not its sub-switch. Each of the two
     * radios in sleep mode's cutoff follows its own key and this one is read on
     * its own, so turning the WiFi switch off no longer silences it. With both
     * on the pair behaves like a single "keep the whole network up" switch —
     * both radios survive the night, at a higher cost — but either half can
     * also be had without the other.
     *
     * Default **off**, same reasoning as the WiFi switch — ask before
     * holding a radio open overnight.
     */
    const val KEY_SLEEP_KEEPALIVE_DATA = "sleep_keepalive_data"
    /** UI-only: set while the mirror holds a data-keepalive change the module never saw. */
    private const val KEY_SLEEP_KEEPALIVE_DATA_PENDING_PUSH = "sleep_keepalive_data_pending_push"

    /**
     * Remote + local: "WeChat doze keepout" experiment.
     *
     * The PowerKeeper process hardcodes WeChat into its domestic
     * always-white set (`DeviceIdleController$1`) and re-adds it to the AOSP
     * battery-optimization whitelist on every power-mode change — through the
     * single funnel `CommonAdapter.addPowerSaveWhitelistApps`, which is also
     * what persists /data/system/deviceidle.xml. This switch drops WeChat
     * from that call's argument list.
     *
     * Default **off**, like every other experiment: it overwrites where the
     * system puts WeChat, the effect outlives the process (the entry is not
     * written back until the switch is turned off), and the whitelist already
     * holds WeChat today — the hook prevents the next write, it does not
     * clear the stored one.
     */
    const val KEY_WECHAT_DOZE_KEEPOUT = "wechat_doze_keepout"
    /** UI-only: set while the mirror holds a keepout change the module never saw. */
    private const val KEY_WECHAT_DOZE_KEEPOUT_PENDING_PUSH = "wechat_doze_keepout_pending_push"

    /**
     * Remote + local: "relaxed WiFi weak-signal switch" experiment.
     *
     * Background, measured on-device (HyperOS V816, see Hooks doc §5.9.2):
     * `AmlMiuiThirdPartScorer` turns `mLegacyIntScore` into a usable/unusable
     * verdict at a hardcoded threshold of 50, and reports it outward once per
     * update through `notifyScoreAndIsUsable()`. A score below 50 makes
     * `WifiScoreReport` mark the network `+EXITING`, and ConnectivityService
     * then moves the default network to cellular for 30 s. The hook clamps the
     * score to 50 for the duration of that one call, so the weak-signal verdict
     * is never published.
     *
     * Scope of the change, which is why it belongs in this file's experiment
     * set rather than near the FCM allowlist:
     * - the clamp lives in the arguments of a single in-flight call; nothing is
     *   written to disk, to Settings, or anywhere else upstream, so turning the
     *   switch off — or uninstalling — leaves no residue;
     * - the real score is restored on the way out, so mechanism that does not
     *   go through the scorer (carrier/UI decisions, WiFi actually leaving) is
     *   untouched.
     *
     * Default **off**, like every other experiment: it deliberately keeps the
     * device on a WiFi link the ROM judged too weak, which is a quality-of-
     * service trade, not a repair.
     */
    const val KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED = "wifi_weak_signal_switch_relaxed"
    /** UI-only: set while the mirror holds a relaxed-switch change the module never saw. */
    private const val KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED_PENDING_PUSH =
        "wifi_weak_signal_switch_relaxed_pending_push"

    /**
     * Remote + local: how weak a WiFi link may get before this switch stops
     * covering it — the sub-option of [KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED].
     *
     * The master switch on its own rescues *every* score below the ROM's floor,
     * however far below: a link scored 33 gets exactly the treatment one scored
     * 49 does. Only consulted while the master is on, so it can narrow but
     * never widen the master, and it needs no other gate — the hook reaches it
     * only inside the branch the master already opened.
     *
     * Below the chosen value the score is handed to the ROM untouched, so the
     * network moves to cellular exactly as it would without the module. That is
     * the trade being offered: the switch exists to stop needless switching,
     * and there is a depth past which staying is worse than the switch it was
     * trying to avoid. Nothing is gained by pretending otherwise, and the
     * description on screen says so.
     *
     * The four values step by five. The ROM's own floor is 50; around 45 is the
     * marginal band that produced the recovery decisions in the V816 samples —
     * one gaming window (1409 samples) reached 33 with only four samples below
     * 35, and another (1245 samples) never went below 44 — so 30 is offered
     * rather than proved, as the far end of a scale the middle of which is
     * where the measurement actually sits.
     *
     * Default **45**: the narrowest rescue, so switching the master on changes
     * the least. Anything deeper is a deliberate widening, not a default.
     */
    const val KEY_WIFI_WEAK_SIGNAL_FLOOR = "wifi_weak_signal_floor"
    /** UI-only: set while the mirror holds a floor change the module never saw. */
    private const val KEY_WIFI_WEAK_SIGNAL_FLOOR_PENDING_PUSH =
        "wifi_weak_signal_floor_pending_push"
    /** Offered floors, narrowest first. */
    val WIFI_WEAK_SIGNAL_FLOORS: IntArray = intArrayOf(45, 40, 35, 30)
    /** Default floor: see [KEY_WIFI_WEAK_SIGNAL_FLOOR]. */
    const val WIFI_WEAK_SIGNAL_FLOOR_DEFAULT = 45

    /**
     * Remote + local: "wake stopped packages" experiment — the master switch.
     *
     * Since Android 3.1 a package in the stopped state (never launched,
     * force-stopped, or stopped by a freeze tool) receives no manifest
     * broadcast: `broadcastIntentLocked` stamps every intent with
     * `FLAG_EXCLUDE_STOPPED_PACKAGES` unless the caller is privileged, and the
     * delivery filter skips stopped receivers. The module already clears that
     * one hurdle for the GMS→c2dm hop (see Hooker's ActivityManagerService
     * hook); this switch drops it for broadcasts to a checked package from
     * *any* caller, which is what "唤醒" means here — the flag is added and
     * nothing else about the broadcast changes.
     *
     * The list gate is `Hooker#wakeExplicitlyAllows`, so membership is required
     * and GMS is not exempt: an empty list means "no app", which is what the
     * screen says. The hook sits on the AMS *entry* point
     * (`broadcastIntentWithFeature`), not on `broadcastIntentLocked`, so it
     * covers the broadcasts apps send; the ones the system delivers on its own
     * (alarms, notification actions, anything through a `PendingIntent`) never
     * pass through it, and the description says so.
     *
     * Two things it does not do, both of which matter on screen: it does not
     * start or keep a process alive, and it does not change what the ROM does
     * with the broadcast once it is allowed in. An app that has been frozen out
     * of running in the background still needs the rest of the module for that.
     *
     * Default **off**, like every other experiment: stopped-state delivery is a
     * protection the user or a freeze tool asked for, and the flag is added
     * before the ROM's own delivery decision rather than after it.
     */
    /**
     * 已删除的实验开关（键名保留用于清理旧镜像数据，不再读写）：
     * - `wake_stopped_packages`（上游 3.7.0 删除）：真实 FCM 广播 caller 恒为
     *   GMS，固定的 GMS→c2dm 跳线已覆盖；本开关补加 FLAG 的分支覆盖不到任何
     *   额外广播。
     * - `wake_autostart_relaxed`（上游 3.7.0 删除）：同因。其唯一存活价值
     *   （持久写盘）已并入 [KEY_WAKE_WRITE_AUTOSTART]。
     */
    private const val KEY_WAKE_STOPPED_PACKAGES = "wake_stopped_packages"
    /** UI-only: set while the mirror holds a wake change the module never saw. */
    private const val KEY_WAKE_STOPPED_PACKAGES_PENDING_PUSH =
        "wake_stopped_packages_pending_push"

    /** 同上，已删除的实验开关键名；仅存在于旧镜像数据。 */
    private const val KEY_WAKE_AUTOSTART_RELAXED = "wake_autostart_relaxed"
    private const val KEY_WAKE_AUTOSTART_RELAXED_PENDING_PUSH =
        "wake_autostart_relaxed_pending_push"

    /**
     * Remote + local: "write the autostart permission" switch（独立开关，上游
     * 3.7.0 起不再是 relaxed 的子项）。
     *
     * 对显式勾选的应用，把 MIUI 自启动行为位（`10008`）和开关位（`10053`，
     * 手机管家应用管理页显示的那个）都写为 `MODE_ALLOWED` —— 与
     * `android.miui.AppOpsUtils#setApplicationAutoStart(ctx, pkg, true)` 等效。
     * 旧版本只写 10008：应用实际已被允许自启动，但管家页面仍显示关闭，
     * 造成"没生效"的错觉（上游 3.7.0 变更说明）。
     *
     * The write happens on two occasions. Turning the switch **on** applies it
     * to the whole allowlist at once — the app broadcasts
     * [ACTION_APPLY_AUTOSTART] and the module walks the list — so the row takes
     * effect on the spot and the result can be checked from a shell straight
     * away. Afterwards it is *lazy*: each qualifying push (the c2dm / Firebase
     * action family) rewrites the op for its own package, which is what
     * corrects a package some other tool has reset. That second half is why a
     * package is not fixed the instant it drifts — it waits for its next push.
     *
     * This is the one experiment that edits a user-visible system setting, and
     * the earlier "bypass the gate, never write the op" line is relaxed for it
     * on purpose: it is off by default and only reachable from the experiment
     * screen, so the write is a choice the user made. It is not reverted when
     * the switch is turned off — nothing in the module knows what the value was
     * before.
     *
     * Default **off**, same reasoning as every other experiment.
     */
    const val KEY_WAKE_WRITE_AUTOSTART = "wake_write_autostart"
    /** UI-only: set while the mirror holds an autostart-write change the module never saw. */
    private const val KEY_WAKE_WRITE_AUTOSTART_PENDING_PUSH =
        "wake_write_autostart_pending_push"

    /** Action the app broadcasts after writing, to refresh system_server. */
    const val ACTION_ALLOWLIST_CHANGED = MODULE_PKG + ".ALLOWLIST_CHANGED"

    /**
     * Action that asks the system_server hook to run the autostart write over
     * the whole allowlist right now, instead of waiting for each package to be
     * handed a push first.
     *
     * Deliberately not folded into [ACTION_ALLOWLIST_CHANGED]: that one is a
     * "re-read your copy" hint sent after *every* settings write, and it is
     * throttled on the receiving side. This one asks for a one-off piece of
     * work, and losing it costs nothing beyond the lazy path it was meant to
     * short-circuit.
     */
    const val ACTION_APPLY_AUTOSTART = MODULE_PKG + ".APPLY_AUTOSTART"

    /**
     * Remote prefs handle published by the settings UI once libxposed binds, so
     * other screens (e.g. About) can read/write the allowlist without binding a
     * second service listener. Null when the module service is not bound.
     */
    @Volatile
    private var sRemotePrefs: SharedPreferences? = null

    @JvmStatic
    fun setRemote(remotePrefs: SharedPreferences?) {
        sRemotePrefs = remotePrefs
    }

    @JvmStatic
    fun remote(): SharedPreferences? = sRemotePrefs

    /** 所有入口共用的待同步补交；只有成功提交的最新值才会清除标记。 */
    internal fun syncPending(context: Context, remote: SharedPreferences?) {
        if (remote == null) return
        if (hasPendingPush(context)) writeAllowlist(context, remote, readLocalAllowlist(context))
        if (hasPendingStrictPush(context)) writeStrictMode(context, remote, readLocalStrictMode(context))
        if (hasPendingWechatDozeKeepoutPush(context)) writeWechatDozeKeepout(context, remote, readLocalWechatDozeKeepout(context))
        if (hasPendingWifiWeakSignalSwitchRelaxedPush(context)) writeWifiWeakSignalSwitchRelaxed(context, remote, readLocalWifiWeakSignalSwitchRelaxed(context))
        if (hasPendingWifiWeakSignalFloorPush(context)) writeWifiWeakSignalFloor(context, remote, readLocalWifiWeakSignalFloor(context))
        if (hasPendingSleepKeepalivePush(context)) writeSleepKeepalive(context, remote, readLocalSleepKeepalive(context))
        if (hasPendingSleepKeepaliveDataPush(context)) writeSleepKeepaliveData(context, remote, readLocalSleepKeepaliveData(context))
        // wake_stopped_packages / wake_autostart_relaxed 已删除（上游 3.7.0）：
        // 不再补交；旧 pending 标记随本地镜像数据一并失效。
        if (hasPendingWakeWriteAutostartPush(context)) writeWakeWriteAutostart(context, remote, readLocalWakeWriteAutostart(context))
    }

    /** Package names the user allows FCM to wake / auto-launch. */
    @JvmStatic
    fun readAllowlist(remotePrefs: SharedPreferences?): MutableSet<String> {
        return readSet(remotePrefs)
    }

    @JvmStatic
    fun readLocalAllowlist(context: Context): MutableSet<String> {
        return readSet(localPrefs(context))
    }

    /** Copied defensively: callers mutate the result, and the stored set is shared. */
    private fun readSet(prefs: SharedPreferences?): MutableSet<String> {
        if (prefs == null) {
            return HashSet()
        }
        val set = prefs.getStringSet(KEY_ALLOWLIST, Collections.emptySet())
        return if (set != null) HashSet(set) else HashSet()
    }

    @JvmStatic
    fun writeLocalAllowlist(context: Context, allowlist: Set<String>) {
        localPrefs(context).edit().putStringSet(KEY_ALLOWLIST, HashSet(allowlist)).apply()
    }

    /**
     * Whether the local mirror holds a change the module service never received,
     * because the service was not bound when the user made it. The next bind then
     * pushes the mirror up instead of adopting the (older) remote set, which is
     * what used to silently revert such a change.
     */
    @JvmStatic
    fun hasPendingPush(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_PENDING_PUSH, false)
    }

    private fun markPendingPush(context: Context) {
        localPrefs(context).edit().putBoolean(KEY_PENDING_PUSH, true).apply()
    }

    private fun clearPendingPush(context: Context) {
        localPrefs(context).edit().putBoolean(KEY_PENDING_PUSH, false).apply()
    }

    /** Strict mode as the UI last left it; the mirror is what the settings screen shows. */
    @JvmStatic
    fun readLocalStrictMode(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_STRICT_MODE, false)
    }

    /** Strict-mode counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingStrictPush(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_STRICT_PENDING_PUSH, false)
    }

    /** Sleep-keepalive value as the UI last left it; the mirror is what the experiment screen shows. */
    @JvmStatic
    fun readLocalSleepKeepalive(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_SLEEP_KEEPALIVE, false)
    }

    /** Sleep-keepalive counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingSleepKeepalivePush(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_SLEEP_KEEPALIVE_PENDING_PUSH, false)
    }

    /**
     * Write the sleep-keepalive flag and make it live.
     *
     * Same shape as [writeStrictMode]. The hook lives in the PowerKeeper
     * process and reads the remote value lazily at each qualifying call, so
     * flipping this takes effect on the next sleep entry without a reboot.
     */
    @JvmStatic
    fun writeSleepKeepalive(
        context: Context,
        remotePrefs: SharedPreferences?,
        enabled: Boolean
    ) {
        writeConfig(context, remotePrefs, KEY_SLEEP_KEEPALIVE, KEY_SLEEP_KEEPALIVE_PENDING_PUSH, enabled)
    }

    /** Sleep-keepalive data sub-switch value as the UI last left it. */
    @JvmStatic
    fun readLocalSleepKeepaliveData(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_SLEEP_KEEPALIVE_DATA, false)
    }

    /** Sleep-keepalive data counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingSleepKeepaliveDataPush(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_SLEEP_KEEPALIVE_DATA_PENDING_PUSH, false)
    }

    /**
     * Write the sleep-keepalive data sub-switch and make it live.
     *
     * Same shape as [writeSleepKeepalive]. The hook in the PowerKeeper process
     * reads the remote value lazily at each qualifying call, so flipping this
     * takes effect on the next sleep entry without a reboot.
     */
    @JvmStatic
    fun writeSleepKeepaliveData(
        context: Context,
        remotePrefs: SharedPreferences?,
        enabled: Boolean
    ) {
        writeConfig(context, remotePrefs, KEY_SLEEP_KEEPALIVE_DATA, KEY_SLEEP_KEEPALIVE_DATA_PENDING_PUSH, enabled)
    }

    /** WeChat-doze-keepout value as the UI last left it; the mirror is what the experiment screen shows. */
    @JvmStatic
    fun readLocalWechatDozeKeepout(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_WECHAT_DOZE_KEEPOUT, false)
    }

    /** WeChat-doze-keepout counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingWechatDozeKeepoutPush(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_WECHAT_DOZE_KEEPOUT_PENDING_PUSH, false)
    }

    /**
     * Write the WeChat-doze-keepout flag and make it live.
     *
     * Same shape as [writeStrictMode]. The hook lives in the PowerKeeper
     * process and reads the remote value lazily at each qualifying call, so
     * flipping this takes effect on the next whitelist write without a reboot.
     */
    @JvmStatic
    fun writeWechatDozeKeepout(
        context: Context,
        remotePrefs: SharedPreferences?,
        enabled: Boolean
    ) {
        writeConfig(context, remotePrefs, KEY_WECHAT_DOZE_KEEPOUT, KEY_WECHAT_DOZE_KEEPOUT_PENDING_PUSH, enabled)
    }

    /** Relaxed WiFi weak-signal switch value as the UI last left it. */
    @JvmStatic
    fun readLocalWifiWeakSignalSwitchRelaxed(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED, false)
    }

    /** Relaxed WiFi weak-signal switch counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingWifiWeakSignalSwitchRelaxedPush(context: Context): Boolean {
        return localPrefs(context)
            .getBoolean(KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED_PENDING_PUSH, false)
    }

    /**
     * Write the relaxed WiFi weak-signal flag and make it live.
     *
     * Same shape as [writeWechatDozeKeepout]. The hook lives in system_server
     * and reads the remote value lazily at each qualifying call, so flipping
     * this takes effect on the next score update without a reboot.
     */
    @JvmStatic
    fun writeWifiWeakSignalSwitchRelaxed(
        context: Context,
        remotePrefs: SharedPreferences?,
        enabled: Boolean
    ) {
        writeConfig(context, remotePrefs, KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED, KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED_PENDING_PUSH, enabled)
    }

    /** True when [value] is one of the offered floors. */
    @JvmStatic
    fun isValidWeakSignalFloor(value: Int): Boolean {
        for (floor in WIFI_WEAK_SIGNAL_FLOORS) {
            if (floor == value) {
                return true
            }
        }
        return false
    }

    /**
     * Fall back to the default rather than acting on an unknown value: a floor
     * left over from a wider list of options would otherwise be read as a
     * depth nobody offers any more, and the two failures look alike.
     */
    @JvmStatic
    fun sanitizeWeakSignalFloor(value: Int): Int {
        return if (isValidWeakSignalFloor(value)) value else WIFI_WEAK_SIGNAL_FLOOR_DEFAULT
    }

    /** Weak-signal floor as the UI last left it, sanitized the same way the hook does. */
    @JvmStatic
    fun readLocalWifiWeakSignalFloor(context: Context): Int {
        return sanitizeWeakSignalFloor(
            localPrefs(context).getInt(KEY_WIFI_WEAK_SIGNAL_FLOOR, WIFI_WEAK_SIGNAL_FLOOR_DEFAULT)
        )
    }

    /** Weak-signal floor counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingWifiWeakSignalFloorPush(context: Context): Boolean {
        return localPrefs(context)
            .getBoolean(KEY_WIFI_WEAK_SIGNAL_FLOOR_PENDING_PUSH, false)
    }

    /**
     * Write the floor and make it live. Integer rather than boolean, otherwise
     * identical to [writeWifiWeakSignalSwitchRelaxed]; the hook reads it at the
     * same lazily-refreshed call, so a change lands on the next score update.
     */
    @JvmStatic
    fun writeWifiWeakSignalFloor(
        context: Context,
        remotePrefs: SharedPreferences?,
        floor: Int
    ) {
        val value = sanitizeWeakSignalFloor(floor)
        writeConfig(context, remotePrefs, KEY_WIFI_WEAK_SIGNAL_FLOOR, KEY_WIFI_WEAK_SIGNAL_FLOOR_PENDING_PUSH, value)
    }

    /** Autostart-write switch value as the UI last left it. */
    @JvmStatic
    fun readLocalWakeWriteAutostart(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_WAKE_WRITE_AUTOSTART, false)
    }

    /** Autostart-write counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingWakeWriteAutostartPush(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_WAKE_WRITE_AUTOSTART_PENDING_PUSH, false)
    }

    /**
     * Write the autostart-write switch and make it live.
     *
     * Turning it on applies the write to the whole allowlist at once (via
     * [ACTION_APPLY_AUTOSTART]) so the row takes effect on the spot. Turning
     * it off deliberately leaves what was already written alone — nothing in
     * the module knows what the values were before; undo is 手动到手机管家
     * 拨动一次该应用的自启动开关.
     */
    @JvmStatic
    fun writeWakeWriteAutostart(
        context: Context,
        remotePrefs: SharedPreferences?,
        enabled: Boolean
    ) {
        writeConfig(context, remotePrefs, KEY_WAKE_WRITE_AUTOSTART, KEY_WAKE_WRITE_AUTOSTART_PENDING_PUSH, enabled) {
            if (enabled) broadcastApplyAutostart(appContext(context))
        }
    }

    /**
     * Write strict mode and make it live.
     *
     * Same shape as [writeAllowlist]: the remote boolean is what the
     * hooks read, and [broadcastAllowlistChanged] is what makes them
     * re-read it — they load the whole [GROUP_CONFIG] group in one go, so
     * one broadcast refreshes the allowlist and this flag together. When the
     * module service is not bound yet the change stays in the mirror and is
     * flagged, so the next bind pushes it up instead of dropping it.
     */
    @JvmStatic
    fun writeStrictMode(
        context: Context,
        remotePrefs: SharedPreferences?,
        enabled: Boolean
    ) {
        writeConfig(context, remotePrefs, KEY_STRICT_MODE, KEY_STRICT_PENDING_PUSH, enabled)
    }

    private fun localPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
    }

    /** Application context where available: broadcasts must not outlive the caller. */
    private fun appContext(context: Context): Context {
        val app = context.applicationContext
        return app ?: context
    }

    /**
     * Serialises allowlist writes: one background thread, in order, so a burst of
     * taps cannot interleave and lose the last write.
     */
    private val WRITER: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        val thread = Thread(runnable, "fcmlive-allowlist-write")
        thread.isDaemon = true
        thread
    }

    /**
     * Write the allowlist and make it live.
     *
     * Remote prefs are what system_server's hooks read, so this write plus the
     * broadcast that follows it is what makes a change take effect — no refresh, no
     * restart. The write itself is a synchronous cross-process commit and therefore
     * runs on a background thread: on the calling (main) thread it could stall the
     * UI, and `apply()` is not an option here because the broadcast must not
     * outrun the value it announces. When [remotePrefs] is null (module
     * service not bound in this process) the change is kept in the local mirror and
     * flagged, so the next bind pushes it up rather than dropping it.
     */
    @JvmStatic
    fun writeAllowlist(
        context: Context,
        remotePrefs: SharedPreferences?,
        allowlist: Set<String>
    ) {
        writeConfig(context, remotePrefs, KEY_ALLOWLIST, KEY_PENDING_PUSH, allowlist)
    }

    /**
     * Ask system_server to re-read the shared config group. Sent three times
     * over ~1.5s because the receiver there is installed by a retry loop shortly
     * after boot (`Hooker.installAllowlistReceiverAsync`): a change made
     * in that window would otherwise be dropped and appear to need a refresh.
     *
     * Despite the name it is not allowlist-only: the receiver reloads
     * [GROUP_CONFIG] wholesale, so this also carries a strict-mode change
     * (see [writeStrictMode]) — which is why the two share one action.
     */
    private val CONFIG_WRITER by lazy { ConfigWriteQueue(WRITER) }

    private fun writeConfig(context: Context, remote: SharedPreferences?, key: String,
        pendingKey: String, value: Any, onCommitted: () -> Unit = {}) {
        val app = appContext(context)
        CONFIG_WRITER.write(localPrefs(app), remote, key, pendingKey, value) {
            broadcastAllowlistChanged(app)
            onCommitted()
        }
    }

    private fun sendConfigBroadcast(context: Context, action: String) {
        // 接收端按系统附带的 UID 验证，缺失身份不再放行。
        val options = android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true)
        context.sendBroadcast(Intent(action).setPackage("android"), null, options.toBundle())
    }

    @JvmStatic
    fun broadcastAllowlistChanged(context: Context) {
        val app = appContext(context)
        sendConfigBroadcast(app, ACTION_ALLOWLIST_CHANGED)
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({ sendConfigBroadcast(app, ACTION_ALLOWLIST_CHANGED) }, 400L)
        handler.postDelayed({ sendConfigBroadcast(app, ACTION_ALLOWLIST_CHANGED) }, 1500L)
    }

    /**
     * Ask system_server to apply the autostart write to every allowlisted
     * package now — see [ACTION_APPLY_AUTOSTART].
     *
     * Sent once rather than repeated like [broadcastAllowlistChanged]: the
     * receiving side re-reads the allowlist itself before walking it, so a miss
     * here is covered by the three reload broadcasts, and a duplicate would
     * only re-walk a list that is already written.
     */
    @JvmStatic
    fun broadcastApplyAutostart(context: Context) {
        sendConfigBroadcast(appContext(context), ACTION_APPLY_AUTOSTART)
    }
}
