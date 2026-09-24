package dev.ujhhgtg.wekit.features.items.beautify

import android.app.Activity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.mogic.WxViewPager
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel.HomeSidePanelPreferenceKeys
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.allViews
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookHandle
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Controls WeChat's native LauncherUI title and menu independently of floating glass. */
object HomeHeaderControls : ClickableFeature() {

    override val technicalId = "主页原生顶栏控件"
    override val nameRes = R.string.feature_home_header_controls_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_home_header_controls_description
    override val alwaysEnabled = true
    override val noSwitchWidget = true

    private const val TAG = "HomeHeaderControls"
    private const val ACTION_BAR_CONTAINER_CLASS = "androidx.appcompat.widget.ActionBarContainer"
    private const val TOOLBAR_CLASS = "androidx.appcompat.widget.Toolbar"

    // Keep the previous preference keys so existing user choices survive the move out of
    // FloatingMainHeader and HomeSidePanel.
    private var hideTitle by prefOption(HomeSidePanelPreferenceKeys.HIDE_WECHAT_TITLE, false)
    private var hideSearch by prefOption("floating_main_header_hide_search_on_home", false)
    private var hidePlus by prefOption("floating_main_header_hide_plus_on_home", false)

    private val sessions = WeakHashMap<WxViewPager, WeakReference<Session>>()

    override fun onEnable() {
        LauncherUI::class.reflekt().firstMethod {
            name = "onCreateOptionsMenu"
            parameters(Menu::class)
        }.hookAfter {
            sessionFor(thisObject as Activity)?.onMenuCreated(args[0] as Menu)
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "onResume"
            parameters()
        }.hookAfter {
            sessionFor(thisObject as Activity)?.scheduleSync()
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "startChatting"
            parameters(String::class, android.os.Bundle::class, Boolean::class)
        }.hookBefore {
            sessionFor(thisObject as Activity)?.restoreHostControls()
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "startChatting"
            parameters(String::class, android.os.Bundle::class, Boolean::class)
        }.hookAfter {
            sessionFor(thisObject as Activity)?.scheduleSync()
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

            val session = Session(activity, viewPager, tabsAdapter).also { it.attach() }
            sessions[viewPager] = WeakReference(session)
        }
    }

    override fun onDisable() {
        sessions.values.mapNotNull { it.get() }.forEach(Session::detach)
        sessions.clear()
    }

