package com.tt.autobluetoothcodec

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** How the content's width is limited in wide windows. */
internal enum class WidthCap {
    /** The full width, as on a phone. */
    NONE,
    /** Up to the cap, then centered. */
    HARD,
    /** Like a phone up to most of the cap, then growing at a quarter of the extra width. */
    SOFT,
}

/**
 * The layout of the screens in wide and tall windows: the width cap, whether the space beside the content is
 * tinted, and where the main screen's cards sit when they don't fill its height, 0 at the top to 1 at the bottom.
 * PROTOTYPE: the previews try combinations, the app uses the default.
 */
internal data class ContentLayout(
    val cap: WidthCap = WidthCap.NONE,
    val tintSides: Boolean = false,
    val devicePlacement: Float = 0f,
)

internal val LocalContentLayout = staticCompositionLocalOf { ContentLayout() }

/** The main screen's and the about screen's cap, wide enough for a card and its chips. */
internal val NARROW_CONTENT = 600.dp

/** The log's and the licenses' cap, they're mostly text. */
internal val WIDE_CONTENT = 720.dp

/** The width of the content in a window [available] wide. */
internal fun contentWidth(available: Dp, cap: Dp, mode: WidthCap): Dp = when (mode) {
    WidthCap.NONE -> available
    WidthCap.HARD -> minOf(available, cap)
    WidthCap.SOFT -> {
        val knee = cap * 0.85f
        if (available <= knee) available else minOf(available, knee + (available - knee) / 4)
    }
}

/**
 * Fills the window and passes [content] the space to leave on either side, so it's at most about [cap] wide and
 * centered. The scroll containers stay as wide as the window, so they scroll wherever they're touched, and put the
 * space inside. The tint beside the content fades in over its first 32 dp, so a narrow strip doesn't stand out.
 */
@Composable
internal fun CappedContent(cap: Dp, modifier: Modifier = Modifier, content: @Composable (side: Dp) -> Unit) {
    val layout = LocalContentLayout.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val side = (maxWidth - contentWidth(maxWidth, cap, layout.cap)) / 2
        val tint = if (layout.tintSides && side > 0.dp) {
            val scheme = MaterialTheme.colorScheme
            lerp(scheme.surface, scheme.surfaceContainer, (side / 32.dp).coerceIn(0f, 1f))
        } else {
            null
        }
        val tinted = if (tint == null) Modifier else Modifier.drawBehind {
            val width = side.toPx()
            drawRect(tint, size = Size(width, size.height))
            drawRect(tint, topLeft = Offset(size.width - width, 0f), size = Size(width, size.height))
        }
        Box(Modifier.fillMaxSize().then(tinted)) {
            content(side)
        }
    }
}

/** Places a column's children together, [bias] of the free space above them: 0 at the top, ½ centered. */
internal class BiasArrangement(private val bias: Float) : Arrangement.Vertical {
    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        var y = ((totalSize - sizes.sum()) * bias).roundToInt().coerceAtLeast(0)
        sizes.forEachIndexed { i, size ->
            outPositions[i] = y
            y += size
        }
    }
}
