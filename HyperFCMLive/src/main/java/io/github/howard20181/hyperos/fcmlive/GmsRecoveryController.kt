package io.github.howard20181.hyperos.fcmlive

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 所有自动恢复由系统宿主的单一后台线程处理，不在 Hook 回调内做网络读取或发广播。 */
internal class GmsRecoveryController(
    private val context: () -> Context?,
    private val handler: Handler,
    private val ownerActive: () -> Boolean,
    private val logger: (Int, String) -> Unit
) {
    enum class Trigger { STARTUP, PERIODIC, NETWORK, POLICY_REPAIRED, SLEEP_EXIT }

    private val epoch = RecoveryEpoch()
    private val closed = AtomicBoolean(false)
    private val registration = AtomicReference<Pair<ConnectivityManager, ConnectivityManager.NetworkCallback>?>(null)
    private val policies = mutableMapOf<Int, GmsRecoveryPolicy>()
    private val lastStatus = mutableMapOf<Int, String>()
    private var compatibilityPending = false
    private var newSequencePending = false
    private var networkGraceUntil = 0L
    private val schedule = RecoverySchedule()
    private var callbackNetwork: Network? = null
    private var callbackCapabilities: String? = null
    private val check = Runnable { runCheck() }

    private fun active() = !closed.get() && ownerActive() && epoch.active()

    fun start() {
        handler.post {
            if (!active()) return@post
            requestOnHandler(Trigger.STARTUP)
        }
    }

    fun request(trigger: Trigger) {
        if (active()) handler.post { if (active()) requestOnHandler(trigger) }
    }

    private fun requestOnHandler(trigger: Trigger) {
        if (registration.get() == null) registerNetworkCallback()
        newSequencePending = true
        if (trigger == Trigger.POLICY_REPAIRED || trigger == Trigger.SLEEP_EXIT) compatibilityPending = true
        val settling = trigger == Trigger.NETWORK || trigger == Trigger.STARTUP
        if (settling) networkGraceUntil = SystemClock.elapsedRealtime() + GmsRecoveryPolicy.CONFIRM_DELAY_MS
        enqueue(if (settling) GmsRecoveryPolicy.CONFIRM_DELAY_MS else 0L, replace = settling)
    }

    private fun enqueue(delayMs: Long, replace: Boolean = false) {
        if (!active()) return
        val now = SystemClock.elapsedRealtime()
        if (!schedule.request(now, delayMs, networkGraceUntil, replace)) return
        handler.removeCallbacks(check)
        if (!handler.postDelayed(check, (schedule.dueAt!! - now).coerceAtLeast(0L))) schedule.clear()
    }

    private fun runCheck() {
        schedule.clear()
        if (!active()) return
        val compatibility = compatibilityPending
        val newSequence = newSequencePending
        compatibilityPending = false
        newSequencePending = false
        val ctx = context()
        if (ctx == null) {
            status(-1, "observation-unavailable")
            return
        }
        try {
            val reader = GmsRecoveryReader(ctx)
            val snapshot = reader.read()
            if (!active()) return
            if (snapshot.observations.isEmpty()) status(-1, "identity-unavailable")
            var nextDelay: Long? = null
            fun next(delay: Long) { nextDelay = minOf(nextDelay ?: Long.MAX_VALUE, delay) }
            for (sample in snapshot.observations) {
                if (!active()) return
                val now = SystemClock.elapsedRealtime()
                val policy = policies.getOrPut(sample.uid) { GmsRecoveryPolicy() }
                // PowerKeeper 的名单和旧睡眠链来自主用户；不得替分身制造兼容恢复请求。
                when (val decision = policy.observe(sample, now, epoch.lastRequest(sample.uid),
                    compatibilityTrigger = compatibility && sample.uid / 100000 == 0, newSequence = newSequence)) {
                    is GmsRecoveryPolicy.Decision.Stop -> status(sample.uid, decision.reason)
                    is GmsRecoveryPolicy.Decision.Check -> next(decision.delayMs)
                    is GmsRecoveryPolicy.Decision.Request -> {
                        val currentNetwork = reader.network()
                        if (currentNetwork.state == RecoveryNetworkState.OFFLINE || currentNetwork.key != snapshot.network.key ||
                            (!decision.compatibility && currentNetwork.state != RecoveryNetworkState.READY)) {
                            status(sample.uid, "network-changed")
                            continue
                        }
                        if (!active() || !epoch.claim(sample.uid, now)) continue
                        if (!active()) return
                        policy.attempted(now, decision.compatibility)
                        val sent = runCatching { reader.requestReconnect(sample.uid) }.getOrDefault(false)
                        if (active()) {
                            logger(if (sent) Log.INFO else Log.WARN,
                                "recovery: ${if (sent) "request-sent" else "request-failed"} uid=${sample.uid} mode=${if (decision.compatibility) "policy-event" else "confirmed-absence"}")
                            lastStatus.remove(sample.uid)
                            next(GmsRecoveryPolicy.CONFIRM_DELAY_MS)
                        }
                    }
                }
            }
            if (policies.size > 64) {
                val visible = snapshot.observations.map { it.uid }.toSet()
                policies.keys.retainAll(visible)
                lastStatus.keys.retainAll(visible + -1)
            }
            nextDelay?.let { enqueue(it) }
        } catch (e: Exception) {
            status(-1, "check-failed-${e.javaClass.simpleName}")
        }
    }

    private fun status(uid: Int, value: String) {
        if (!active() || lastStatus[uid] == value) return
        lastStatus[uid] = value
        logger(Log.INFO, "recovery: $value uid=$uid")
    }

    // Lint 无法推断 system_server 宿主的共享权限，已在运行时显式检查并降级为不可用。
    @SuppressLint("MissingPermission")
    private fun registerNetworkCallback() {
        try {
            val ctx = context() ?: return
            if (ctx.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
                status(-1, "network-callback-unavailable")
                return
            }
            val manager = ctx.getSystemService(ConnectivityManager::class.java) ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!active()) return
                    if (callbackNetwork == network) return
                    callbackNetwork = network
                    callbackCapabilities = null
                    requestOnHandler(Trigger.NETWORK)
                }
                override fun onLost(network: Network) {
                    if (!active() || callbackNetwork != network) return
                    callbackNetwork = null
                    callbackCapabilities = null
                    requestOnHandler(Trigger.NETWORK)
                }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (!active() || callbackNetwork != network) return
                    val state = listOf(NetworkCapabilities.NET_CAPABILITY_INTERNET,
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED, NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                        .joinToString(":") { capabilities.hasCapability(it).toString() }
                    if (state == callbackCapabilities) return
                    callbackCapabilities = state
                    requestOnHandler(Trigger.NETWORK)
                }
            }
            manager.registerDefaultNetworkCallback(callback, handler)
            registration.set(manager to callback)
            if (!active()) unregisterNetworkCallback()
        } catch (_: Exception) {
            status(-1, "network-callback-unavailable")
        }
    }

    private fun unregisterNetworkCallback() {
        registration.getAndSet(null)?.let { (manager, callback) -> runCatching { manager.unregisterNetworkCallback(callback) } }
    }

    fun close() {
        closed.set(true)
        epoch.retire()
        handler.removeCallbacks(check)
        unregisterNetworkCallback()
    }

    companion object {
        const val ACTION_CHECK = "io.github.howard20181.hyperos.fcmlive.CHECK_GMS_RECOVERY"
        const val POWERKEEPER = "com.miui.powerkeeper"
    }
}

/** 合并的是待执行工作，而不是累计不断增长的任务队列。 */
internal class RecoverySchedule {
    var dueAt: Long? = null
        private set

    fun request(now: Long, delay: Long, notBefore: Long, replace: Boolean = false): Boolean {
        val desired = maxOf(now + delay.coerceAtLeast(0L), notBefore)
        if (!replace && dueAt?.let { it <= desired } == true) return false
        dueAt = desired
        return true
    }

    fun clear() { dueAt = null }
}
