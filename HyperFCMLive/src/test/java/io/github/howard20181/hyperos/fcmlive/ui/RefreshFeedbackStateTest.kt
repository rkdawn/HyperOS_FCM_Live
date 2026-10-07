package io.github.howard20181.hyperos.fcmlive.ui

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class RefreshFeedbackStateTest {
    private class Clock : MonotonicFrameClock {
        var nanos = 0L
        var afterFrame: () -> Unit = {}
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            nanos += 16_666_667L
            yield()
            return onFrame(nanos).also { afterFrame() }
        }
    }

    @Test fun partialPullRetractsWithoutAnyRefreshDelay() {
        val clock = Clock()
        var waited = 0L
        val state = RefreshFeedbackState({ clock.nanos / 1_000_000 }, { waited += it })
        val positions = mutableListOf<Float>()
        clock.afterFrame = { positions += state.distanceFraction }
        runBlocking(clock) {
            state.snapTo(0.2f)
            assertEquals(0.2f, state.distanceFraction, 0.0001f)
            state.animateToHidden()
        }
        assertEquals(0L, waited)
        assertEquals(RefreshFeedbackState.Phase.IDLE, state.phase)
        assertEquals(0f, state.distanceFraction, 0f)
        assertTrue(positions.any { it > 0f && it < 0.2f })
        assertTrue(positions.zipWithNext().all { (a, b) -> b <= a + 0.00001f })
    }

    @Test fun committedRefreshHoldsThenFadesBeforeMovingBack() {
        val clock = Clock()
        var waited = 0L
        val state = RefreshFeedbackState({ clock.nanos / 1_000_000 }, {
            waited += it
            clock.nanos += it * 1_000_000
        })
        val frames = mutableListOf<Triple<RefreshFeedbackState.Phase, Float, Float>>()
        clock.afterFrame = { frames += Triple(state.phase, state.distanceFraction, state.lineAlpha) }
        runBlocking(clock) {
            state.snapTo(1.4f)
            state.beginRefresh()
            state.animateToHidden() // Material3 松手后的 hide，不能提前关闭留白。
            assertEquals(1.4f, state.distanceFraction, 0f)
            state.animateToThreshold()
            state.runRefresh {}
        }
        assertTrue(waited > 0L)
        assertTrue(frames.any { it.first == RefreshFeedbackState.Phase.COMPLETING })
        assertTrue(frames.filter { it.first == RefreshFeedbackState.Phase.COMPLETING }.all { it.second == 1f })
        assertTrue(frames.filter { it.first == RefreshFeedbackState.Phase.RETURNING }.all { it.third == 0f })
        assertEquals(RefreshFeedbackState.Phase.IDLE, state.phase)
        assertFalse(state.busy)
    }

    @Test fun repeatedPullDoesNotReuseOldPosition() {
        val clock = Clock()
        val state = RefreshFeedbackState({ clock.nanos / 1_000_000 }, {})
        runBlocking(clock) {
            state.snapTo(0.8f)
            state.animateToHidden()
            state.snapTo(0.1f)
            assertEquals(0.1f, state.distanceFraction, 0f)
            state.animateToHidden()
            state.runRefresh {} // 普通手势不能误播刷新完成动画。
            assertEquals(0f, state.distanceFraction, 0f)
        }
    }

    @Test fun sameFrameCompletionStillRunsExactlyOneCycle() {
        val clock = Clock()
        val state = RefreshFeedbackState({ clock.nanos / 1_000_000 }, {})
        runBlocking(clock) {
            state.snapTo(1.2f)
            state.beginRefresh()
            state.beginRefresh() // 回调和重组重复观察到同一轮刷新。
            assertEquals(1, state.generation)
            state.runRefresh {} // 扫描已在组合看见 true 之前完成。
            assertEquals(RefreshFeedbackState.Phase.IDLE, state.phase)
            state.beginRefresh()
            assertEquals(2, state.generation)
            state.runRefresh {}
        }
    }

    @Test fun frameworkCallbacksCannotCancelTheFeedbackAnimation() {
        val clock = Clock()
        val state = RefreshFeedbackState({ clock.nanos / 1_000_000 }, {})
        runBlocking(clock) {
            state.beginRefresh()
            val animation = launch { state.runRefresh {} }
            while (state.busy) {
                state.animateToThreshold()
                state.animateToHidden()
                state.snapTo(0f)
                yield()
            }
            animation.join()
        }
        assertEquals(0f, state.distanceFraction, 0f)
        assertFalse(state.busy)
    }

    @Test fun slowScanMustFinishBeforeCompletionFeedback() {
        val clock = Clock()
        var waited = -1L
        val state = RefreshFeedbackState({ clock.nanos / 1_000_000 }, { waited = it })
        runBlocking(clock) {
            state.beginRefresh()
            state.runRefresh {
                assertEquals(RefreshFeedbackState.Phase.REFRESHING, state.phase)
                assertEquals(1f, state.distanceFraction, 0f)
                clock.nanos += 5_000_000_000L
            }
        }
        assertEquals(0L, waited)
        assertFalse(state.busy)
    }

    @Test fun cancelledCycleResetsBeforeNextGesture() {
        val clock = Clock()
        val state = RefreshFeedbackState({ clock.nanos / 1_000_000 }, {})
        runBlocking(clock) {
            val waiting = CompletableDeferred<Unit>()
            state.beginRefresh()
            val animation = launch { state.runRefresh {
                waiting.complete(Unit)
                CompletableDeferred<Unit>().await()
            } }
            waiting.await()
            animation.cancelAndJoin()
            assertFalse(state.busy)
            assertEquals(0f, state.distanceFraction, 0f)
            assertEquals(1f, state.lineAlpha, 0f)
            state.snapTo(0.15f)
            state.animateToHidden()
        }
    }

    @Test fun layerGeometryDoesNotCreateTopOrBottomOvershootAtRest() {
        assertEquals(0f, refreshOffsetPx(0f, 48f), 0f)
        assertEquals(48f, refreshOffsetPx(1f, 48f), 0f)
        assertEquals(0f, refreshOffsetPx(-1f, 48f), 0f)
        assertEquals(96f, refreshOffsetPx(3f, 48f), 0f)
    }
}
