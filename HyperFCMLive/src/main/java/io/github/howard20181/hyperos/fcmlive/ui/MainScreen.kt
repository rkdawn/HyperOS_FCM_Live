package io.github.howard20181.hyperos.fcmlive.ui

import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import io.github.howard20181.hyperos.fcmlive.R
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppShapes
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppSurfaces

/**
 * Compose half of the settings screen: the top bar and the app list.
 *
 * Nothing here decides anything. Every value arrives as state and every tap
 * leaves as a callback, so the Activity stays the only owner of what the
 * allowlist means — search debouncing, multi-select staging, the package scan
 * and the Xposed write all live there, untouched by this file.
 *
 * There is no View left in the tree: the inline search field is a Compose
 * [BasicTextField] whose focus and keyboard ride the composition itself —
 * entering search composes the field, which is what raises the IME, and
 * leaving it disposes the node, which is what lowers the IME. The Activity
 * only sees text.
 */

/** Top bar flags; owned by MainActivity and pushed wholesale on every change. */
data class MainTopBarState(
    val title: String,
    val searching: Boolean = false,
    val multiSelect: Boolean = false,
    val allVisibleSelected: Boolean = false,
    val overflow: OverflowState = OverflowState()
)

/** The four overflow toggles. Pure state — the Activity owns the consequences. */
data class OverflowState(
    val showSystemApps: Boolean = false,
    val showFcmSupportedOnly: Boolean = false,
    val excludeMiPushApps: Boolean = false,
    val strictMode: Boolean = false
)

/** Every action the top bar can ask for. */
data class MainActions(
    val onBack: () -> Unit,
    val onSearch: () -> Unit,
    val onBatchAdd: () -> Unit,
    val onBatchRemove: () -> Unit,
    val onSelectAll: () -> Unit,
    val onAbout: () -> Unit,
    val onToggleShowSystemApps: () -> Unit,
    val onToggleShowFcmOnly: () -> Unit,
    val onToggleExcludeMiPush: () -> Unit,
    val onToggleStrictMode: () -> Unit,
    val onDiagnostics: () -> Unit
)

