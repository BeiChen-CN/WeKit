package dev.ujhhgtg.wekit.features.items.beautify.floating_main_header

import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.AbsListView
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.mogic.WxViewPager
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.items.beautify.FloatingMainHeader
import dev.ujhhgtg.wekit.features.items.beautify.ReplaceNavigationBar
import dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel.HomeSidePanel
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.allViews
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.theme.InjectedUiTheme
import dev.ujhhgtg.wekit.utils.HookHandle
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import kotlin.math.roundToInt

/** Owns one launcher window; every host mutation has a corresponding restoration snapshot. */
class MainHeaderSession(
    val activity: LauncherUI,
    private val pager: WxViewPager,
    private val adapter: Any,
) {
    private val decor = activity.window.decorView as FrameLayout
    private val lifecycleOwner = LifecycleOwnerProvider.getOrCreate(activity)
    private var attached = false
    private var syncPosted = false
    private var suspendedForChat = false
    private var failed = false
    private var layoutConfiguration = Configuration(activity.resources.configuration)
    private var selectedTab = ReplaceNavigationBar.logicalTabIndex(pager.currentItem)
    private var selectionHook: HookHandle? = null
    private var replacement: Replacement? = null
    val hostView: View? get() = replacement?.surface

    private val syncRunnable = Runnable {
        syncPosted = false
        if (attached && !failed) {
            try {
                sync()
            } catch (error: Exception) {
                failed = true
                restore()
                WeLogger.e("FloatingMainHeader", "Cannot replace launcher header; restored native bar", error)
            }
        }
    }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { scheduleSync() }
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        val state = replacement
        if (state != null) {
            if (activity.currentFragmet != null || selectedTab == 3 || !pager.isShown) {
                restore()
            } else {
                // Host theme/menu updates can reinstall native chrome between layout passes.
                // Remove it before the frame is presented, including the window shadow.
                try {
                    state.suppressNativeChrome()
                } catch (error: Exception) {
                    failed = true
                    restore()
                    WeLogger.e("FloatingMainHeader", "Cannot suppress native header chrome", error)
                }
            }
        }
        true
    }

    fun attach() {
        selectionHook = adapter.reflekt().firstMethod {
            name = "onPageSelected"
            parameterCount = 1
        }.hookBeforeDirectly {
            if (thisObject !== adapter) return@hookBeforeDirectly
            // The bottom bar maps its physical order to logical WeChat indices at priority 100.
            selectedTab = args[0] as Int
            if (selectedTab == 3) restore()
            scheduleSync()
        }
        FloatingMainHeader.registerUnhook(selectionHook!!)
        attached = true
        decor.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        decor.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        scheduleSync()
    }

    fun detach() {
        attached = false
        selectionHook?.let { if (FloatingMainHeader.unhooks.remove(it)) it.unhook() }
        selectionHook = null
        decor.removeCallbacks(syncRunnable)
        syncPosted = false
        if (decor.viewTreeObserver.isAlive) {
            decor.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            decor.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        }
        restore()
    }

    fun scheduleSync() {
        if (!attached || failed || syncPosted) return
        syncPosted = true
        decor.postOnAnimation(syncRunnable)
    }

    fun suspendForChat() {
        suspendedForChat = true
        restore()
    }

    fun resumeAfterChat() {
        suspendedForChat = false
        scheduleSync()
    }

    fun onOverlayModeChanged(view: View, requested: Boolean) {
        val state = replacement ?: return
        if (state.overlay !== view) return
        state.hostOverlayMode = requested
        if (!requested) FloatingMainHeader.setOverlayMode(view, true)
    }

    fun contentInsets(view: View, insets: Rect): Rect {
        val state = replacement ?: return insets
        if (view !== state.content || suspendedForChat || selectedTab == 3 || activity.currentFragmet != null) return insets
        val base = FloatingMainHeader.baseInnerInsets(state.overlay)
        if (base !is Rect) return insets
        return Rect(insets).apply { top = (top - state.height).coerceAtLeast(base.top) }
    }

    @Suppress("DEPRECATION")
    fun contentInsets(view: View, insets: WindowInsets): WindowInsets {
        val state = replacement ?: return insets
        if (view !== state.content || suspendedForChat || selectedTab == 3 || activity.currentFragmet != null) return insets
        val base = FloatingMainHeader.baseInnerInsets(state.overlay)
        if (base is Rect) return insets // Rect hosts consume the synthetic inset in fitSystemWindows.
        val baseTop = base.reflekt().firstMethod { name = "getSystemWindowInsetTop"; parameters() }.invoke() as Int
        val top = (insets.systemWindowInsetTop - state.height).coerceAtLeast(baseTop)
        if (top >= insets.systemWindowInsetTop) return insets
        return insets.replaceSystemWindowInsets(
            insets.systemWindowInsetLeft, top, insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
        )
    }

    private fun sync() {
        val configuration = activity.resources.configuration
        if (layoutConfiguration.orientation != configuration.orientation ||
            layoutConfiguration.densityDpi != configuration.densityDpi ||
            layoutConfiguration.fontScale != configuration.fontScale ||
            layoutConfiguration.screenWidthDp != configuration.screenWidthDp ||
            layoutConfiguration.screenHeightDp != configuration.screenHeightDp
        ) {
            layoutConfiguration = Configuration(configuration)
            restore()
            // Let the native toolbar measure under the new window configuration first.
            scheduleSync()
            return
        }
        if (suspendedForChat || selectedTab == 3 || activity.currentFragmet != null || !pager.isShown) {
            restore()
            return
        }
        replacement?.let {
            if (!it.header.isAttachedToWindow || it.toolbar.parent !== it.surface) restore()
        }
        val state = replacement ?: install() ?: return
        state.config.value = FloatingMainHeader.appearance()
        state.updateGeometry()
        state.updatePage()
        state.suppressNativeChrome()
    }

    private fun install(): Replacement? {
        val overlay = generateSequence(pager.parent as? View) { it.parent as? View }
            .firstOrNull { it.javaClass.name == FloatingMainHeader.ACTION_BAR_OVERLAY_LAYOUT_CLASS }
            as? ViewGroup ?: error("Launcher pager has no ActionBarOverlayLayout")
        val header = (0 until overlay.childCount).map(overlay::getChildAt)
            .filterIsInstance<ViewGroup>()
            .single { candidate ->
                candidate.javaClass.name == FloatingMainHeader.ACTION_BAR_CONTAINER_CLASS &&
                    candidate.allViews.any { it.hasHostType(FloatingMainHeader.TOOLBAR_CLASS) }
            }
        if (header.visibility != View.VISIBLE || header.height <= 0 || decor.width <= 0) return null
        check(header.height < decor.height) { "ActionBar occupies the entire window" }
        val toolbar = header.allViews.filterIsInstance<ViewGroup>()
            .single { it.hasHostType(FloatingMainHeader.TOOLBAR_CLASS) }
        val content = (0 until overlay.childCount).map(overlay::getChildAt)
            .single { it.javaClass.name == FloatingMainHeader.CONTENT_FRAME_CLASS }
        val state = Replacement(header, toolbar, overlay, content)
        replacement = state // Publish before mutations so a failed install can restore everything.
        state.install()
        return state
    }

    private fun restore() {
        val state = replacement ?: return
        replacement = null // Insets/overlay hooks must already see the native path while restoring.
        try {
            state.restore()
        } finally {
            HomeSidePanel.onExternalChromeChanged(activity)
        }
    }

    private inner class Replacement(
        val header: ViewGroup,
        val toolbar: ViewGroup,
        val overlay: ViewGroup,
        val content: View,
    ) {
        val height = header.height
        var hostOverlayMode = FloatingMainHeader.overlayMode(overlay)
        private var contentShadow = FloatingMainHeader.contentShadow(overlay)
        private val originalVisibility = header.visibility
        private val toolbarParent = toolbar.parent as ViewGroup
        private val toolbarIndex = toolbarParent.indexOfChild(toolbar)
        private val toolbarParams = toolbar.layoutParams
        private val initialTop = IntArray(2).also(header::getLocationInWindow)[1]
        private val density = header.resources.displayMetrics.density
        val config = mutableStateOf(FloatingMainHeader.appearance())
        private val source = mutableStateOf<View>(pager)
        val surface = MainHeaderSurface(header.context).apply { setLifecycleOwner(lifecycleOwner) }
        private val glass = ComposeView(header.context).apply {
            setLifecycleOwner(lifecycleOwner)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setContent { InjectedUiTheme { MainHeaderGlass(source.value, lifecycleOwner, config.value) } }
        }
        private val visuals = linkedMapOf<View, NativeVisual>()
        private val frosted = linkedMapOf<View, FrostedTop>()
        private val lists = linkedMapOf<ViewGroup, ListPadding>()

        fun install() {
            surface.addView(glass, FrameLayout.LayoutParams(-1, -1))
            toolbarParent.removeView(toolbar)
            surface.addView(toolbar, FrameLayout.LayoutParams(-1, -1))
            // It is a window sibling, never a child of the captured pager or native container.
            decor.addView(surface, FrameLayout.LayoutParams(-1, height, Gravity.TOP or Gravity.LEFT))
            updateGeometry()
            updatePage()
            suppressNativeChrome()
            FloatingMainHeader.setOverlayMode(overlay, true)
            FloatingMainHeader.requestContentInsets(overlay)
            HomeSidePanel.onExternalChromeChanged(activity)
        }

        @Suppress("DEPRECATION")
        fun updateGeometry() {
            val appearance = config.value
            val decorLocation = IntArray(2).also(decor::getLocationInWindow)
            val insets = decor.rootWindowInsets
            val cutout = insets?.displayCutout
            val leftInset = maxOf(insets?.systemWindowInsetLeft ?: 0, cutout?.safeInsetLeft ?: 0)
            val rightInset = maxOf(insets?.systemWindowInsetRight ?: 0, cutout?.safeInsetRight ?: 0)
            val topInset = maxOf(insets?.systemWindowInsetTop ?: initialTop, cutout?.safeInsetTop ?: 0)
            val side = (appearance.sideMargin * density).roundToInt()
            val top = (topInset - decorLocation[1]).coerceAtLeast(0) + (appearance.topGap * density).roundToInt()
            val width = (decor.width - leftInset - rightInset - side * 2).coerceAtLeast(1)
            val params = surface.layoutParams as FrameLayout.LayoutParams
            if (params.width != width || params.height != height || params.leftMargin != leftInset + side || params.topMargin != top) {
                params.width = width
                params.height = height
                params.leftMargin = leftInset + side
                params.topMargin = top
                surface.layoutParams = params
            }
            surface.radius = appearance.cornerRadius * density
        }

        fun updatePage() {
            // Background-only containers are cleared; icon/text/button drawables and ripples stay intact.
            toolbar.allViews.filterIsInstance<ViewGroup>().forEach { view ->
                visuals.getOrPut(view) { NativeVisual(view) }
            }
            content.allViews.filter { it.javaClass.name == FloatingMainHeader.FROSTED_CONTENT_CLASS }.forEach { view ->
                frosted.getOrPut(view) { FrostedTop(view) }
            }
            val pagerRect = Rect()
            if (!pager.getGlobalVisibleRect(pagerRect)) return
            val list = pager.allViews.filterIsInstance<ViewGroup>().filter { view ->
                (view is AbsListView || view.hasHostType("androidx.recyclerview.widget.RecyclerView")) &&
                    view.isShown && Rect().let { rect ->
                        view.getGlobalVisibleRect(rect) && rect.contains(pagerRect.centerX(), rect.centerY())
                    }
            }.maxByOrNull { it.width.toLong() * it.height }
            source.value = list ?: pager
            // The side panel animates the entire page; do not turn that transform into list padding.
            if (surface.scaleX != 1f || surface.translationX != 0f || surface.translationY != 0f) return
            if (list != null) {
                val padding = lists.getOrPut(list) { ListPadding(list) }
                val listTop = IntArray(2).also(list::getLocationInWindow)[1]
                val decorTop = IntArray(2).also(decor::getLocationInWindow)[1]
                val cardBottom = decorTop + (surface.layoutParams as FrameLayout.LayoutParams).topMargin + height
                // Reserve space inside the scrolling viewport, not as an opaque view above it.
                padding.apply((cardBottom - listTop + (8 * density).roundToInt()).coerceAtLeast(0))
            }
        }

        fun suppressNativeChrome() {
            if (header.visibility != View.GONE) header.visibility = View.GONE
            visuals.values.forEach(NativeVisual::clear)
            frosted.values.forEach(FrostedTop::clear)
            FloatingMainHeader.contentShadow(overlay)?.let {
                contentShadow = it
                FloatingMainHeader.setContentShadow(overlay, null)
            }
        }

        fun restore() {
            var failure: Exception? = null
            fun restorePart(action: () -> Unit) {
                try {
                    action()
                } catch (error: Exception) {
                    val first = failure
                    if (first == null) failure = error else first.addSuppressed(error)
                }
            }
            // A failed optional visual restoration must not leave the native header hidden.
            restorePart {
                (toolbar.parent as? ViewGroup)?.removeView(toolbar)
                toolbarParent.addView(toolbar, toolbarIndex.coerceAtMost(toolbarParent.childCount), toolbarParams)
            }
            restorePart { header.visibility = originalVisibility }
            restorePart { decor.removeView(surface) }
            restorePart { glass.disposeComposition() }
            visuals.values.forEach { restorePart(it::restore) }
            frosted.values.forEach { restorePart(it::restore) }
            lists.values.forEach { restorePart(it::restore) }
            restorePart {
                if (FloatingMainHeader.contentShadow(overlay) == null) {
                    FloatingMainHeader.setContentShadow(overlay, contentShadow)
                }
            }
            restorePart { FloatingMainHeader.setOverlayMode(overlay, hostOverlayMode) }
            restorePart { FloatingMainHeader.requestContentInsets(overlay) }
            failure?.let { WeLogger.e("FloatingMainHeader", "Native header restoration was incomplete", it) }
        }
    }

    private class NativeVisual(private val view: View) {
        private val transparent = ColorDrawable(android.graphics.Color.TRANSPARENT)
        private var background = view.background
        private var foreground = view.foreground
        private var elevation = view.elevation
        private var translationZ = view.translationZ
        private var animator = view.stateListAnimator
        fun clear() {
            if (view.background !== transparent) { background = view.background; view.background = transparent }
            if (view.foreground != null) { foreground = view.foreground; view.foreground = null }
            if (view.stateListAnimator != null) { animator = view.stateListAnimator; view.stateListAnimator = null }
            if (view.elevation != 0f) { elevation = view.elevation; view.elevation = 0f }
            if (view.translationZ != 0f) { translationZ = view.translationZ; view.translationZ = 0f }
        }
        fun restore() {
            if (view.background === transparent) view.background = background
            if (view.foreground == null) view.foreground = foreground
            if (view.stateListAnimator == null) view.stateListAnimator = animator
            if (view.elevation == 0f) view.elevation = elevation
            if (view.translationZ == 0f) view.translationZ = translationZ
        }
    }

    private class FrostedTop(private val view: View) {
        private val getter = view.reflekt().firstMethod { name = "getTopBlurAreaHeight"; parameters() }
        private val setter = view.reflekt().firstMethod { name = "setTopBlurAreaHeight"; parameters(Int::class) }
        private var height = getter.invoke() as Int
        fun clear() {
            val current = getter.invoke() as Int
            if (current != 0) { height = current; setter.invoke(0); view.invalidate() }
        }
        fun restore() {
            if (getter.invoke() as Int == 0) { setter.invoke(height); view.invalidate() }
        }
    }

    private class ListPadding(private val view: ViewGroup) {
        private val top = view.paddingTop
        private val clip = view.clipToPadding
        private var installed = top
        fun apply(overlap: Int) {
            val target = maxOf(top, overlap)
            if (view.paddingTop != target) view.setPadding(view.paddingLeft, target, view.paddingRight, view.paddingBottom)
            installed = target
            if (view.clipToPadding) view.clipToPadding = false
        }
        fun restore() {
            if (view.paddingTop == installed) view.setPadding(view.paddingLeft, top, view.paddingRight, view.paddingBottom)
            view.clipToPadding = clip
        }
    }
}

private fun View.hasHostType(name: String): Boolean =
    generateSequence<Class<*>>(javaClass) { it.superclass }.any { it.name == name }
