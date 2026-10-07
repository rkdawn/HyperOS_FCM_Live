package io.github.howard20181.hyperos.fcmlive.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** 自定义留白与细线，不改原有刷新回调；动画始终限制在工具栏下方。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RefreshContent(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    padding: PaddingValues,
    content: @Composable (Modifier) -> Unit
) {
    val state = remember { RefreshFeedbackState() }
    val currentRefreshing = rememberUpdatedState(refreshing)
    LaunchedEffect(refreshing) {
        if (refreshing) state.beginRefresh()
    }
    // 每个手势一条串行动画；同帧完成的扫描也有代次，不依赖观察到 true。
    // refreshing 变化只更新等待条件，不取消正在执行的收尾动画。
    val generation = state.generation
    LaunchedEffect(generation) {
        if (generation > 0) {
            state.runRefresh { snapshotFlow { currentRefreshing.value }.first { !it } }
        }
    }
    PullToRefreshBox(
        isRefreshing = refreshing || state.busy,
        state = state,
        onRefresh = {
            if (!state.busy) {
                state.beginRefresh()
                onRefresh()
            }
        },
        enabled = !state.busy,
        modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).clipToBounds(),
        indicator = {}
    ) {
        // 列表尺寸不随每帧位移重测；静止时占满可用高度，不留底部空洞。
        content(Modifier.fillMaxSize().graphicsLayer {
            translationY = refreshOffsetPx(state.distanceFraction, REFRESH_GAP.toPx())
        })
        Box(Modifier.fillMaxWidth().height(REFRESH_GAP).graphicsLayer {
            translationY = refreshOffsetPx(state.distanceFraction, REFRESH_GAP.toPx()) - REFRESH_GAP.toPx()
            alpha = state.lineAlpha
        }) {
            RefreshStroke(state)
        }
    }
}

@Composable
private fun RefreshStroke(state: RefreshFeedbackState) {
    val color = MaterialTheme.colorScheme.primary
    // 只在刷新期间存在无限动画；静止时不会让应用保持逐帧唤醒。
    val running = state.phase == RefreshFeedbackState.Phase.REFRESHING
    val phase = if (running) rememberInfiniteTransition(label = "refresh-sweep").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "refresh-position"
    ) else null
    // 结束时从最后绘制的位置扩展，避免线段瞬移；这不是 Compose 状态，不触发重组。
    val last = remember { floatArrayOf(0.3f, 0.7f) }
    Canvas(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        val fraction = state.distanceFraction
        if (fraction <= 0f) return@Canvas
        val left: Float
        val right: Float
        when (state.phase) {
            RefreshFeedbackState.Phase.REFRESHING -> {
                left = (phase?.value ?: 0.5f) * 0.6f
                right = left + 0.4f
                last[0] = left
                last[1] = right
            }
            RefreshFeedbackState.Phase.COMPLETING, RefreshFeedbackState.Phase.RETURNING -> {
                val done = state.completionFraction
                left = last[0] * (1f - done)
                right = last[1] + (1f - last[1]) * done
            }
            else -> {
                val half = 0.2f * fraction.coerceAtMost(1f)
                left = 0.5f - half
                right = 0.5f + half
                last[0] = left
                last[1] = right
            }
        }
        drawLine(color, Offset(size.width * left, size.height / 2f),
            Offset(size.width * right, size.height / 2f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
    }
}

private val REFRESH_GAP = 48.dp