/**
 * The whole page: bar, list and the refresh hairline.
 *
 * The list scan is a background task the screen surfaces as a 2dp line
 * sweeping the top edge ([RefreshLine]); onResume rescans, so the common
 * path never needs a gesture at all.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen(
    topBarState: MainTopBarState,
    actions: MainActions,
    query: String,
    onQueryChange: (String) -> Unit,
    apps: List<AppListStore.AppEntry>,
    multiSelect: Boolean,
    selected: Set<String>,
    onRowClick: (AppListStore.AppEntry) -> Unit,
    onRowLongClick: (AppListStore.AppEntry) -> Unit,
    loadIcon: (AppListStore.AppEntry) -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    // Remembered here, inside the composition, so the position is part of the
    // saveable state the host window restores. The Activity used to build the
    // state itself and hand it in, which is the one way to get a `LazyListState`
    // nobody ever saves — the list came back at the top after a rotation, the
    // opposite of what building it there was meant to achieve.
    lazyListState: LazyListState = rememberLazyListState(),
    // The M3 feedback surface. Owned by the caller, because that is where the
    // messages originate; hosted here, because this is the tree on screen.
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    // The pull's own progress: 0 at rest, 1 at the commit threshold, >1 past
    // it. Only read for visuals — the gesture itself belongs to
    // PullToRefreshBox above.
    val pullState = rememberPullToRefreshState()

    // The gap's position, in "fraction of the full gap" units (0 = closed,
    // 1 = fully open). While the finger is down it mirrors distanceFraction
    // 1:1; once the scan commits, a spring takes over and parks it at 1 for
    // as long as the scan runs (the line needs the room); when both the
    // finger and the scan are done, the same spring returns it to 0 — the
    // "检测完再弹回去" the user asked for.
    //
    // Frame-economy contract (the second jank fix): the fraction States are
    // READ ONLY INSIDE the graphicsLayer lambda below. A graphicsLayer block
    // is a draw-phase observer — reading a State there subscribes the layer
    // to it without invalidating composition, so a finger moving at 120 Hz
    // re-runs the layer update and nothing else. The previous revision
    // computed `gapFraction` in composition scope, which made every gesture
    // frame a full MainScreen recompose — Scaffold, topBar, the
    // PullToRefreshBox lambda, all of it. That was the residual jank.
    // `refreshing` still drives composition (the line's visibility toggles),
    // but it flips once per scan, not per frame.
    val gestureFraction = remember { mutableStateOf(0f) }
    val gapSpring = remember { Animatable(0f) }
    // Finger tracking stays a composition write (the finger is the animator);
    // derivedStateOf keeps the recomposition it triggers scoped to the
    // observers that actually read `gestureOwns`, not the whole screen.
    //
    // NOTE: `gestureOwns` deliberately does NOT consult pullState.isAnimating.
    // The frame drives its fraction with a per-frame snapTo, which restarts
    // the internal Animatable every frame — isAnimating flickers frame to
    // frame. A display source chosen on that flag would flip between the
    // gesture value and the spring's parked 0, and the gap would visibly
    // blink shut and back every few frames — the "掉一下帧又弹回去" the user
    // saw. The flag only decides WHO WRITES next (the sequencer below), and
    // the write path is idempotent per source, so the flicker there is
    // harmless.
    val gestureOwns by remember {
        derivedStateOf {
            !refreshing && pullState.distanceFraction > 0f
        }
    }
    if (gestureOwns && gestureFraction.value != pullState.distanceFraction) {
        gestureFraction.value = pullState.distanceFraction
    }
    // One sequencer owns the whole lifecycle, in order. The close leg waits
    // 380ms before pulling the gap down — exactly the duration of the line's
    // zip-open + fade — so the line finishes its completion read *inside* the
    // open gap instead of sliding up behind the top bar mid-animation (the
    // "线跑到最上面" the user saw).
    var refreshingJustEnded by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (!refreshing) {
            refreshingJustEnded = true
            delay(600)
            refreshingJustEnded = false
        }
    }
    LaunchedEffect(refreshing, gestureOwns) {
        when {
            gestureOwns -> {
                // Finger down: make sure the spring is parked.
                if (gapSpring.value != 0f) gapSpring.snapTo(0f)
            }
            refreshing -> {
                // Scan committed: open to full and hold, from wherever the
                // finger left it. Default Animatable spec (critical damping,
                // stiffness 1500) — same curve the framework uses for its
                // own indicator, and the one that does not read as dropped
                // frames.
                val start = maxOf(gestureFraction.value, gapSpring.value)
                gapSpring.snapTo(start.coerceIn(0f, 1f))
                gapSpring.animateTo(1f)
            }
            else -> {
                // Finger lifted, no scan: close. Also the scan-just-ended leg —
                // both arrive here, and both close the gap the same way.
                // The spring picks up from the larger of the two sources, then
                // the gesture value retires — AFTER the spring has taken the
                // hand-off, so no frame renders a closed gap in between.
                val releasePoint = maxOf(gestureFraction.value, gapSpring.value)
                if (releasePoint > 0.01f && !gapSpring.isRunning) {
                    gapSpring.snapTo(releasePoint.coerceIn(0f, 1f))
                    // Let the line's zip+fade (300+240ms, overlapping) finish
                    // before the gap itself starts moving.
                    if (refreshingJustEnded) {
                        delay(380)
                    }
                    gapSpring.animateTo(0f)
                }
                gestureFraction.value = 0f
            }
        }
    }

    // A finished refresh puts the list back at the top.
    //
    // The scan re-sorts: checked apps first, everything else alphabetically. A
    // row the user just unchecked therefore leaves the top group and lands
    // wherever its name puts it, which can be hundreds of rows down. A
    // `LazyColumn` does not hold the scroll *index* across that — on every
    // measure it rewrites the position to wherever the remembered first visible
    // row's key moved to (LazyList, `updateScrollPositionIfTheFirstItemWasMoved`),
    // so the page travelled down with the row that was unchecked. Uncheck a row
    // near the top, pull to refresh, and the view ended up at the bottom of the
    // list.
    //
    // The top is where a refresh belongs: the pull reaches this box only as
    // unconsumed nested scroll, which a list that is not already at the very top
    // does not produce. Asking for item 0 is also what makes the move hold —
    // `scrollToItem` forgets the stored key (`requestPositionAndForgetLastKnownKey`),
    // so the next measure has nothing left to pull the position down with.
    var wasRefreshing by remember { mutableStateOf(refreshing) }
    LaunchedEffect(refreshing) {
        if (wasRefreshing && !refreshing) {
            lazyListState.scrollToItem(0)
        }
        wasRefreshing = refreshing
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // `background`, not `surface`: it is the mapped `pageBg`, and it is
        // also what the Activity paints the window with, so the strip under the
        // status bar and the gesture hint line cannot end up a different tone.
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            MainTopBar(
                state = topBarState,
                actions = actions,
                query = query,
                onQueryChange = onQueryChange
            )
        }
    ) { innerPadding ->
        // The pull does what the user described: the whole list shifts down,
        // opening a gap under the bar; the refresh line lives in that gap and
        // sweeps while the scan runs; when the scan ends the list springs
        // back. No chevron, no flip, no overlap with the rows — the gap is
        // reserved by the layout itself, so nothing is ever covered.
        //
        // Mechanics: distanceFraction (0 at rest, 1 at the commit threshold)
        // drives the gap while the finger is down, 1:1. Once the scan starts
        // (or the finger lifts) a spring owns the offset — the scan holds a
        // minimum gap open for the line, and when both the finger and the
        // scan are done it returns to 0. PullToRefreshBox still owns the
        // gesture recognition; its built-in indicator stays empty.
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            state = pullState,
            modifier = Modifier
                // Only the top edge is a hard stop — the bar above owns it.
                .padding(top = innerPadding.calculateTopPadding())
                .fillMaxSize(),
            indicator = {},
            content = {
                // The gap is a FIXED-height block whose visibility is driven by
                // graphicsLayer translation — a draw-phase-only property. The
                // previous revision drove a Spacer's height from the animated
                // fraction, which re-measured the whole list every frame; that
                // is what the "掉帧" was. Here the closed gap slides up behind
                // the top bar (negative translation), the open one sits at 0.
                // The LazyColumn below never re-measures during the animation.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Read the fraction States HERE, not in composition:
                            // a graphicsLayer block re-runs its update when the
                            // States it reads change, without invalidating
                            // composition — the whole point of the frame-economy
                            // contract above.
                            //
                            // Source selection is "whichever is non-zero", not a
                            // mode flag: the frame's snapTo makes isAnimating
                            // flicker frame to frame, and a flag-chosen source
                            // would blink the gap shut on every flickering frame
                            // (the spring sits at 0 while the gesture value
                            // still holds the pull). max() of both is continuous
                            // through every hand-off — gesture → spring open,
                            // spring → gesture close, all monotonic.
                            val f = maxOf(gestureFraction.value, gapSpring.value)
                            val gapPx = (GAP_REST + GAP_TRAVEL * f).toPx()
                            translationY = gapPx - (GAP_REST + GAP_TRAVEL).toPx()
                        }
                ) {
                    // Fixed-height gap block: the sweeping line lives here.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(GAP_REST + GAP_TRAVEL)
                    ) {
                        RefreshLine(
                            visible = refreshing,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                    AppListPane(
                        apps = apps,
                        multiSelect = multiSelect,
                        selected = selected,
                        lazyListState = lazyListState,
                        onRowClick = onRowClick,
                        onRowLongClick = onRowLongClick,
                        loadIcon = loadIcon,
                        bottomPadding = innerPadding.calculateBottomPadding() + LIST_BOTTOM_PAD
                    )
                }
            }
        )
    }
}


/**
 * The refresh affordance: a 2dp hairline at the very top of the list, sweeping
 * left↔right for as long as the scan runs.
 *
 * Indeterminate on purpose. A ring implied "pull to refresh" as a first-class
 * gesture; the gesture is now a fallback (onResume rescans automatically, so
 * the common path never needs it), and the only honest claim this screen makes
 * while scanning is "a scan is running" — no progress, no completion circle,
 * no bounce. The line appears by fading in, sweeps on an infinite transition,
 * and fades out when the scan ends; everything reads inside the draw scope, so
 * the sweep costs draw passes only.
 */
