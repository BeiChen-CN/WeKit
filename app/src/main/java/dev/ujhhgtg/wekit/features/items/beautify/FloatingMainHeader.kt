package dev.ujhhgtg.wekit.features.items.beautify

import android.app.Activity
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
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
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.beautify.floating_main_header.MainHeaderAppearance
import dev.ujhhgtg.wekit.features.items.beautify.floating_main_header.MainHeaderSession
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.IntNumberPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.allViews
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldUsingType
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Replaces the main window bar with one clipped glass surface and the live host controls. */
object FloatingMainHeader : ClickableFeature(), IResolveDex {
    override val technicalId = "主页悬浮顶栏"
    override val nameRes = R.string.feature_floating_main_header_name
    override val descriptionRes = R.string.feature_floating_main_header_description
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)

    const val ACTION_BAR_CONTAINER_CLASS = "androidx.appcompat.widget.ActionBarContainer"
    const val ACTION_BAR_OVERLAY_LAYOUT_CLASS = "androidx.appcompat.widget.ActionBarOverlayLayout"
    const val TOOLBAR_CLASS = "androidx.appcompat.widget.Toolbar"
    const val CONTENT_FRAME_CLASS = "androidx.appcompat.widget.ContentFrameLayout"
    const val FROSTED_CONTENT_CLASS = "com.tencent.mm.ui.FrostedContentView"

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

    private var blurRadius by prefOption("floating_main_header_blur_radius", 8)
    private var cornerRadius by prefOption("floating_main_header_corner_radius", 24)
    private var sideMargin by prefOption("floating_main_header_side_margin", 12)
    private var topGap by prefOption("floating_main_header_top_gap", 4)
    private var dynamicHighlight by prefOption("floating_main_header_dynamic_gravity_highlight", false)
    private val sessions = WeakHashMap<WxViewPager, WeakReference<MainHeaderSession>>()
    private var writingOverlayMode = false

    fun appearance() = MainHeaderAppearance(
        blurRadius.coerceIn(0, 40), cornerRadius.coerceIn(8, 32),
        sideMargin.coerceIn(4, 32), topGap.coerceIn(0, 24), dynamicHighlight,
    )

    override fun onEnable() {
        View::class.reflekt().firstMethod {
            name = "fitSystemWindows"
            parameters(Rect::class)
        }.hookBefore {
            val content = thisObject as View
            forEachSession { args[0] = it.contentInsets(content, args[0] as Rect) }
        }
        ViewGroup::class.reflekt().firstMethod {
            name = "dispatchApplyWindowInsets"
            parameters(WindowInsets::class)
        }.hookBefore {
            val content = thisObject as View
            forEachSession { args[0] = it.contentInsets(content, args[0] as WindowInsets) }
        }
        ACTION_BAR_OVERLAY_LAYOUT_CLASS.toClass().reflekt().firstMethod {
            name = "setOverlayMode"
            parameters(Boolean::class)
        }.hookAfter {
            if (!writingOverlayMode) {
                forEachSession { it.onOverlayModeChanged(thisObject as View, args[0] as Boolean) }
            }
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "onResume"
            parameters()
        }.hookAfter { attachTo(thisObject as LauncherUI) }
        LauncherUI::class.reflekt().firstMethod {
            name = "startChatting"
            parameters(String::class, Bundle::class, Boolean::class)
        }.hookBefore { sessionFor(thisObject as Activity)?.suspendForChat() }
        LauncherUI::class.reflekt().firstMethod {
            name = "closeChatting"
            parameters(Boolean::class)
        }.hookAfter { sessionFor(thisObject as Activity)?.resumeAfterChat() }
        LauncherUI::class.reflekt().firstMethod {
            name = "onDestroy"
            parameters()
        }.hookBefore {
            val activity = thisObject as Activity
            sessions.entries.removeAll { (_, reference) ->
                val session = reference.get()
                if (session?.activity === activity) session.detach()
                session == null || session.activity === activity
            }
        }
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val mainTabs = thisObject!!
            val activity = mainTabs.reflekt().firstField {
                type = "com.tencent.mm.ui.MMFragmentActivity"
            }.get()!! as LauncherUI
            val pager = mainTabs.reflekt().firstField { name = "mViewPager" }.get()!! as WxViewPager
            val adapter = mainTabs.reflekt().firstField { name = "mTabsAdapter" }.get()!!
            attach(activity, pager, adapter)
        }
        // A settings toggle can happen after MainTabUI.doOnCreate has already run.
        LauncherUI.getInstance()?.let { launcher ->
            launcher.runOnUiThread { if (isActive) attachTo(launcher) }
        }
    }

    private fun attachTo(activity: LauncherUI) {
        for (pager in activity.window.decorView.allViews.filterIsInstance<WxViewPager>()) {
            val existing = sessions[pager]?.get()
            if (existing != null) {
                existing.resumeAfterChat()
                return
            }
            val adapter = pager.reflekt().firstMethod { name = "getAdapter"; parameters() }.invoke()
                ?: continue // The launcher can resume before a pager has received its adapter.
            if (adapter.javaClass.name != "com.tencent.mm.ui.MainTabUI\$TabsAdapter") continue
            attach(activity, pager, adapter)
            return
        }
    }

    private fun attach(activity: LauncherUI, pager: WxViewPager, adapter: Any) {
        if (sessions[pager]?.get() != null) return
        val session = MainHeaderSession(activity, pager, adapter)
        sessions[pager] = WeakReference(session)
        session.attach()
    }

    private fun forEachSession(action: (MainHeaderSession) -> Unit) =
        sessions.values.mapNotNull { it.get() }.forEach(action)

    private fun sessionFor(activity: Activity) =
        sessions.values.mapNotNull { it.get() }.firstOrNull { it.activity === activity }

    fun hostViewFor(activity: Activity): View? = sessionFor(activity)?.hostView

    override fun onDisable() {
        forEachSession(MainHeaderSession::detach)
        sessions.clear()
    }

    fun overlayMode(view: View): Boolean = fieldOverlayMode.field.getBoolean(view)
    fun baseInnerInsets(view: View): Any = fieldBaseInnerInsets.field.get(view)!!
    fun contentShadow(view: View): Drawable? = fieldWindowContentOverlay.field.get(view) as Drawable?
    fun setContentShadow(view: View, drawable: Drawable?) {
        fieldWindowContentOverlay.field.set(view, drawable)
        view.invalidate()
    }

    fun setOverlayMode(view: View, enabled: Boolean) {
        writingOverlayMode = true
        try {
            view.reflekt().firstMethod {
                name = "setOverlayMode"
                parameters(Boolean::class)
            }.invoke(enabled)
        } finally {
            writingOverlayMode = false
        }
    }

    fun requestContentInsets(view: View) {
        val base = baseInnerInsets(view)
        fieldLastInnerInsets.field.set(view, if (base is Rect) Rect(base) else base)
        view.requestLayout()
        view.requestApplyInsets()
    }

    override fun onClick(context: ComponentActivity) {
        context.showComposeDialog {
            var config by remember { mutableStateOf(appearance()) }
            fun update(next: MainHeaderAppearance) {
                blurRadius = next.blurRadius
                cornerRadius = next.cornerRadius
                sideMargin = next.sideMargin
                topGap = next.topGap
                dynamicHighlight = next.dynamicHighlight
                config = next
                forEachSession { it.scheduleSync() }
            }
            AlertDialogContent(
                title = { Text(stringResource(nameRes)) },
                text = {
                    LazyColumn {
                        item {
                            SegmentedColumn(
                                title = stringResource(R.string.main_floating_header_behavior_title),
                                contentPadding = PaddingValues(vertical = 4.dp),
                            ) {
                                item { BaseWidget(title = stringResource(R.string.main_floating_header_summary)) }
                            }
                        }
                        item {
                            SegmentedColumn(
                                title = stringResource(R.string.main_floating_header_glass_title),
                                contentPadding = PaddingValues(vertical = 4.dp),
                            ) {
                                item {
                                    SwitchWidget(
                                        title = stringResource(R.string.nav_dynamic_gravity_highlight),
                                        checked = config.dynamicHighlight,
                                        onCheckedChange = { update(config.copy(dynamicHighlight = it)) },
                                    )
                                }
                                item {
                                    BaseItemContainer {
                                        IntNumberPickerWidget(
                                            title = stringResource(R.string.nav_blur_radius),
                                            value = config.blurRadius, startInt = 0, endInt = 40, stepSize = 1,
                                            onValueChange = { update(config.copy(blurRadius = it)) },
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            SegmentedColumn(
                                title = stringResource(R.string.main_floating_header_geometry_title),
                                contentPadding = PaddingValues(vertical = 4.dp),
                            ) {
                                item {
                                    BaseItemContainer {
                                        IntNumberPickerWidget(
                                            title = stringResource(R.string.chat_floating_corner_radius_label),
                                            value = config.cornerRadius, startInt = 8, endInt = 32, stepSize = 1,
                                            onValueChange = { update(config.copy(cornerRadius = it)) },
                                        )
                                    }
                                }
                                item {
                                    BaseItemContainer {
                                        IntNumberPickerWidget(
                                            title = stringResource(R.string.chat_floating_side_margin_label),
                                            value = config.sideMargin, startInt = 4, endInt = 32, stepSize = 1,
                                            onValueChange = { update(config.copy(sideMargin = it)) },
                                        )
                                    }
                                }
                                item {
                                    BaseItemContainer {
                                        IntNumberPickerWidget(
                                            title = stringResource(R.string.chat_floating_top_gap_label),
                                            value = config.topGap, startInt = 0, endInt = 24, stepSize = 1,
                                            onValueChange = { update(config.copy(topGap = it)) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }
}