    private fun sessionFor(activity: Activity): Session? =
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
        sessions.values.mapNotNull { it.get() }.forEach(Session::scheduleSync)
    }

    private class Session(
        private val activity: Activity,
        private val viewPager: WxViewPager,
        private val tabsAdapter: Any,
    ) {
        private val decorRoot = activity.window.decorView as ViewGroup
        private var attached = false
        private var syncPosted = false
        private var selectedTabIndex = viewPager.currentItem
        private var tabSelectionHook: HookHandle? = null
        private var observedHeader: ViewGroup? = null
        private var optionsMenu: Menu? = null
        private var searchItemId: Int? = null
        private var plusItemId: Int? = null
        private var missingTitleLogged = false
        private val titleVisibilities = linkedMapOf<TextView, Int>()
        private val menuVisibilities = linkedMapOf<MenuItem, Boolean>()
        private val syncRunnable = Runnable {
            syncPosted = false
            if (attached) sync()
        }
        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scheduleSync()
        }

        fun ownsActivity(candidate: Activity) = activity === candidate

        fun attach() {
            if (attached) return
            attached = true
            tabSelectionHook = tabsAdapter.reflekt().firstMethod {
                name = "onPageSelected"
                parameterCount = 1
            }.hookBeforeDirectly {
                if (thisObject !== tabsAdapter) return@hookBeforeDirectly
                // ReplaceNavigationBar maps reordered pager indices at priority 100.
                selectedTabIndex = args[0] as Int
                scheduleSync()
            }
            decorRoot.addOnLayoutChangeListener(layoutListener)
            scheduleSync()
        }

        fun detach() {
            if (!attached) return
            attached = false
            tabSelectionHook?.unhook()
            tabSelectionHook = null
            decorRoot.removeCallbacks(syncRunnable)
            decorRoot.removeOnLayoutChangeListener(layoutListener)
            observedHeader?.removeOnLayoutChangeListener(layoutListener)
            observedHeader = null
            restoreHostControls()
            optionsMenu = null
            searchItemId = null
            plusItemId = null
        }

        fun scheduleSync() {
            if (!attached || syncPosted) return
            syncPosted = true
            decorRoot.postOnAnimation(syncRunnable)
        }

        fun onMenuCreated(menu: Menu) {
            restoreMenuItems()
            optionsMenu = menu
            if (menu.size() != 2) {
                searchItemId = null
                plusItemId = null
                if (hideSearch || hidePlus) {
                    WeLogger.e(TAG, "expected two LauncherUI menu items, found ${menu.size()}")
                }
                return
            }
            searchItemId = menu.getItem(0).itemId
            plusItemId = menu.getItem(1).itemId
            scheduleSync()
        }

        fun restoreHostControls() {
            restoreTitles()
            restoreMenuItems()
        }

        private fun sync() {
            val isHome = selectedTabIndex == 0 && (activity as LauncherUI).currentFragmet == null
            if (!isHome) {
                observeHeader(null)
                restoreHostControls()
                return
            }
            val header = if (hideTitle || hideSearch || hidePlus) findMainHeader() else null
            observeHeader(header)
            syncTitle(header)
            syncMenuItems()
        }

        private fun observeHeader(header: ViewGroup?) {
            if (observedHeader === header) return
            observedHeader?.removeOnLayoutChangeListener(layoutListener)
            observedHeader = header
            header?.addOnLayoutChangeListener(layoutListener)
        }

        private fun syncTitle(header: ViewGroup?) {
            if (!hideTitle) {
                restoreTitles()
                return
            }
            if (header == null) {
                restoreTitles()
                return
            }
            val titles = header.allViews.filterIsInstance<TextView>()
                .filter { it.id == android.R.id.title || it.id == android.R.id.text1 }
                .toSet()
            if (titles.isEmpty() && !missingTitleLogged) {
                missingTitleLogged = true
                WeLogger.e(TAG, "native LauncherUI title not found in action bar")
            }
            val iterator = titleVisibilities.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in titles) {
                    entry.key.visibility = entry.value
                    iterator.remove()
                }
            }
            titles.forEach { title ->
                titleVisibilities.putIfAbsent(title, title.visibility)
                if (title.visibility != View.GONE) title.visibility = View.GONE
            }
        }

        private fun findMainHeader(): ViewGroup? = decorRoot.allViews
            .filterIsInstance<ViewGroup>()
            .filter { it.javaClass.name == ACTION_BAR_CONTAINER_CLASS }
            .filter { candidate -> candidate.allViews.any { it !== candidate && it.isToolbar() } }
            .minByOrNull { candidate -> IntArray(2).also(candidate::getLocationInWindow)[1] }

        private fun View.isToolbar(): Boolean {
            var type: Class<*>? = javaClass
            while (type != null) {
                if (type.name == TOOLBAR_CLASS) return true
                type = type.superclass
            }
            return false
        }

        private fun restoreTitles() {
            titleVisibilities.forEach { (title, visibility) -> title.visibility = visibility }
            titleVisibilities.clear()
        }

        private fun syncMenuItems() {
            val menu = optionsMenu ?: return
            setMenuItemHidden(menu, searchItemId, hideSearch)
            setMenuItemHidden(menu, plusItemId, hidePlus)
        }

        private fun setMenuItemHidden(menu: Menu, itemId: Int?, hide: Boolean) {
            if (itemId == null) return
            val item = menu.findItem(itemId) ?: return
            if (hide) {
                menuVisibilities.putIfAbsent(item, item.isVisible)
                if (item.isVisible) item.isVisible = false
            } else {
                menuVisibilities.remove(item)?.let { item.isVisible = it }
            }
        }

        private fun restoreMenuItems() {
            menuVisibilities.forEach { (item, visible) -> item.isVisible = visible }
            menuVisibilities.clear()
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var title by remember { mutableStateOf(hideTitle) }
            var search by remember { mutableStateOf(hideSearch) }
            var plus by remember { mutableStateOf(hidePlus) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_home_header_controls_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item(key = "hide_title") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.home_header_controls_hide_title),
                                checked = title,
                                onCheckedChange = {
                                    title = it
                                    hideTitle = it
                                    scheduleAllSessions()
                                },
                            )
                        }
                        item(key = "hide_search") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.home_header_controls_hide_search),
                                checked = search,
                                onCheckedChange = {
                                    search = it
                                    hideSearch = it
                                    scheduleAllSessions()
                                },
                            )
                        }
                        item(key = "hide_plus") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.home_header_controls_hide_plus),
                                checked = plus,
                                onCheckedChange = {
                                    plus = it
                                    hidePlus = it
                                    scheduleAllSessions()
                                },
                            )
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