@Composable
private fun RefreshLine(visible: Boolean, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val sweep = rememberInfiniteTransition(label = "refresh-line")
    val phase = sweep.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "refresh-line-phase"
    )
    val alpha = remember { Animatable(0f) }
    // The completion leg. 0 = the segment where the sweep left it; 1 = the
    // line closed into one full-width stroke. Frozen start/end are captured
    // the moment the scan ends, so the close-up begins where the eye last
    // saw the segment instead of jumping to an edge.
    val completion = remember { Animatable(1f) }
    var doneStart by remember { mutableStateOf(0f) }
    var doneEnd by remember { mutableStateOf(1f) }

    LaunchedEffect(visible) {
        if (visible) {
            // A new scan: reset the close-up and ride the fade in.
            completion.snapTo(0f)
            alpha.animateTo(1f, tween(durationMillis = 200))
        } else if (alpha.value > 0.01f) {
            // Scan finished. The segment zips open into a full-width line —
            // tail runs to the left edge, head to the right — and the whole
            // stroke fades. One decisive "done", the Chrome progress-bar
            // completion read, instead of the sweep just evaporating.
            val head = (phase.value + 1f) / 2f
            doneStart = (head - REFRESH_LINE_SEGMENT).coerceAtLeast(0f)
            doneEnd = head.coerceAtLeast(doneStart)
            completion.snapTo(0f)
            completion.animateTo(
                1f,
                tween(durationMillis = 300, easing = FastOutSlowInEasing)
            )
            alpha.animateTo(0f, tween(durationMillis = 240))
        }
    }

    Canvas(modifier = modifier.fillMaxWidth().height(REFRESH_LINE_THICKNESS)) {
        val a = alpha.value
        if (a <= 0.01f) {
            return@Canvas
        }
        val w = size.width
        val startF: Float
        val endF: Float
        if (!visible) {
            // Done leg: interpolate the frozen segment out to full width.
            val c = completion.value
            startF = doneStart + (0f - doneStart) * c
            endF = doneEnd + (1f - doneEnd) * c
        } else {
            // Sweeping: short segment travelling the width, tail behind.
            val head = (phase.value + 1f) / 2f
            endF = head
            startF = (head - REFRESH_LINE_SEGMENT).coerceAtLeast(0f)
        }
        drawLine(
            color = lineColor,
            start = Offset(startF * w, size.height / 2f),
            end = Offset(endF * w, size.height / 2f),
            strokeWidth = size.height,
            alpha = a,
            cap = StrokeCap.Round
        )
    }
}

