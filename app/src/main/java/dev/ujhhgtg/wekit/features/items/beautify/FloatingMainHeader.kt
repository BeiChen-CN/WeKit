package dev.ujhhgtg.wekit.features.items.beautify

import android.app.Activity
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.AbsListView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.mogic.WxViewPager
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.floatingGlassContainerColor
import dev.ujhhgtg.wekit.ui.content.floatingGlassSurface
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.IntNumberPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.content.rememberFloatingGlassHighlight
import dev.ujhhgtg.wekit.ui.content.rememberFloatingGlassTilt
import dev.ujhhgtg.wekit.ui.content.rememberViewBackdrop
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.allViews
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.ui.utils.theme.InjectedUiTheme
import dev.ujhhgtg.wekit.utils.HookHandle
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldUsingType
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Gives the shared LauncherUI action bar a floating glass surface on the first three tabs. */
object FloatingMainHeader : ClickableFeature(), IResolveDex {

    override val technicalId = "主页悬浮顶栏"
    override val nameRes = R.string.feature_floating_main_header_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_floating_main_header_description

    private const val TAG = "FloatingMainHeader"
    private const val ACTION_BAR_CONTAINER_CLASS = "androidx.appcompat.widget.ActionBarContainer"
    private const val ACTION_BAR_OVERLAY_LAYOUT_CLASS =
        "androidx.appcompat.widget.ActionBarOverlayLayout"
    private const val TOOLBAR_CLASS = "androidx.appcompat.widget.Toolbar"
    private const val CONTENT_FRAME_CLASS = "androidx.appcompat.widget.ContentFrameLayout"
    private const val FROSTED_CONTENT_CLASS = "com.tencent.mm.ui.FrostedContentView"
    private const val RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"

    private val fieldOverlayMode by dexField()
    private val fieldBaseInnerInsets by dexField()
    private val fieldLastInnerInsets by dexField()
    private val fieldWindowContentOverlay by dexField()

    override fun resolveDex(dexKit: DexKitBridge) {
        val overlay = dexKit.getClassData(ACTION_BAR_OVERLAY_LAYOUT_CLASS)!!
        val measure = overlay.methods.single {
            it.methodName == "onMeasure" && it.paramTypeNames == listOf("int", "int") &&
                it.returnTypeName == "void"
        }
        val measureReads = measure.usingFields
            .filter { it.usingType == FieldUsingType.Read }
            .map { it.field }
            .filter { it.className == ACTION_BAR_OVERLAY_LAYOUT_CLASS }
            .distinctBy { it.descriptor }
        val setter = overlay.methods.single {
            it.methodName == "setOverlayMode" && it.paramTypeNames == listOf("boolean") &&
                it.returnTypeName == "void"
        }
        // setOverlayMode also writes the legacy shadow-ignore flag. Only the actual overlay
        // flag is read by onMeasure, so do not depend on either field's obfuscated name.
        fieldOverlayMode.setDescriptor(
            setter.usingFields.filter { it.usingType == FieldUsingType.Write }
                .map { it.field }.distinctBy { it.descriptor }.single {
                    it.typeName == "boolean" && it in measureReads
                }
        )
        val draw = overlay.methods.single {
            it.methodName == "draw" && it.paramTypeNames == listOf("android.graphics.Canvas") &&
                it.returnTypeName == "void"
        }
        fieldWindowContentOverlay.setDescriptor(
            draw.usingFields.filter { it.usingType == FieldUsingType.Read }
                .map { it.field }.distinctBy { it.descriptor }.single {
                    it.className == ACTION_BAR_OVERLAY_LAYOUT_CLASS &&
                        it.typeName == "android.graphics.drawable.Drawable"
                }
        )

        val namedBase = measureReads.filter { it.fieldName == "mBaseInnerInsets" }
        if (namedBase.isNotEmpty()) {
            // Retain the existing path for hosts that keep AppCompat's original field names,
            // including the WindowInsetsCompat implementation.
            fieldBaseInnerInsets.setDescriptor(namedBase.single())
            fieldLastInnerInsets.setDescriptor(measureReads.single {
                it.fieldName == "mLastInnerInsets" && it.typeName == namedBase.single().typeName
            })
        } else {
            // The Rect implementation copies base content -> content, then base inner ->
            // inner, and finally compares inner with last inner. DexKit preserves instruction
            // order; validate this layout and its fitSystemWindows relationship strictly.
            val rects = measureReads.filter { it.typeName == "android.graphics.Rect" }
            require(rects.size == 5) { "Unexpected ActionBarOverlayLayout inset structure: $rects" }
            val fit = overlay.methods.single {
                it.methodName == "fitSystemWindows" &&
                    it.paramTypeNames == listOf("android.graphics.Rect") &&
                    it.returnTypeName == "boolean"
            }
            val fitRects = fit.usingFields.filter { it.usingType == FieldUsingType.Read }
                .map { it.field }.filter {
                    it.className == ACTION_BAR_OVERLAY_LAYOUT_CLASS &&
                        it.typeName == "android.graphics.Rect"
                }.distinctBy { it.descriptor }
            require(fitRects.size == 4 && fitRects[0] == rects[2] && fitRects[1] == rects[0] &&
                rects[4] !in fitRects
            ) { "Unexpected ActionBarOverlayLayout base/last inset relationship" }
            fieldBaseInnerInsets.setDescriptor(rects[2])
            fieldLastInnerInsets.setDescriptor(rects[4])
        }
    }

