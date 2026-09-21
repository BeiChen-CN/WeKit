// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package dev.ujhhgtg.wekit.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.ui.content.liquid.lens
import dev.ujhhgtg.wekit.ui.content.liquid.vibrancy
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.sensor.DeviceTilt
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val floatingGlassSpecular = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f,
        ),
        dualPeak = true,
    ),
)

// Mirrors miuix-blur HighlightStyle's LIGHT_REF — keep in sync.
private const val LIGHT_REF_X = 0.5f
private const val LIGHT_REF_Y = 0.7f
private const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f // |g_xy| > 0.1, ≈ 6° tilt

@Composable
fun rememberFloatingGlassTilt(dynamicGravityHighlight: Boolean): DeviceTilt {
    return if (dynamicGravityHighlight) rememberDeviceTilt().value else DeviceTilt.Zero
}

@Composable
fun rememberFloatingGlassHighlight(
    tilt: DeviceTilt,
    extraDegrees: Float,
): Highlight {
    val baseStyle = floatingGlassSpecular.style as BloomStroke
    val rotatedPrimary = remember(tilt, extraDegrees) {
        val basePrimary = baseStyle.primaryLight
        val gx = tilt.gravityX
        val gy = tilt.gravityY
        val gMagSq = gx * gx + gy * gy
        val (lx0, ly0) = if (gMagSq > GRAVITY_DIR_THRESHOLD_SQ) {
            val invMag = 1f / sqrt(gMagSq)
            (gx * invMag) to (gy * invMag)
        } else {
            0f to -1f
        }
        val rad = extraDegrees * PI / 180.0
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val lx = c * lx0 - s * ly0
        val ly = s * lx0 + c * ly0
        basePrimary.copy(
            position = LightPosition(
                x = LIGHT_REF_X + lx,
                y = LIGHT_REF_Y + ly,
                z = basePrimary.position.z,
            ),
        )
    }
    return remember(rotatedPrimary) {
        floatingGlassSpecular.copy(style = baseStyle.copy(primaryLight = rotatedPrimary))
    }
}

fun floatingGlassContainerColor(containerColor: Color, blurRadius: Dp): Color {
    return if (blurRadius <= 0.dp) Color.Transparent else containerColor.copy(alpha = 0.4f)
}

fun Modifier.floatingGlassSurface(
    backdrop: Backdrop,
    shape: Shape,
    containerColor: Color,
    blurRadius: Dp,
    highlight: Highlight,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier {
    val transparent = blurRadius <= 0.dp
    val surfaceColor = floatingGlassContainerColor(containerColor, blurRadius)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (!transparent) {
                vibrancy()
                blur(blurRadius.toPx(), blurRadius.toPx())
                lens(
                    refractionHeight = 24.dp.toPx(),
                    refractionAmount = 24.dp.toPx(),
                )
            }
        },
        highlight = { highlight.copy(alpha = 0.75f) },
        layerBlock = layerBlock,
        onDrawSurface = { drawRect(surfaceColor) },
    )
}