/** The gap's closed height: enough for the hairline to live in at rest. */
private val GAP_REST = 4.dp

/** How far past rest the fully-open gap reaches, at the commit threshold. */
private val GAP_TRAVEL = 40.dp

/** Thickness of the top refresh hairline. 3dp: 2dp read as too faint to spot. */
private val REFRESH_LINE_THICKNESS = 3.dp

/**
 * Sweeping segment length as a fraction of the line's width. 0.22 was easy to
 * lose against a full-width list; 0.40 covers enough of the screen that the
 * motion catches the eye at a glance.
 */
private const val REFRESH_LINE_SEGMENT = 0.40f

/** Bottom padding the last list card keeps above the navigation-bar inset. */
private val LIST_BOTTOM_PAD = 16.dp


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(
    state: MainTopBarState,
    actions: MainActions,
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 64dp of bar under the status bar — the same height [AppTopBar]
            // gives every secondary page, and the M3 top-app-bar height. The
            // old 100dp (12dp above and below a 52dp row) read as dead air on
            // both edges; 6dp does what the status bar itself does not.
            .heightIn(min = 64.dp)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 10.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.searching || state.multiSelect) {
            TopBarIconButton(
                painter = painterResource(R.drawable.ic_arrow_back),
                description = stringResource(
                    if (state.multiSelect) R.string.exit_multi_select else R.string.exit_search
                ),
                onClick = actions.onBack
            )
        } else {
            Spacer(modifier = Modifier.width(6.dp))
        }

        // Title and search share this slot: only one of them composes at a
        // time, and the query text lives in the Activity's state, so nothing
        // is lost between the two.
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (!state.searching) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            SearchField(
                visible = state.searching,
                query = query,
                onQueryChange = onQueryChange
            )
        }

        if (state.multiSelect) {
            TopBarIconButton(
                painter = painterResource(R.drawable.ic_batch_add),
                description = stringResource(R.string.batch_add_allowlist),
                onClick = actions.onBatchAdd
            )
            TopBarIconButton(
                painter = painterResource(R.drawable.ic_batch_remove),
                description = stringResource(R.string.batch_remove_allowlist),
                onClick = actions.onBatchRemove
            )
            TopBarIconButton(
                painter = painterResource(
                    if (state.allVisibleSelected) R.drawable.ic_deselect_all else R.drawable.ic_select_all
                ),
                description = stringResource(
                    if (state.allVisibleSelected) R.string.deselect_all else R.string.select_all
                ),
                onClick = actions.onSelectAll
            )
        } else {
            if (!state.searching) {
                TopBarIconButton(
                    painter = painterResource(R.drawable.ic_search),
                    description = stringResource(R.string.tooltip_search),
                    onClick = actions.onSearch
                )
            }
            OverflowMenu(state.overflow, actions)
        }
    }
}