    private const val DEFAULT_CORNER_RADIUS = 24
    private const val DEFAULT_SIDE_MARGIN = 12
    private const val DEFAULT_TOP_GAP = 4
    private const val DEFAULT_ELEVATION = 4
    private const val DEFAULT_BLUR_RADIUS = 8

    private const val MIN_CORNER_RADIUS = 0
    private const val MAX_CORNER_RADIUS = 32
    private const val MIN_SIDE_MARGIN = 0
    private const val MAX_SIDE_MARGIN = 32
    private const val MIN_TOP_GAP = 0
    private const val MAX_TOP_GAP = 24
    private const val MIN_ELEVATION = 0
    private const val MAX_ELEVATION = 16
    private const val MIN_BLUR_RADIUS = 0
    private const val MAX_BLUR_RADIUS = 40

    private var blurRadiusDp by prefOption(
        "floating_main_header_blur_radius",
        DEFAULT_BLUR_RADIUS,
    )
    private var dynamicGravityHighlight by prefOption(
        "floating_main_header_dynamic_gravity_highlight",
        false,
    )
    private var cornerRadiusDp by prefOption(
        "floating_main_header_corner_radius",
        DEFAULT_CORNER_RADIUS,
    )
    private var sideMarginDp by prefOption(
        "floating_main_header_side_margin",
        DEFAULT_SIDE_MARGIN,
    )
    private var topGapDp by prefOption(
        "floating_main_header_top_gap",
        DEFAULT_TOP_GAP,
    )
    private var elevationDp by prefOption(
        "floating_main_header_elevation",
        DEFAULT_ELEVATION,
    )

    private data class GlassConfig(
        val blurRadiusDp: Int,
        val dynamicGravityHighlight: Boolean,
        val cornerRadiusDp: Int,
        val elevationDp: Int,
    )

    private data class HeaderGeometry(
        val cornerRadiusDp: Int,
        val sideMarginDp: Int,
        val topGapDp: Int,
        val elevationDp: Int,
    )

    private data class HeaderState(
        val header: ViewGroup,
        val layer: ComposeView,
        val glassConfig: MutableState<GlassConfig>,
        val originalMargins: IntArray,
        val overlayLayout: View,
        val layoutListener: View.OnLayoutChangeListener,
        val attachListener: View.OnAttachStateChangeListener,
        val contentFrame: View,
        val visualStates: MutableMap<View, HeaderVisualState>,
        val clipStates: List<HeaderClipState>,
        val originalContentShadow: Drawable?,
        val listStates: MutableMap<ViewGroup, ListState> = LinkedHashMap(),
        val contentOffsets: MutableMap<View, ContentOffsetState> = LinkedHashMap(),
        val frostedTopStates: MutableMap<View, FrostedTopState> = LinkedHashMap(),
        var geometry: HeaderGeometry? = null,
    )

    private class HeaderVisualState(val view: View) {
        val background = view.background
        val foreground = view.foreground
        val outlineProvider = view.outlineProvider
        val clipToOutline = view.clipToOutline
        val elevation = view.elevation
        val translationZ = view.translationZ
        val animator = view.stateListAnimator
        val transparentBackground = ColorDrawable(android.graphics.Color.TRANSPARENT)
        var installedOutlineProvider: ViewOutlineProvider? = null
        var visibilityBeforeHide: Int? = null
    }

    private class HeaderClipState(val view: ViewGroup) {
        val clipChildren = view.clipChildren
        val clipToPadding = view.clipToPadding
    }

    private class FrostedTopState(val view: View) {
        private val getter = view.reflekt().firstMethod {
            name = "getTopBlurAreaHeight"
            parameters()
        }
        private val setter = view.reflekt().firstMethod {
            name = "setTopBlurAreaHeight"
            parameters(Int::class)
        }
        private var originalHeight = getter.invoke() as Int

        fun clear() {
            val height = getter.invoke() as Int
            if (height == 0) return
            originalHeight = height
            setter.invoke(0)
            view.invalidate()
        }

        fun restore() {
            if (getter.invoke() as Int != 0) return
            setter.invoke(originalHeight)
            view.invalidate()
        }
    }

    private class ListState(val view: ViewGroup) {
        val paddingTop = view.paddingTop
        val clipToPadding = view.clipToPadding
        var installedPaddingTop = paddingTop
    }

    private class ContentOffsetState(val view: View) {
        val paddingTop = view.paddingTop
        val topMargin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin
        var installedPaddingTop = paddingTop
        var installedTopMargin = topMargin
    }

    private val sessions = WeakHashMap<WxViewPager, WeakReference<MainHeaderSession>>()
    private val hostOverlayModes = WeakHashMap<View, Boolean>()
    private val ownOverlayWrite = ThreadLocal.withInitial { false }

