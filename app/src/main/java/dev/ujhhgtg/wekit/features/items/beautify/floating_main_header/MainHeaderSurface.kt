package dev.ujhhgtg.wekit.features.items.beautify.floating_main_header

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import dev.ujhhgtg.wekit.ui.content.floatingGlassSurface
import dev.ujhhgtg.wekit.ui.content.rememberFloatingGlassHighlight
import dev.ujhhgtg.wekit.ui.content.rememberFloatingGlassTilt
import dev.ujhhgtg.wekit.ui.content.rememberViewBackdrop

/** One geometry contract for the Android controls, glass, and touch region. */
data class MainHeaderAppearance(
    val blurRadius: Int,
    val cornerRadius: Int,
    val sideMargin: Int,
    val topGap: Int,
    val dynamicHighlight: Boolean,
)

class MainHeaderSurface(context: Context) : FrameLayout(context) {
    private val path = Path()
    private val bounds = RectF()
    var radius = 0f
        set(value) {
            if (field == value) return
            field = value
            updatePath()
        }

    init {
        clipChildren = true
        clipToPadding = true
        clipToOutline = true
        // Never inherit a platform elevation or rectangular foreground from the ActionBar.
        elevation = 0f
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, effectiveRadius())
            }
        }
    }

    private fun effectiveRadius() = radius.coerceAtMost(minOf(width, height) / 2f)

    private fun updatePath() {
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        path.reset()
        path.addRoundRect(bounds, effectiveRadius(), effectiveRadius(), Path.Direction.CW)
        invalidateOutline()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePath()
    }

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        try {
            // Also clip software captures: clipToOutline alone only clips hardware rendering.
            canvas.clipPath(path)
            super.draw(canvas)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val r = effectiveRadius()
            val x = event.x
            val y = event.y
            if (x < 0 || y < 0 || x >= width || y >= height) return false
            val dx = (r - x).coerceAtLeast(x - (width - r)).coerceAtLeast(0f)
            val dy = (r - y).coerceAtLeast(y - (height - r)).coerceAtLeast(0f)
            if (dx * dx + dy * dy > r * r) return false
        }
        return super.dispatchTouchEvent(event)
    }
}

@Composable
fun MainHeaderGlass(source: View, lifecycleOwner: LifecycleOwner, appearance: MainHeaderAppearance) {
    val tint = if (isSystemInDarkTheme()) Color(0xFF191919) else Color(0xFFF7F7F7)
    val backdrop = rememberViewBackdrop(source, lifecycleOwner, observeScrollChanges = true)
    val tilt = rememberFloatingGlassTilt(appearance.dynamicHighlight)
    Box(
        Modifier.fillMaxSize().floatingGlassSurface(
            backdrop = backdrop,
            shape = RoundedCornerShape(appearance.cornerRadius.dp),
            containerColor = tint,
            blurRadius = appearance.blurRadius.dp,
            highlight = rememberFloatingGlassHighlight(tilt, 0f),
        ),
    )
}