/**
 * Long-press tooltip on a 48dp target, which is what these icons were.
 *
 * The tooltip replaces the hand-placed PopupWindow the Activity used to build
 * for every icon. Same reason to exist (HyperOS puts its own bubble *on* the
 * control) and same gesture, but positioned by the toolkit instead of by
 * measured arithmetic that had to be re-clamped against every freeform window.
 *
 * These stay bare `IconButton`s on purpose, and the home bar is excluded from
 * every "fill the leading control" change: only the back control of a secondary
 * page wears a plate (`ui/AppTopBar.kt`). Here the icons are a row of peers over
 * a list — plating them would turn four tool icons into four buttons competing
 * with the content, and the bar has no leading control to single out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBarIconButton(
    painter: Painter,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TooltipBox(
        // Below, like the old PopupWindow: `showAtLocation` put the bubble
        // under the anchor with an 8dp gap. Above wedged it between the icon
        // and the status bar, which is where it looked pinned to the bar
        // instead of attached to the button.
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Below
        ),
        tooltip = {
            PlainTooltip {
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        },
        state = rememberTooltipState(),
        modifier = modifier
    ) {
        IconButton(onClick = onClick) {
            Icon(
                painter = painter,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Inline app search, Compose end to end.
 *
 * The platform SearchView it replaces was kept for its IME plumbing; Compose
 * turns out to own the same plumbing through the composition itself: entering
 * search composes this field, and [LaunchedEffect] rides the first frame to
 * grab focus and raise the keyboard — the "expand and focus" the iconified
 * SearchView did on demand. Leaving search disposes the node, which drops
 * focus and lowers the keyboard without a single explicit call.
 *
 * The Activity sees only text: `onQueryChange` is its debounce entry point,
 * and the query itself lives in the Activity's state, so a restore after a
 * rebuild re-composes the field already holding the text.
 */
@Composable
private fun SearchField(
    visible: Boolean,
    query: String,
    onQueryChange: (String) -> Unit
) {
    if (!visible) {
        return
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .focusRequester(focusRequester),
        singleLine = true,
        // Both colours come from the ColorScheme, not from a colour resource.
        // A `@color/` read here resolves through the *XML* theme attributes,
        // which are a different source from the one this tree is dressed in:
        // the field would keep the theme's own neutral while everything around
        // it followed the picked seed and palette style.
        textStyle = LocalTextStyle.current.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        decorationBox = { inner ->
            // Fixed 52dp with the text vertically centred — the same slot the
            // platform field filled, and the same no-plate look: no container,
            // no indicator, palette-tinted text, hint and caret.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.search_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                inner()
            }
        }
    )
}