    override fun onEnable() {
        // Rect-based AppCompat calls View.fitSystemWindows directly from ContentFrameLayout;
        // it never reaches dispatchApplyWindowInsets, even on recent Android versions.
        View::class.reflekt().firstMethod {
            name = "fitSystemWindows"
            parameters(Rect::class)
        }.hookBefore {
            val content = thisObject as View
            sessions.values.mapNotNull { it.get() }.forEach { session ->
                args[0] = session.contentInsets(content, args[0] as Rect)
            }
        }
        // Overlay mode leaves an ActionBar inset for the content subtree to consume. Remove
        // only that synthetic inset, on this window's content frame, not on every host view.
        ViewGroup::class.reflekt().firstMethod {
            name = "dispatchApplyWindowInsets"
            parameters(WindowInsets::class)
        }.hookBefore {
            val content = thisObject as View
            sessions.values.mapNotNull { it.get() }.forEach { session ->
                args[0] = session.contentInsets(content, args[0] as WindowInsets)
            }
        }
        ACTION_BAR_OVERLAY_LAYOUT_CLASS.toClass().reflekt().firstMethod {
            name = "setOverlayMode"
            parameters(Boolean::class)
        }.hookAfter {
            if (ownOverlayWrite.get()!!) return@hookAfter
            val overlay = thisObject as View
            val requested = args[0] as Boolean
            hostOverlayModes[overlay] = requested
            sessions.values.mapNotNull { it.get() }
                .forEach { it.onHostOverlayModeChanged(overlay, requested) }
        }

        LauncherUI::class.reflekt().firstMethod {
            name = "onResume"
            parameters()
        }.hookAfter {
            sessionFor(thisObject as Activity)?.onChatTransition()
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "startChatting"
            parameters(String::class, android.os.Bundle::class, Boolean::class)
        }.hookBefore {
            sessionFor(thisObject as Activity)?.restoreBeforeChat()
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "startChatting"
            parameters(String::class, android.os.Bundle::class, Boolean::class)
        }.hookAfter {
            sessionFor(thisObject as Activity)?.onChatTransition()
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "closeChatting"
            parameters(Boolean::class)
        }.hookAfter {
            sessionFor(thisObject as Activity)?.scheduleSync()
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "onDestroy"
            parameters()
        }.hookAfter {
            removeSessionsForActivity(thisObject as Activity)
        }
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val activity = thisObject!!.reflekt()
                .firstField { type = "com.tencent.mm.ui.MMFragmentActivity" }
                .get()!! as Activity
            val viewPager = thisObject!!.reflekt()
                .firstField { name = "mViewPager" }
                .get()!! as WxViewPager
            val tabsAdapter = thisObject!!.reflekt()
                .firstField { name = "mTabsAdapter" }
                .get()!!
            if (sessions[viewPager]?.get() != null) return@hookAfter

            val session = MainHeaderSession(activity, viewPager, tabsAdapter).also { it.attach() }
            sessions[viewPager] = WeakReference(session)
        }
    }

    override fun onDisable() {
        sessions.values.mapNotNull { it.get() }.forEach(MainHeaderSession::detach)
        sessions.clear()
        hostOverlayModes.clear()
    }

    private fun setOverlayModeByWeKit(overlay: View, enabled: Boolean) {
        val wasOwnWrite = ownOverlayWrite.get()!!
        ownOverlayWrite.set(true)
        try {
            overlay.reflekt().firstMethod {
                name = "setOverlayMode"
                parameters(Boolean::class)
            }.invoke(enabled)
        } finally {
            ownOverlayWrite.set(wasOwnWrite)
        }
    }

    private fun requestContentInsets(overlay: View) {
        // AppCompat caches the *original* dispatched insets. Force the next measure to dispatch
        // again when activating/restoring, even if the host was already in overlay mode.
        val base = fieldBaseInnerInsets.field.get(overlay)!!
        fieldLastInnerInsets.field.set(overlay, if (base is Rect) Rect(base) else base)
        overlay.requestLayout()
        overlay.requestApplyInsets()
    }

    private fun sessionFor(activity: Activity): MainHeaderSession? =
        sessions.values.mapNotNull { it.get() }.firstOrNull { it.ownsActivity(activity) }

    private fun removeSessionsForActivity(activity: Activity) {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val session = entry.value.get()
            if (session == null || session.ownsActivity(activity)) {
                session?.detach()
                iterator.remove()
            }
        }
    }

    private fun scheduleAllSessions() {
        sessions.values.mapNotNull { it.get() }.forEach(MainHeaderSession::scheduleSync)
    }

    private fun currentGlassConfig() = GlassConfig(
        blurRadiusDp = blurRadiusDp,
        dynamicGravityHighlight = dynamicGravityHighlight,
        cornerRadiusDp = cornerRadiusDp,
        elevationDp = elevationDp,
    )

    private fun currentGeometry() = HeaderGeometry(
        cornerRadiusDp = cornerRadiusDp,
        sideMarginDp = sideMarginDp,
        topGapDp = topGapDp,
        elevationDp = elevationDp,
    )

    private class MainHeaderSession(
        private val activity: Activity,
        private val viewPager: WxViewPager,
        private val tabsAdapter: Any,
    ) {
        private val decorRoot = activity.window.decorView as ViewGroup
        private val lifecycleOwner = LifecycleOwnerProvider.getOrCreate(activity)
        private val transitionHost = viewPager.parent as View
        private var attached = false
        private var syncPosted = false
        private var headerState: HeaderState? = null
        private var selectedTabIndex = viewPager.currentItem
        private var tabSelectionHook: HookHandle? = null
        private var pendingTransitionLayoutListener: View.OnLayoutChangeListener? = null
        private val invalidHeightHeaders = WeakHashMap<View, Boolean>()

        private val syncRunnable = Runnable {
            syncPosted = false
            if (attached) sync()
        }
        private val decorLayoutListener = ViewTreeObserver.OnGlobalLayoutListener { scheduleSync() }
        private val decorPreDrawListener = ViewTreeObserver.OnPreDrawListener {
            headerState?.let(::neutralizeHeader)
            true
        }

        fun ownsActivity(candidate: Activity): Boolean = activity === candidate

        fun onHostOverlayModeChanged(overlay: View, requested: Boolean) {
            if (headerState?.overlayLayout !== overlay || requested) return
            if ((activity as LauncherUI).currentFragmet == null && selectedTabIndex != 3) {
                setOverlayModeByWeKit(overlay, true)
                requestContentInsets(overlay)
            }
        }

        fun contentInsets(content: View, insets: Rect): Rect {
            val state = headerState ?: return insets
            if (state.contentFrame !== content || selectedTabIndex == 3 ||
                (activity as LauncherUI).currentFragmet != null
            ) return insets
            val base = fieldBaseInnerInsets.field.get(state.overlayLayout)
            if (base !is Rect) return insets
            // Do not mutate AppCompat's cached Rect: restoration must receive the full inset.
            return Rect(insets).apply {
                top = (top - measuredHeaderHeight(state.header)).coerceAtLeast(base.top)
            }
        }

        @Suppress("DEPRECATION")
        fun contentInsets(content: View, insets: WindowInsets): WindowInsets {
            val state = headerState ?: return insets
            if (state.contentFrame !== content || selectedTabIndex == 3 ||
                (activity as LauncherUI).currentFragmet != null
            ) return insets
            // Use AppCompat's pre-ActionBar inner inset, not the root window inset: the latter
            // may already have been consumed as a content margin and would count status twice.
            // Older host AppCompat stores a Rect, newer versions a WindowInsetsCompat.
            val baseInsets = fieldBaseInnerInsets.field.get(state.overlayLayout)!!
            // Rect hosts add the bar only in their direct fitSystemWindows dispatch. Applying
            // the subtraction to a platform WindowInsets dispatch as well would consume it twice.
            if (baseInsets is Rect) return insets
            val baseTop = baseInsets.reflekt().firstMethod {
                name = "getSystemWindowInsetTop"
                parameters()
            }.invoke() as Int
            val top = (insets.systemWindowInsetTop - measuredHeaderHeight(state.header))
                .coerceAtLeast(baseTop)
            if (top >= insets.systemWindowInsetTop) return insets
            return insets.replaceSystemWindowInsets(
                insets.systemWindowInsetLeft, top,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
            )
        }

        fun restoreBeforeChat() = removeHeaderState()

        fun attach() {
            if (attached) return
            attached = true
            tabSelectionHook = tabsAdapter.reflekt().firstMethod {
                name = "onPageSelected"
                parameterCount = 1
            }.hookBeforeDirectly {
                if (thisObject !== tabsAdapter) return@hookBeforeDirectly
                // ReplaceNavigationBar maps a reordered pager index back to WeChat's logical
                // tab index at priority 100, before this default-priority callback.
                selectedTabIndex = args[0] as Int
                if (selectedTabIndex == 3) removeHeaderState()
                scheduleSync()
            }
            decorRoot.viewTreeObserver.addOnGlobalLayoutListener(decorLayoutListener)
            decorRoot.viewTreeObserver.addOnPreDrawListener(decorPreDrawListener)
            scheduleSync()
        }

        fun detach() {
            if (!attached) return
            attached = false
            tabSelectionHook?.unhook()
            tabSelectionHook = null
            decorRoot.removeCallbacks(syncRunnable)
            if (decorRoot.viewTreeObserver.isAlive) {
                decorRoot.viewTreeObserver.removeOnGlobalLayoutListener(decorLayoutListener)
                decorRoot.viewTreeObserver.removeOnPreDrawListener(decorPreDrawListener)
            }
            pendingTransitionLayoutListener?.let(transitionHost::removeOnLayoutChangeListener)
            pendingTransitionLayoutListener = null
            removeHeaderState()
            invalidHeightHeaders.clear()
        }

        fun scheduleSync() {
            if (!attached || syncPosted) return
            syncPosted = true
            decorRoot.postOnAnimation(syncRunnable)
        }

        fun onChatTransition() {
            if (!attached) return
            scheduleSync()
            pendingTransitionLayoutListener?.let(transitionHost::removeOnLayoutChangeListener)
            val listener = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    view: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int,
                ) {
                    transitionHost.removeOnLayoutChangeListener(this)
                    if (pendingTransitionLayoutListener === this) {
                        pendingTransitionLayoutListener = null
                    }
                    scheduleSync()
                }
            }
            pendingTransitionLayoutListener = listener
            transitionHost.addOnLayoutChangeListener(listener)
        }

        private fun sync() {
            if ((activity as LauncherUI).currentFragmet != null || selectedTabIndex == 3) {
                removeHeaderState()
                return
            }

            val current = headerState
            if (current != null && (!isValidHeader(current.header) ||
                current.visualStates.values.any { it.view.isToolbar() && !it.view.isAttachedToWindow }
            )) {
                removeHeaderState()
            }

            val state = headerState ?: createHeaderState(findMainHeader() ?: return) ?: return
            val height = measuredHeaderHeight(state.header)
            if (!isSafeHeaderHeight(state.header, height)) {
                removeHeaderState()
                return
            }

            state.glassConfig.value = currentGlassConfig()
            // WeChat can replace the custom title/menu subtree when changing tabs.
            nativeHeaderContainers(state).forEach { view ->
                state.visualStates.getOrPut(view) { HeaderVisualState(view) }
            }
            state.contentFrame.allViews.filter { it.javaClass.name == FROSTED_CONTENT_CLASS }
                .forEach { view ->
                    state.frostedTopStates.getOrPut(view) { FrostedTopState(view) }
                }
            applyGeometry(state, currentGeometry())
            updateGlassBounds(state, height)
            neutralizeHeader(state)
            applyListGeometry(state)
        }

        private fun findMainHeader(): ViewGroup? {
            return decorRoot.allViews
                .filterIsInstance<ViewGroup>()
                .filter { it.javaClass.name == ACTION_BAR_CONTAINER_CLASS }
                .filter(::isValidHeader)
                .filter { candidate -> candidate.allViews.any { it !== candidate && it.isToolbar() } }
                .minByOrNull { candidate ->
                    IntArray(2).also(candidate::getLocationInWindow)[1]
                }
        }

        private fun isValidHeader(header: ViewGroup): Boolean =
            header.isAttachedToWindow && header.rootView === decorRoot.rootView

        private fun measuredHeaderHeight(header: View): Int =
            header.height.takeIf { it > 0 } ?: header.measuredHeight

        private fun isSafeHeaderHeight(header: View, height: Int): Boolean {
            val safe = height > 0 && (decorRoot.height <= 0 || height < decorRoot.height)
            if (!safe && invalidHeightHeaders.put(header, true) == null) {
                WeLogger.e(
                    TAG,
                    "refusing invalid glass layer height: headerHeight=$height, rootHeight=${decorRoot.height}",
                )
            }
            return safe
        }

        private fun createHeaderState(header: ViewGroup): HeaderState? {
            val height = measuredHeaderHeight(header)
            if (!isSafeHeaderHeight(header, height)) return null

            val margins = header.layoutParams as ViewGroup.MarginLayoutParams
            val overlayLayout = findOverlayLayout(header)
            val contentFrame = (overlayLayout as ViewGroup).allViews.first {
                it.javaClass.name == CONTENT_FRAME_CLASS
            }
            // The capture must contain page content only: never sample the Toolbar/glass itself.
            check(viewPager.allViews.none { it === header })
            hostOverlayModes.putIfAbsent(
                overlayLayout,
                fieldOverlayMode.field.getBoolean(overlayLayout),
            )
            val visuals = header.allViews.filterIsInstance<ViewGroup>()
                .associateTo(LinkedHashMap<View, HeaderVisualState>()) { it to HeaderVisualState(it) }
            val clips = ArrayList<HeaderClipState>()
            var clipHost: ViewGroup? = header
            while (clipHost != null) {
                clips += HeaderClipState(clipHost)
                if (clipHost === overlayLayout) break
                clipHost = clipHost.parent as? ViewGroup
            }

            val configState = mutableStateOf(currentGlassConfig())
            val layer = ComposeView(header.context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isClickable = false
                isFocusable = false
                clipChildren = false
                clipToPadding = false
                setLifecycleOwner(lifecycleOwner)
                setContent {
                    InjectedUiTheme {
                        val config by configState
                        MainHeaderGlass(viewPager, lifecycleOwner, config)
                    }
                }
            }
            val layoutListener = View.OnLayoutChangeListener {
                    _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                    scheduleSync()
                }
            }
            val attachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = scheduleSync()

                override fun onViewDetachedFromWindow(v: View) = scheduleSync()
            }
            val state = HeaderState(
                header = header,
                layer = layer,
                glassConfig = configState,
                originalMargins = intArrayOf(
                    margins.leftMargin,
                    margins.topMargin,
                    margins.rightMargin,
                    margins.bottomMargin,
                ),
                overlayLayout = overlayLayout,
                layoutListener = layoutListener,
                attachListener = attachListener,
                contentFrame = contentFrame,
                visualStates = visuals,
                clipStates = clips,
                originalContentShadow = fieldWindowContentOverlay.field.get(overlayLayout) as Drawable?,
            )
            headerState = state
            setOverlayModeByWeKit(overlayLayout, true)
            neutralizeHeader(state)
            header.addOnLayoutChangeListener(layoutListener)
            header.addOnAttachStateChangeListener(attachListener)
            header.addView(
                layer,
                0,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height),
            )
            applyGeometry(state, currentGeometry())
            updateGlassBounds(state, height)
            requestContentInsets(overlayLayout)
            return state
        }

        private fun updateGlassBounds(state: HeaderState, height: Int) {
            val padding = (state.glassConfig.value.elevationDp * 2 *
                state.header.resources.displayMetrics.density).roundToInt()
            val params = state.layer.layoutParams as FrameLayout.LayoutParams
            val width = state.header.width + padding * 2
            if (params.width == width && params.height == height + padding * 2 &&
                params.leftMargin == -padding && params.topMargin == -padding &&
                params.rightMargin == -padding && params.bottomMargin == -padding
            ) return
            params.width = width
            params.height = height + padding * 2
            params.leftMargin = -padding
            params.topMargin = -padding
            // Balance the expanded child in wrap_content measurement; otherwise each layout
            // can increase ActionBarContainer's measured height by the shadow padding again.
            params.rightMargin = -padding
            params.bottomMargin = -padding
            state.layer.layoutParams = params
        }

        private fun neutralizeHeader(state: HeaderState) {
            // This host paints a separate full-width strip in dispatchDraw, independent of
            // its background. Keep its bottom frosted area and enabled state untouched.
            state.frostedTopStates.values.forEach(FrostedTopState::clear)
            state.visualStates.values.forEach { original ->
                val view = original.view
                if (view.background !== original.transparentBackground) {
                    view.background = original.transparentBackground
                }
                if (view.foreground != null) view.foreground = null
                if (view.elevation != 0f) view.elevation = 0f
                if (view.translationZ != 0f) view.translationZ = 0f
                if (view.stateListAnimator != null) view.stateListAnimator = null
                // Preserve measurement while hiding the entire native home Toolbar, including
                // custom title/menu views whose IDs and visibility are managed by WeChat.
                if (view.isToolbar()) {
                    if (selectedTabIndex == 0) {
                        if (original.visibilityBeforeHide == null) {
                            original.visibilityBeforeHide = view.visibility
                        }
                        if (view.visibility != View.INVISIBLE) view.visibility = View.INVISIBLE
                    } else {
                        restoreToolbarVisibility(original)
                    }
                }
                // Only the glass and Toolbar content have rounded bounds; their host is clear.
                val clip = original.installedOutlineProvider != null
                if (view.clipToOutline != clip) view.clipToOutline = clip
                if (view.outlineProvider !== original.installedOutlineProvider) {
                    view.outlineProvider = original.installedOutlineProvider
                }
            }
            state.clipStates.forEach { clip ->
                if (clip.view.clipChildren) clip.view.clipChildren = false
                if (clip.view.clipToPadding) clip.view.clipToPadding = false
            }
            if (fieldWindowContentOverlay.field.get(state.overlayLayout) != null) {
                fieldWindowContentOverlay.field.set(state.overlayLayout, null)
                state.overlayLayout.invalidate()
            }
        }

        private fun nativeHeaderContainers(state: HeaderState): Sequence<ViewGroup> = sequence {
            val pending = ArrayDeque<ViewGroup>()
            pending.add(state.header)
            while (pending.isNotEmpty()) {
                val view = pending.removeLast()
                yield(view)
                for (index in 0 until view.childCount) {
                    val child = view.getChildAt(index)
                    if (child is ViewGroup && child !== state.layer) pending.add(child)
                }
            }
        }

        private fun restoreToolbarVisibility(original: HeaderVisualState) {
            val visibility = original.visibilityBeforeHide ?: return
            if (original.view.visibility == View.INVISIBLE) original.view.visibility = visibility
            original.visibilityBeforeHide = null
        }

        private fun applyGeometry(state: HeaderState, geometry: HeaderGeometry) {
            val header = state.header
            val density = header.resources.displayMetrics.density
            val margins = header.layoutParams as ViewGroup.MarginLayoutParams
            val side = (geometry.sideMarginDp * density).toInt()
            val top = (geometry.topGapDp * density).toInt()
            val left = state.originalMargins[0] + side
            val topMargin = state.originalMargins[1] + top
            val right = state.originalMargins[2] + side
            if (margins.leftMargin != left || margins.topMargin != topMargin ||
                margins.rightMargin != right || margins.bottomMargin != state.originalMargins[3]
            ) {
                margins.leftMargin = left
                margins.topMargin = topMargin
                margins.rightMargin = right
                margins.bottomMargin = state.originalMargins[3]
                header.layoutParams = margins
            }
            if (state.geometry == geometry) return

            val outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val radius = geometry.cornerRadiusDp * view.resources.displayMetrics.density
                    outline.setRoundRect(0, 0, view.width, view.height.coerceAtLeast(1), radius)
                }
            }
            state.visualStates.values.filter { it.view.isToolbar() }.forEach { visual ->
                visual.installedOutlineProvider = outlineProvider
                visual.view.invalidateOutline()
            }
            state.geometry = geometry
        }

        @Suppress("DEPRECATION")
        private fun applyListGeometry(state: HeaderState) {
            val pagerLocation = IntArray(2).also(viewPager::getLocationInWindow)
            val centerX = pagerLocation[0] + viewPager.width / 2
            // Find the main scrolling viewport on the currently visible physical page. This
            // also works after ReplaceNavigationBar reorders the logical tab indices.
            val list = viewPager.allViews.filterIsInstance<ViewGroup>()
                .filter { it is AbsListView || it.isHostClass(RECYCLER_VIEW_CLASS) }
                .filter { view ->
                    if (!view.isShown || view.width <= 0 || view.height <= 0) return@filter false
                    val location = IntArray(2).also(view::getLocationInWindow)
                    location[0] <= centerX && location[0] + view.width > centerX
                }.maxByOrNull { it.width.toLong() * it.height } ?: return
            val systemTop = decorRoot.rootWindowInsets?.systemWindowInsetTop ?: return
            val listLocation = IntArray(2).also(list::getLocationInWindow)
            val headerHeight = measuredHeaderHeight(state.header)
            var reservedSpace = (listLocation[1] - systemTop).coerceAtLeast(0)
            var changed = false
            var ancestor = list.parent as? View
            // Some host page wrappers explicitly reserve actionBarSize in addition to window
            // insets. Only remove a measured one-bar reservation on the list's ancestor path;
            // never zero arbitrary page padding, status-bar padding, or other sibling layouts.
            while (ancestor != null && ancestor !== state.contentFrame) {
                val view = ancestor
                val original = state.contentOffsets[view]
                val padding = original?.paddingTop ?: view.paddingTop
                val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
                val margin = original?.topMargin ?: margins?.topMargin
                if (reservedSpace >= headerHeight && padding in headerHeight..(headerHeight + systemTop)) {
                    val offset = state.contentOffsets.getOrPut(view) { ContentOffsetState(view) }
                    val target = padding - headerHeight
                    if (view.paddingTop != target) {
                        view.setPadding(view.paddingLeft, target, view.paddingRight, view.paddingBottom)
                        changed = true
                    }
                    offset.installedPaddingTop = target
                    reservedSpace -= headerHeight
                }
                if (reservedSpace >= headerHeight && margins != null &&
                    margin != null && margin in headerHeight..(headerHeight + systemTop)
                ) {
                    val offset = state.contentOffsets.getOrPut(view) { ContentOffsetState(view) }
                    val target = margin - headerHeight
                    if (margins.topMargin != target) {
                        margins.topMargin = target
                        view.layoutParams = margins
                        changed = true
                    }
                    offset.installedTopMargin = target
                    reservedSpace -= headerHeight
                }
                ancestor = view.parent as? View
            }
            if (changed) return // Measure the new viewport before calculating its content padding.
            val original = state.listStates.getOrPut(list) { ListState(list) }
            val headerLocation = IntArray(2).also(state.header::getLocationInWindow)
            val cover = (headerLocation[1] + headerHeight - listLocation[1]).coerceAtLeast(0)
            // Preserve any larger native top padding instead of adding a second reservation.
            val target = maxOf(original.paddingTop, cover)
            if (list.paddingTop != target) {
                list.setPadding(list.paddingLeft, target, list.paddingRight, list.paddingBottom)
            }
            original.installedPaddingTop = target
            if (list.clipToPadding) list.clipToPadding = false
        }

        private fun removeHeaderState() {
            val state = headerState ?: return
            headerState = null
            val header = state.header
            header.removeOnLayoutChangeListener(state.layoutListener)
            header.removeOnAttachStateChangeListener(state.attachListener)
            if (state.layer.parent === header) header.removeView(state.layer)
            state.layer.disposeComposition()

            state.frostedTopStates.values.forEach(FrostedTopState::restore)
            state.visualStates.values.forEach { original ->
                val view = original.view
                restoreToolbarVisibility(original)
                if (view.background === original.transparentBackground) view.background = original.background
                if (view.foreground == null) view.foreground = original.foreground
                if (view.outlineProvider === original.installedOutlineProvider) {
                    view.outlineProvider = original.outlineProvider
                    view.clipToOutline = original.clipToOutline
                }
                view.elevation = original.elevation
                view.translationZ = original.translationZ
                view.stateListAnimator = original.animator
            }
            state.clipStates.forEach { original ->
                original.view.clipChildren = original.clipChildren
                original.view.clipToPadding = original.clipToPadding
            }
            if (fieldWindowContentOverlay.field.get(state.overlayLayout) == null) {
                fieldWindowContentOverlay.field.set(state.overlayLayout, state.originalContentShadow)
                state.overlayLayout.invalidate()
            }
            state.listStates.values.forEach { original ->
                val view = original.view
                if (view.paddingTop == original.installedPaddingTop) {
                    view.setPadding(view.paddingLeft, original.paddingTop, view.paddingRight, view.paddingBottom)
                }
                view.clipToPadding = original.clipToPadding
            }
            state.contentOffsets.values.forEach { original ->
                val view = original.view
                if (view.paddingTop == original.installedPaddingTop) {
                    view.setPadding(view.paddingLeft, original.paddingTop, view.paddingRight, view.paddingBottom)
                }
                val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
                if (margins != null && original.topMargin != null &&
                    margins.topMargin == original.installedTopMargin
                ) {
                    margins.topMargin = original.topMargin
                    view.layoutParams = margins
                }
            }
            val margins = header.layoutParams as ViewGroup.MarginLayoutParams
            margins.leftMargin = state.originalMargins[0]
            margins.topMargin = state.originalMargins[1]
            margins.rightMargin = state.originalMargins[2]
            margins.bottomMargin = state.originalMargins[3]
            header.layoutParams = margins

            // The host may have changed its requested mode while the card was active.
            setOverlayModeByWeKit(state.overlayLayout, hostOverlayModes[state.overlayLayout] ?: false)
            requestContentInsets(state.overlayLayout)
        }

        private fun findOverlayLayout(header: View): View {
            var ancestor = header.parent
            while (ancestor is View) {
                if (ancestor.javaClass.name == ACTION_BAR_OVERLAY_LAYOUT_CLASS) return ancestor
                ancestor = ancestor.parent
            }
            error("$ACTION_BAR_OVERLAY_LAYOUT_CLASS not found above $ACTION_BAR_CONTAINER_CLASS")
        }

        private fun View.isToolbar(): Boolean = isHostClass(TOOLBAR_CLASS)

        private fun View.isHostClass(className: String): Boolean {
            var type: Class<*>? = javaClass
            while (type != null) {
                if (type.name == className) return true
                type = type.superclass
            }
            return false
        }
    }

    @Composable
    private fun MainHeaderGlass(
        viewPager: WxViewPager,
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        config: GlassConfig,
    ) {
        val containerColor = if (isSystemInDarkTheme()) {
            Color(0xFF191919)
        } else {
            Color(0xFFF7F7F7)
        }
        val backdrop = rememberViewBackdrop(
            viewPager,
            lifecycleOwner,
            if (config.blurRadiusDp > 0) {
                floatingGlassContainerColor(containerColor, config.blurRadiusDp.dp)
            } else {
                null
            },
        )
        val tilt = rememberFloatingGlassTilt(config.dynamicGravityHighlight)
        val highlight = rememberFloatingGlassHighlight(tilt, 0f)
        val shape: Shape = RoundedCornerShape(config.cornerRadiusDp.dp)
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding((config.elevationDp * 2).dp)
                .dropShadow(
                    shape = shape,
                    shadow = Shadow(
                        radius = (config.elevationDp * 2).dp,
                        color = Color.Black,
                        alpha = if (isSystemInDarkTheme()) 0.2f else 0.1f,
                    ),
                )
                .floatingGlassSurface(
                    backdrop = backdrop,
                    shape = shape,
                    containerColor = containerColor,
                    blurRadius = config.blurRadiusDp.dp,
                    highlight = highlight,
                ),
        )
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var blurRadius by remember { mutableIntStateOf(blurRadiusDp) }
            var dynamicHighlight by remember { mutableStateOf(dynamicGravityHighlight) }
            var corner by remember { mutableIntStateOf(cornerRadiusDp) }
            var side by remember { mutableIntStateOf(sideMarginDp) }
            var topGap by remember { mutableIntStateOf(topGapDp) }
            var elevation by remember { mutableIntStateOf(elevationDp) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_floating_main_header_name)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.main_floating_header_summary),
                                    description = null,
                                )
                            }
                            item(key = "dynamic_gravity_highlight") {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.nav_dynamic_gravity_highlight),
                                    description = stringResource(R.string.nav_dynamic_gravity_highlight_summary),
                                    checked = dynamicHighlight,
                                    onCheckedChange = {
                                        dynamicHighlight = it
                                        dynamicGravityHighlight = it
                                        scheduleAllSessions()
                                    },
                                )
                            }
                            item(key = "blur_radius") {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.nav_blur_radius),
                                        value = blurRadius,
                                        startInt = MIN_BLUR_RADIUS,
                                        endInt = MAX_BLUR_RADIUS,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            blurRadius = it
                                            blurRadiusDp = it
                                            scheduleAllSessions()
                                        },
                                    )
                                }
                            }
                            item(key = "corner_radius") {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.chat_floating_corner_radius_label),
                                        value = corner,
                                        startInt = MIN_CORNER_RADIUS,
                                        endInt = MAX_CORNER_RADIUS,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            corner = it
                                            cornerRadiusDp = it
                                            scheduleAllSessions()
                                        },
                                    )
                                }
                            }
                            item(key = "side_margin") {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.chat_floating_side_margin_label),
                                        value = side,
                                        startInt = MIN_SIDE_MARGIN,
                                        endInt = MAX_SIDE_MARGIN,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            side = it
                                            sideMarginDp = it
                                            scheduleAllSessions()
                                        },
                                    )
                                }
                            }
                            item(key = "top_gap") {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.chat_floating_top_gap_label),
                                        value = topGap,
                                        startInt = MIN_TOP_GAP,
                                        endInt = MAX_TOP_GAP,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            topGap = it
                                            topGapDp = it
                                            scheduleAllSessions()
                                        },
                                    )
                                }
                            }
                            item(key = "elevation") {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.chat_floating_elevation_label),
                                        value = elevation,
                                        startInt = MIN_ELEVATION,
                                        endInt = MAX_ELEVATION,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            elevation = it
                                            elevationDp = it
                                            scheduleAllSessions()
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
