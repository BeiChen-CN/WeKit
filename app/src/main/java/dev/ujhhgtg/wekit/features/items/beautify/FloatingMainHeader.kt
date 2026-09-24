package dev.ujhhgtg.wekit.features.items.beautify

import android.animation.StateListAnimator
import android.app.Activity
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.mogic.WxViewPager
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
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
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Gives the shared LauncherUI action bar a floating glass surface on the first three tabs. */
object FloatingMainHeader : ClickableFeature() {

    override val technicalId = "主页悬浮顶栏"
    override val nameRes = R.string.feature_floating_main_header_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_floating_main_header_description

    private const val TAG = "FloatingMainHeader"
    private const val ACTION_BAR_CONTAINER_CLASS = "androidx.appcompat.widget.ActionBarContainer"
    private const val ACTION_BAR_OVERLAY_LAYOUT_CLASS =
        "androidx.appcompat.widget.ActionBarOverlayLayout"
    private const val TOOLBAR_CLASS = "androidx.appcompat.widget.Toolbar"

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
    private var hideSearchOnHome by prefOption("floating_main_header_hide_search_on_home", false)
    private var hidePlusOnHome by prefOption("floating_main_header_hide_plus_on_home", false)

    private data class GlassConfig(
        val blurRadiusDp: Int,
        val dynamicGravityHighlight: Boolean,
        val cornerRadiusDp: Int,
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
        val originalBackground: Drawable?,
        val installedBackground: Drawable,
        val originalOutlineProvider: ViewOutlineProvider?,
        val originalClipToOutline: Boolean,
        val originalElevation: Float,
        val originalTranslationZ: Float,
        val originalStateListAnimator: StateListAnimator?,
        val originalMargins: IntArray,
        val overlayLayout: View,
        val layoutListener: View.OnLayoutChangeListener,
        val attachListener: View.OnAttachStateChangeListener,
        var installedOutlineProvider: ViewOutlineProvider? = null,
        var geometry: HeaderGeometry? = null,
    )

    private val sessions = WeakHashMap<WxViewPager, WeakReference<MainHeaderSession>>()
    private val hostOverlayModes = WeakHashMap<View, Boolean>()
    private val ownOverlayWrite = ThreadLocal.withInitial { false }

