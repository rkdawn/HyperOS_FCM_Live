package io.github.howard20181.hyperos.fcmlive

/** 这里只判断 TCP 观察结果，不判断 MCS 登录或消息送达。 */
internal enum class GmsConnectionState { PRESENT, ABSENT, UNKNOWN }
internal enum class RecoveryNetworkState { READY, OFFLINE, UNKNOWN }

internal data class RecoveryObservation(
    val uid: Int,
    val networkKey: String?,
    val network: RecoveryNetworkState,
    val connection: GmsConnectionState,
    val eligible: Boolean = true
)

/** 每个 UID 独立使用；由控制器的单一线程调用，时钟必须单调。 */
internal class GmsRecoveryPolicy {
    sealed interface Decision {
        data class Stop(val reason: String) : Decision
        data class Check(val delayMs: Long) : Decision
        data class Request(val compatibility: Boolean) : Decision
    }

    private var networkKey: String? = null
    private var initialized = false
    private var lastObservation: Long? = null
    private var missingSince: Long? = null
    private var attempts = 0
    private var attemptedAt: Long? = null
    private var compatibilityOnly = false

    fun observe(
        sample: RecoveryObservation,
        now: Long,
        lastGlobalRequest: Long?,
        compatibilityTrigger: Boolean = false,
        newSequence: Boolean = false
    ): Decision {
        if (!sample.eligible || !DiagnosticsParser.isAppUid(sample.uid)) {
            missingSince = null
            return Decision.Stop("ineligible")
        }
        if (!initialized || (sample.networkKey != null && networkKey != sample.networkKey)) {
            initialized = true
            networkKey = sample.networkKey
            missingSince = null
            attempts = 0
            attemptedAt = null
            compatibilityOnly = false
        }
        val previous = lastObservation
        if (previous != null && (now < previous || now - previous > MAX_SAMPLE_GAP_MS)) missingSince = null
        lastObservation = now
        if (sample.connection == GmsConnectionState.PRESENT) {
            missingSince = null
            attempts = 0
            attemptedAt = null
            compatibilityOnly = false
            return Decision.Stop("connection-present")
        }
        if (sample.network == RecoveryNetworkState.OFFLINE) {
            missingSince = null
            return Decision.Stop("network-offline")
        }
        val cooldown = lastGlobalRequest?.let { (MIN_REQUEST_GAP_MS - (now - it)).coerceAtLeast(0L) } ?: 0L
        // 兼容恢复只能来自已核实的策略事件；读不到连接本身绝不会制造这个事件。
        if (compatibilityTrigger && attempts < MAX_ATTEMPTS) {
            missingSince = null
            if (cooldown > 0) return Decision.Stop("compatibility-throttled")
            return Decision.Request(compatibility = true)
        }
        if (sample.network != RecoveryNetworkState.READY || sample.connection == GmsConnectionState.UNKNOWN) {
            missingSince = null
            return Decision.Stop("observation-unavailable")
        }
        if (compatibilityOnly && !newSequence) return Decision.Stop("compatibility-finished")
        if (newSequence) compatibilityOnly = false
        if (attempts >= MAX_ATTEMPTS) return Decision.Stop("retry-limit")
        val missing = missingSince
        if (missing == null) {
            missingSince = now
            return Decision.Check(CONFIRM_DELAY_MS)
        }
        val confirmWait = (CONFIRM_DELAY_MS - (now - missing)).coerceAtLeast(0L)
        val retryWait = attemptedAt?.let {
            val delay = if (attempts == 1) FIRST_RETRY_MS else SECOND_RETRY_MS
            (delay - (now - it)).coerceAtLeast(0L)
        } ?: 0L
        val wait = maxOf(confirmWait, retryWait, cooldown)
        return if (wait > 0) Decision.Check(wait) else Decision.Request(compatibility = false)
    }

    /** 发送失败也计入尝试，避免权限错误或不可达网络导致请求风暴。 */
    fun attempted(now: Long, compatibility: Boolean) {
        attempts++
        attemptedAt = now
        missingSince = null
        compatibilityOnly = compatibility
    }

    companion object {
        const val CONFIRM_DELAY_MS = 30_000L
        const val MIN_REQUEST_GAP_MS = 60_000L
        const val FIRST_RETRY_MS = 120_000L
        const val SECOND_RETRY_MS = 300_000L
        const val MAX_SAMPLE_GAP_MS = 10 * 60_000L
        const val MAX_ATTEMPTS = 3
    }
}