/**
 * Overflow menu, replacing `popup_overflow.xml` and the ~140 lines that used to
 * measure it: the 16dp panel, 12dp rows and aligned check boxes, all inside a
 * window it had to be clamped into by hand.
 *
 * Two things the stock [DropdownMenuItem] got wrong here, which is why the rows
 * are [MenuItemRow] instead:
 *
 * - its ripple is a rectangle. [MenuItemRow] clips before `clickable`, so the
 *   press stays inside the row's rounded plate;
 * - its container colour and corner are the library's, not the page's. Passing
 *   [LocalAppShapes] `menu`/`menuItem` puts the panel back on the same shape
 *   language as the settings menus.
 *
 * The width is intrinsic so the four check boxes land on one vertical line the
 * way `popup_overflow.xml` aligned them, but the popup is still measured from
 * its widest row — never from the window, which is what made the settings menus
 * open at the card's left edge.
 *
 * There is deliberately no divider above "设置": the XML had none, and the four
 * toggles plus a navigation row are one list, not two.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverflowMenu(state: OverflowState, actions: MainActions) {
    val shapes = LocalAppShapes.current
    var expanded by remember { mutableStateOf(false) }
    val moreLabel = stringResource(R.string.more_menu)
    Box {
        // Same long-press bubble as every other top-bar icon — the overflow
        // button lost it when the bar moved to Compose and never got it back.
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                TooltipAnchorPosition.Below
            ),
            tooltip = {
                PlainTooltip { Text(moreLabel, style = MaterialTheme.typography.bodySmall) }
            },
            state = rememberTooltipState()
        ) {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = moreLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .defaultMinSize(minWidth = MENU_OVERFLOW_MIN_WIDTH),
            shape = shapes.menu,
            // Popup level, as every dropdown — see LocalAppSurfaces.
            containerColor = LocalAppSurfaces.current.popup,
            offset = DpOffset(x = -MENU_EDGE_INSET, y = 0.dp)
        ) {
            MenuItemRow(
                label = stringResource(R.string.show_system_apps),
                modifier = Modifier.fillMaxWidth(),
                minWidth = MENU_OVERFLOW_MIN_WIDTH,
                arrangement = Arrangement.SpaceBetween,
                checkable = state.showSystemApps,
                trailing = { OverflowCheckbox(state.showSystemApps) },
                onClick = { expanded = false; actions.onToggleShowSystemApps() }
            )
            Spacer(modifier = Modifier.height(MENU_ITEM_GAP))
            MenuItemRow(
                label = stringResource(R.string.show_fcm_supported_apps),
                modifier = Modifier.fillMaxWidth(),
                minWidth = MENU_OVERFLOW_MIN_WIDTH,
                arrangement = Arrangement.SpaceBetween,
                checkable = state.showFcmSupportedOnly,
                trailing = { OverflowCheckbox(state.showFcmSupportedOnly) },
                onClick = { expanded = false; actions.onToggleShowFcmOnly() }
            )
            Spacer(modifier = Modifier.height(MENU_ITEM_GAP))
            MenuItemRow(
                label = stringResource(R.string.exclude_mipush_apps),
                modifier = Modifier.fillMaxWidth(),
                minWidth = MENU_OVERFLOW_MIN_WIDTH,
                arrangement = Arrangement.SpaceBetween,
                checkable = state.excludeMiPushApps,
                trailing = { OverflowCheckbox(state.excludeMiPushApps) },
                onClick = { expanded = false; actions.onToggleExcludeMiPush() }
            )
            Spacer(modifier = Modifier.height(MENU_ITEM_GAP))
            MenuItemRow(
                label = stringResource(R.string.strict_mode),
                modifier = Modifier.fillMaxWidth(),
                minWidth = MENU_OVERFLOW_MIN_WIDTH,
                arrangement = Arrangement.SpaceBetween,
                checkable = state.strictMode,
                trailing = { OverflowCheckbox(state.strictMode) },
                onClick = { expanded = false; actions.onToggleStrictMode() }
            )
            Spacer(modifier = Modifier.height(MENU_ITEM_GAP))
            MenuItemRow(
                label = stringResource(R.string.fcm_diagnostics),
                modifier = Modifier.fillMaxWidth(),
                minWidth = MENU_OVERFLOW_MIN_WIDTH,
                onClick = { expanded = false; actions.onDiagnostics() }
            )
            Spacer(modifier = Modifier.height(MENU_ITEM_GAP))
            MenuItemRow(
                label = stringResource(R.string.settings),
                modifier = Modifier.fillMaxWidth(),
                minWidth = MENU_OVERFLOW_MIN_WIDTH,
                onClick = { expanded = false; actions.onAbout() }
            )
        }
    }
}

/**
 * The overflow row's box: the shape of a checkbox, none of its semantics.
 *
 * The row itself is the toggle ([MenuItemRow] `checkable`), so it already
 * carries `Role.Checkbox` and the checked state. Left alone, this box would be
 * a second, unreachable stop in the a11y tree — it has no callback behind it,
 * so it would announce itself as a disabled checkbox with no label. Clearing
 * its semantics keeps the plate the eye expects and the tree honest.
 */
