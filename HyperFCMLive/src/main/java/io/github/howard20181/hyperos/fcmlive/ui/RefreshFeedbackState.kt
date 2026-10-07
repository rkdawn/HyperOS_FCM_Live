package io.github.howard20181.hyperos.fcmlive.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 保留 Material3 的手势识别；同一个 offset 同时驱动跟手、停留和收回。
 * 不再在两个状态之间切换，也不在组合阶段读写每一帧的位移。
 */
internal class RefreshFeedbackState(
    private val now: () -> Long = { SystemClock.uptimeMillis() },
    private val waitFor: suspend (Long) -> Unit = { delay(it) }
) : PullToRefreshState {
    enum class Phase { IDLE, PULLING, REFRESHING, COMPLETING, RETURNING }
    var phase by mutableStateOf(Phase.IDLE)
        private set
    var generation by mutableStateOf(0)
        private set
    private val offset = Animatable(0f)
    private val completion = Animatable(0f)
    private val opacity = Animatable(1f)
    private var startedAt = 0L
    val busy get() = when (phase) {
        Phase.REFRESHING, Phase.COMPLETING, Phase.RETURNING -> true
        else -> false
    }
    val completionFraction get() = completion.value
    val lineAlpha get() = opacity.value
    override val distanceFraction get() = offset.value.coerceIn(0f, 2f)
    override val isAnimating get() = offset.isRunning || busy

    /** 在手动 onRefresh 回调中立即标记，避免框架松手回缩先把留白关掉。 */
    fun beginRefresh() {
        if (busy) return
        startedAt = now()
        phase = Phase.REFRESHING
        generation++
    }

    override suspend fun snapTo(targetValue: Float) {
        if (busy) return
        offset.snapTo(targetValue.coerceIn(0f, 2f))
        phase = if (targetValue > 0f) Phase.PULLING else Phase.IDLE
    }

    // 框架只负责手势。刷新后的位移由 runRefresh 独占，避免其异步通知
    // 与扫描完成的收尾动画同时写入 Animatable，互相取消。
    override suspend fun animateToThreshold() = Unit

    override suspend fun animateToHidden() {
        // 未达到刷新阈值时直接收回；真正的刷新由 runRefresh 串行收尾。
        if (busy) return
        offset.animateTo(0f, settle)
        phase = Phase.IDLE
    }

    suspend fun runRefresh(awaitComplete: suspend () -> Unit) {
        if (phase != Phase.REFRESHING) return
        try {
            offset.animateTo(1f, settle)
            awaitComplete()
            // 只延长视觉反馈。慢扫描等真实完成，快扫描至少展示 1 秒。
            waitFor((MINIMUM_FEEDBACK_MS - (now() - startedAt)).coerceAtLeast(0L))
            phase = Phase.COMPLETING
            completion.animateTo(1f, tween(240))
            opacity.animateTo(0f, tween(180))
            phase = Phase.RETURNING
            offset.animateTo(0f, settle)
        } finally {
            // 页面销毁等取消路径也复位，不能留下 busy 状态。
            withContext(NonCancellable) {
                offset.snapTo(0f)
                completion.snapTo(0f)
                opacity.snapTo(1f)
                phase = Phase.IDLE
            }
        }
    }

    companion object {
        const val MINIMUM_FEEDBACK_MS = 1000L
        private val settle = spring<Float>(dampingRatio = 1f, stiffness = 400f)
    }
}

/** 始终向下位移；外层裁剪保证刷新线不可能绘制到工具栏内。 */
internal fun refreshOffsetPx(fraction: Float, gapPx: Float): Float = fraction.coerceIn(0f, 2f) * gapPx