    override fun onEnable() {
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
        LauncherUI::class.reflekt().firstMethod {
            name = "onCreateOptionsMenu"
            parameters(Menu::class)
        }.hookAfter {
            sessionFor(thisObject as Activity)?.onMenuCreated(args[0] as Menu)
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
        private var optionsMenu: Menu? = null
        private var searchItemId: Int? = null
        private var plusItemId: Int? = null
        private val hiddenMenuItems = linkedMapOf<MenuItem, Boolean>()

        private val syncRunnable = Runnable {
            syncPosted = false
            if (attached) sync()
        }
        private val decorLayoutListener = View.OnLayoutChangeListener {
                _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                scheduleSync()
            }
        }

        fun ownsActivity(candidate: Activity): Boolean = activity === candidate

        fun onMenuCreated(menu: Menu) {
            restoreMenuItems()
            optionsMenu = menu
            if (menu.size() != 2) {
                searchItemId = null
                plusItemId = null
                WeLogger.e(TAG, "expected two LauncherUI menu items, found ${menu.size()}")
                return
            }
            searchItemId = menu.getItem(0).itemId
            plusItemId = menu.getItem(1).itemId
            scheduleSync()
        }

        fun onHostOverlayModeChanged(overlay: View, requested: Boolean) {
            if (headerState?.overlayLayout !== overlay || requested) return
            if ((activity as LauncherUI).currentFragmet == null) {
                setOverlayModeByWeKit(overlay, true)
            }
        }

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
                scheduleSync()
            }
            decorRoot.addOnLayoutChangeListener(decorLayoutListener)
            scheduleSync()
        }

        fun detach() {
            if (!attached) return
            attached = false
            tabSelectionHook?.unhook()
            tabSelectionHook = null
            decorRoot.removeCallbacks(syncRunnable)
            decorRoot.removeOnLayoutChangeListener(decorLayoutListener)
            pendingTransitionLayoutListener?.let(transitionHost::removeOnLayoutChangeListener)
            pendingTransitionLayoutListener = null
            removeHeaderState()
            optionsMenu = null
            searchItemId = null
            plusItemId = null
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
            if (current != null && !isValidHeader(current.header)) {
                removeHeaderState()
            }

            val state = headerState ?: createHeaderState(findMainHeader() ?: return) ?: return
            val height = measuredHeaderHeight(state.header)
            if (!isSafeHeaderHeight(state.header, height)) {
                removeHeaderState()
                return
            }

            val params = state.layer.layoutParams as FrameLayout.LayoutParams
            if (params.height != height) {
                params.height = height
                state.layer.layoutParams = params
            }
            state.glassConfig.value = currentGlassConfig()
            applyGeometry(state, currentGeometry())
            syncHomeMenuItems(selectedTabIndex == 0)
        }

        private fun syncHomeMenuItems(isHome: Boolean) {
            val menu = optionsMenu ?: return
            setMenuItemHidden(menu, searchItemId, isHome && hideSearchOnHome)
            setMenuItemHidden(menu, plusItemId, isHome && hidePlusOnHome)
        }

        private fun setMenuItemHidden(menu: Menu, itemId: Int?, hide: Boolean) {
            if (itemId == null) return
            val item = menu.findItem(itemId) ?: return
            if (hide) {
                hiddenMenuItems.putIfAbsent(item, item.isVisible)
                item.isVisible = false
            } else {
                hiddenMenuItems.remove(item)?.let { item.isVisible = it }
            }
        }

        private fun restoreMenuItems() {
            hiddenMenuItems.forEach { (item, visible) -> item.isVisible = visible }
            hiddenMenuItems.clear()
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

            val configState = mutableStateOf(currentGlassConfig())
            val layer = ComposeView(header.context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isClickable = false
                isFocusable = false
                setLifecycleOwner(lifecycleOwner)
                setContent {
                    InjectedUiTheme {
                        val config by configState
                        MainHeaderGlass(viewPager, lifecycleOwner, config)
                    }
                }
            }
            val transparentBackground = ColorDrawable(android.graphics.Color.TRANSPARENT)
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
                originalBackground = header.background,
                installedBackground = transparentBackground,
                originalOutlineProvider = header.outlineProvider,
                originalClipToOutline = header.clipToOutline,
                originalElevation = header.elevation,
                originalTranslationZ = header.translationZ,
                originalStateListAnimator = header.stateListAnimator,
                originalMargins = intArrayOf(
                    margins.leftMargin,
                    margins.topMargin,
                    margins.rightMargin,
                    margins.bottomMargin,
                ),
                overlayLayout = overlayLayout,
                layoutListener = layoutListener,
                attachListener = attachListener,
            )
            headerState = state
            setOverlayModeByWeKit(overlayLayout, true)
            header.background = transparentBackground
            header.addOnLayoutChangeListener(layoutListener)
            header.addOnAttachStateChangeListener(attachListener)
            header.addView(
                layer,
                0,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height),
            )
            applyGeometry(state, currentGeometry())
            return state
        }

        private fun applyGeometry(state: HeaderState, geometry: HeaderGeometry) {
            if (state.geometry == geometry) return
            val header = state.header
            val density = header.resources.displayMetrics.density
            val margins = header.layoutParams as ViewGroup.MarginLayoutParams
            val side = (geometry.sideMarginDp * density).toInt()
            val top = (geometry.topGapDp * density).toInt()
            margins.leftMargin = state.originalMargins[0] + side
            margins.topMargin = state.originalMargins[1] + top
            margins.rightMargin = state.originalMargins[2] + side
            margins.bottomMargin = state.originalMargins[3]
            header.layoutParams = margins

            val outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val radius = geometry.cornerRadiusDp * view.resources.displayMetrics.density
                    outline.setRoundRect(0, 0, view.width, view.height.coerceAtLeast(1), radius)
                }
            }
            state.installedOutlineProvider = outlineProvider
            header.outlineProvider = outlineProvider
            header.clipToOutline = true
            header.stateListAnimator = null
            header.translationZ = 0f
            header.elevation = geometry.elevationDp * density
            header.invalidateOutline()
            state.geometry = geometry
        }

        private fun removeHeaderState() {
            restoreMenuItems()
            val state = headerState ?: return
            headerState = null
            val header = state.header
            header.removeOnLayoutChangeListener(state.layoutListener)
            header.removeOnAttachStateChangeListener(state.attachListener)
            if (state.layer.parent === header) header.removeView(state.layer)
            state.layer.disposeComposition()

            if (header.background === state.installedBackground) {
                header.background = state.originalBackground
            }
            if (header.outlineProvider === state.installedOutlineProvider) {
                header.outlineProvider = state.originalOutlineProvider
            }
            header.clipToOutline = state.originalClipToOutline
            header.elevation = state.originalElevation
            header.translationZ = state.originalTranslationZ
            header.stateListAnimator = state.originalStateListAnimator
            val margins = header.layoutParams as ViewGroup.MarginLayoutParams
            margins.leftMargin = state.originalMargins[0]
            margins.topMargin = state.originalMargins[1]
            margins.rightMargin = state.originalMargins[2]
            margins.bottomMargin = state.originalMargins[3]
            header.layoutParams = margins

            // The host may have changed its requested mode while the card was active.
            setOverlayModeByWeKit(state.overlayLayout, hostOverlayModes[state.overlayLayout] ?: false)
        }

        private fun findOverlayLayout(header: View): View {
            var ancestor = header.parent
            while (ancestor is View) {
                if (ancestor.javaClass.name == ACTION_BAR_OVERLAY_LAYOUT_CLASS) return ancestor
                ancestor = ancestor.parent
            }
            error("$ACTION_BAR_OVERLAY_LAYOUT_CLASS not found above $ACTION_BAR_CONTAINER_CLASS")
        }

        private fun View.isToolbar(): Boolean {
            var type: Class<*>? = javaClass
            while (type != null) {
                if (type.name == TOOLBAR_CLASS) return true
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
            var hideSearch by remember { mutableStateOf(hideSearchOnHome) }
            var hidePlus by remember { mutableStateOf(hidePlusOnHome) }

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
                            item(key = "hide_search_on_home") {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.main_floating_header_hide_search),
                                    checked = hideSearch,
                                    onCheckedChange = {
                                        hideSearch = it
                                        hideSearchOnHome = it
                                        scheduleAllSessions()
                                    },
                                )
                            }
                            item(key = "hide_plus_on_home") {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.main_floating_header_hide_plus),
                                    checked = hidePlus,
                                    onCheckedChange = {
                                        hidePlus = it
                                        hidePlusOnHome = it
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