@Composable
private fun OverflowCheckbox(checked: Boolean) {
    Checkbox(
        checked = checked,
        onCheckedChange = null,
        modifier = Modifier.clearAndSetSemantics {}
    )
}

/**
 * The app list: a `LazyColumn` keyed by package name.
 *
 * The key is also why the scroll position survives a refresh without the manual
 * `setSelectionFromTop` the ListView needed — keys let Compose follow rows whose
 * order moved because a check promoted them.
 */
@Composable
fun AppListPane(
    apps: List<AppListStore.AppEntry>,
    multiSelect: Boolean,
    selected: Set<String>,
    lazyListState: LazyListState,
    onRowClick: (AppListStore.AppEntry) -> Unit,
    onRowLongClick: (AppListStore.AppEntry) -> Unit,
    loadIcon: (AppListStore.AppEntry) -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = lazyListState,
        // The bottom room comes from the caller's `innerPadding` (navigation
        // bar inset + FAB reservation). As *content* padding it lives inside
        // the scroll: cards glide under the gesture hint line on the way past
        // and only come to rest that far above it.
        contentPadding = PaddingValues(
            start = 12.dp, top = 4.dp, end = 12.dp, bottom = bottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = apps, key = { it.packageName }) { app ->
            AppRow(
                label = app.label,
                packageName = app.packageName,
                icon = app.icon,
                checked = app.checked,
                supportMiPush = app.supportMiPush,
                multiSelect = multiSelect,
                rowSelected = selected.contains(app.packageName),
                onClick = { onRowClick(app) },
                onLongClick = { onRowLongClick(app) },
                onIconMissing = { loadIcon(app) }
            )
        }
    }
}

/**
 * One app: card, icon, name with an optional MiPush tag, package name, and the
 * allowlist state.
 *
 * The old row needed a measure pass to stop a long name from pushing its tag off
 * the row; `weight(1f, fill = false)` says that directly, which is most of why
 * this is shorter than the adapter it replaces.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LazyItemScope.AppRow(
    label: String,
    packageName: String,
    icon: Drawable?,
    checked: Boolean,
    supportMiPush: Boolean,
    multiSelect: Boolean,
    rowSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onIconMissing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardSelected = multiSelect && rowSelected
    val shape = LocalAppShapes.current.card
    // A selected row is `primaryContainer`, so all three foregrounds on it have
    // to come from that role's pair. `onSurface`, `onSurfaceVariant` and
    // `primary` each read fine on the card and each disappear here: MONOCHROME's
    // `primaryContainer` is tone 25 (#3B3B3B), `onSurface` is tone 10 and
    // `primary` tone 0. The on/off of the allowlist stays legible because the
    // glyph itself differs — the tint's job under selection is contrast.
    val labelColor = if (cardSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val supportingColor = if (cardSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusTint = when {
        cardSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        checked -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val allowlistState = stringResource(
        if (checked) R.string.a11y_allowlist_on else R.string.a11y_allowlist_off
    )
    val mipushTag = stringResource(R.string.mipush_badge)
    val selectionState = stringResource(
        if (rowSelected) R.string.row_selected else R.string.row_unselected
    )
    val description = remember(label, packageName, allowlistState, supportMiPush, multiSelect, selectionState) {
        buildList {
            add(label)
            add(packageName)
            add(allowlistState)
            if (supportMiPush) add(mipushTag)
            if (multiSelect) add(selectionState)
        }.joinToString("，")
    }

    if (icon == null) {
        // Same trigger the adapter used: ask once per row, then let the
        // coalesced refresh in AppListStore recompose when the icon lands.
        LaunchedEffect(packageName) { onIconMissing() }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .semantics {
                contentDescription = description
                if (multiSelect) {
                    selected = rowSelected
                }
            }
            // See [MenuItemRow]: the ripple `combinedClickable` installs is
            // painted inside this node's rectangular bounds, so without the
            // clip in front it spills over the card's rounded corners.
            .clip(shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onLongClick = onLongClick,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                }
            ),
        shape = shape,
        color = if (cardSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            LocalAppSurfaces.current.card
        }
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = icon?.let { rememberDrawablePainter(it) }
                    ?: painterResource(R.drawable.ic_app_placeholder),
                contentDescription = null,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        color = labelColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (supportMiPush) {
                        MiPushBadge(text = mipushTag)
                    }
                }
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = supportingColor
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(
                    if (checked) R.drawable.ic_status_enabled else R.drawable.ic_status_disabled
                ),
                contentDescription = null,
                tint = statusTint,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * The MiPush chip: primary-container fill at the small corner radius, so the
 * label takes that fill's own partner (`onPrimaryContainer`). It used to be
 * `primary`, which is the *plate's* partner only when the plate is a handle —
 * on MONOCHROME that was #000000 on a #3B3B3B fill. In a selected row the chip's
 * fill and the row's fill are the same role, so the plate merges with the row;
 * the label still reads, which is the part that matters.
 */
@Composable
private fun MiPushBadge(text: String, modifier: Modifier = Modifier) {
    val fill = MaterialTheme.colorScheme.primaryContainer
    val radius = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
        modifier = modifier
            .padding(start = 6.dp)
            .drawBehind {
                drawRoundRect(color = fill, size = this.size, cornerRadius = CornerRadius(radius))
            }
            .padding(start = 6.dp, top = 2.dp, end = 6.dp, bottom = 2.dp)
    )
}

/**
 * Draw a [Drawable] as a Compose [Painter].
 *
 * Compose ships no painter for an arbitrary Drawable, and these icons come from
 * `PackageManager`, not from resources. Drawing onto the native canvas is what
 * an ImageView does with the same Drawable — including adaptive icons keeping
 * their safe zone, which a rasterise-to-bitmap detour would break.
 */
@Composable
private fun rememberDrawablePainter(drawable: Drawable): Painter =
    remember(drawable) { DrawablePainter(drawable) }

private class DrawablePainter(private val drawable: Drawable) : Painter() {
    override val intrinsicSize: Size
        get() = Size(
            drawable.intrinsicWidth.coerceAtLeast(0).toFloat(),
            drawable.intrinsicHeight.coerceAtLeast(0).toFloat()
        )

    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}

/**
 * The preview the other four screens already had. This screen is the largest
 * and the only one that had none, which is the one place a rendering change
 * could not be seen before it shipped.
 *
 * The rows are hand-made rather than scanned: a preview has no package manager
 * behind it, and the four states worth looking at are an allowlisted app, a
 * plain FCM app, an app carrying the MiPush tag, and one with neither. Icons
 * fall back to the placeholder, which is also what a row shows while its real
 * icon is still loading.
 */
@Preview(name = "Main — light", showBackground = true)
@Preview(
    name = "Main — dark intent",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun MainScreenPreview() {
    HyperFCMLiveTheme {
        MainScreen(
            topBarState = MainTopBarState(title = "FCM 唤醒白名单"),
            actions = MainActions(
                onBack = {},
                onSearch = {},
                onBatchAdd = {},
                onBatchRemove = {},
                onSelectAll = {},
                onAbout = {},
                onToggleShowSystemApps = {},
                onToggleShowFcmOnly = {},
                onToggleExcludeMiPush = {},
                onToggleStrictMode = {},
                onDiagnostics = {}
            ),
            query = "",
            onQueryChange = {},
            apps = listOf(
                AppListStore.AppEntry("com.tencent.mm", "微信").apply {
                    checked = true
                    supportFcm = true
                },
                AppListStore.AppEntry("org.telegram.messenger", "Telegram").apply {
                    supportFcm = true
                },
                AppListStore.AppEntry("com.example.pushapp", "推送示例").apply {
                    supportFcm = true
                    supportMiPush = true
                },
                AppListStore.AppEntry("com.example.reader", "阅读器")
            ),
            multiSelect = false,
            selected = emptySet(),
            onRowClick = {},
            onRowLongClick = {},
            loadIcon = {},
            refreshing = false,
            onRefresh = {}
        )
    }
}
