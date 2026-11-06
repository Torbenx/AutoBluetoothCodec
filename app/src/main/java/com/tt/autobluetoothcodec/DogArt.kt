package com.tt.autobluetoothcodec

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** The empty states' scenes of Codec, the dog. */
internal enum class DogScene { ASLEEP, SNIFFING, TOY_BOX, AT_DOOR }

/** The width of the art divided by its height. */
internal const val DOG_ART_RATIO = 240f / 172f

private const val ART_WIDTH = 240f

/** Every motion fits a whole number of times into this, so the loop has no seam. */
private const val LOOP_SECONDS = 12f

private const val TAU = 2 * PI.toFloat()

private fun wave(seconds: Float, period: Float, shift: Float = 0f) = sin(TAU * seconds / period - shift)

@Immutable
private class DogColors(
    val floor: Color,
    val furniture: Color,
    val furnitureShade: Color,
    val furnitureDark: Color,
    val fur: Color,
    val furShade: Color,
    val snout: Color,
    val ink: Color,
    val collar: Color,
    val accent: Color,
)

private val COLLAR = Color(0xFF2F6BDB)

@Composable
private fun dogColors(): DogColors {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.surface.luminance() < 0.5f) {
        DogColors(scheme.outlineVariant, scheme.secondaryContainer, Color(0xFF4C566A), Color(0xFF2E3543),
            Color(0xFFCF9D62), Color(0xFFAC7A42), Color(0xFFE6C89E), Color(0xFF4A3628), COLLAR, scheme.primary)
    } else {
        DogColors(scheme.outlineVariant, scheme.secondaryContainer, Color(0xFFC3CFEC), Color(0xFFA9B8DA),
            Color(0xFFE0AC6E), Color(0xFFC68E4F), Color(0xFFF2D6AE), Color(0xFF33291F), COLLAR, scheme.primary)
    }
}

/**
 * [scene], moving, or frozen at the moment [frozenAt] in seconds, which previews and tests can't do otherwise. The
 * clock is only read while drawing, so the motion doesn't recompose the screen. It's decorative, the empty state's text
 * says what it shows.
 */
@Composable
internal fun DogArt(scene: DogScene, modifier: Modifier = Modifier, frozenAt: Float? = null) {
    if (frozenAt != null) {
        DogCanvas(scene, modifier) { frozenAt }
    } else {
        val clock = rememberInfiniteTransition(label = "dog").animateFloat(
            initialValue = 0f,
            targetValue = LOOP_SECONDS,
            animationSpec = infiniteRepeatable(tween((LOOP_SECONDS * 1000).toInt(), easing = LinearEasing), RepeatMode.Restart),
            label = "dog clock",
        )
        DogCanvas(scene, modifier) { clock.value }
    }
}

@Composable
private fun DogCanvas(scene: DogScene, modifier: Modifier, seconds: () -> Float) {
    val colors = dogColors()
    Canvas(modifier) {
        val unit = size.width / ART_WIDTH
        scale(unit, unit, Offset.Zero) {
            when (scene) {
                DogScene.ASLEEP -> drawAsleep(colors, seconds())
                DogScene.SNIFFING -> drawSniffing(colors, sniffPose(seconds()))
                DogScene.TOY_BOX -> drawToyBox(colors, seconds())
                DogScene.AT_DOOR -> drawAtDoor(colors, seconds())
            }
        }
    }
}

/** The moving parts of the sniffing dog at one moment, angles in degrees, clockwise. */
internal data class SniffPose(
    val x: Float,
    val bounce: Float,
    // 1 faces left, -1 faces right, in between it's turning
    val facing: Float,
    val diagonalLegs: Float,
    val neck: Float,
    val head: Float,
    val ear: Float,
    val tail: Float,
    val scent: List<Float>,
)

/**
 * Codec walks along the sofa, stops to sniff, turns around and walks back. Each step's swing covers what the walk
 * covers in that time, at 20 units per second and 1.5 seconds per step, so the feet don't slide over the floor.
 */
internal fun sniffPose(seconds: Float): SniffPose {
    val t = seconds.mod(LOOP_SECONDS)
    fun ramp(time: Float) = (time / 0.3f).coerceIn(0f, 1f)
    fun turn(time: Float): Float {
        val eased = ((time / 0.6f).coerceIn(0f, 1f)).let { it * it * (3 - 2 * it) }
        return cos(PI.toFloat() * eased)
    }
    val x: Float
    val facing: Float
    val walking: Float
    when {
        // From -4 to 66, so the nose stays above the floor at both ends
        t < 3.5f -> { x = 66 - 20 * t; facing = 1f; walking = ramp(t) * ramp(3.5f - t) }
        t < 5f -> { x = -4f; facing = 1f; walking = 0f }
        t < 5.6f -> { x = -4f; facing = turn(t - 5f); walking = 0f }
        t < 9.1f -> { x = 20 * (t - 5.6f) - 4; facing = -1f; walking = ramp(t - 5.6f) * ramp(9.1f - t) }
        t < 11.4f -> { x = 66f; facing = -1f; walking = 0f }
        else -> { x = 66f; facing = -turn(t - 11.4f); walking = 0f }
    }
    val step = wave(t, 1.5f)
    val sniffing = 1 - walking
    val neck = 2 * step - 2.5f * sniffing
    val head = 1.6f * wave(t, 0.25f) * (1 - 0.5f * walking)
    return SniffPose(
        x = x,
        bounce = 1.1f * abs(step) * walking,
        // A squashed dog still has to be drawn, not vanish for a frame
        facing = if (abs(facing) < 0.04f) 0.04f else facing,
        diagonalLegs = 12 * step * walking,
        neck = neck,
        head = head,
        ear = -neck * 1.5f - head * 2 + 2 * step * walking,
        tail = (8 + 8 * sniffing) * wave(t, 0.4f),
        scent = List(3) { sniffing * max(0f, wave(t, 0.5f, it * 0.9f)) },
    )
}

private fun path(data: String): Path = PathParser().parsePathString(data).toPath()

// All in the art's units, 240 by 172
private val SLEEP_TAIL = path("M164 112C180 108 190 98 186 84")
private val SLEEP_BODY = path("M70 120C70 96 94 84 120 84C150 84 174 96 176 120Z")
private val SLEEP_COLLAR = path("M105 86Q112 100 108 119")
private val SLEEP_EYE = path("M55 92Q61 97 67 92")
private val SLEEP_MOUTH = path("M38 112Q48 118 60 112")
private val SLEEP_EAR = path("M78 78C94 74 104 90 99 110C96 122 84 120 81 110C78 100 74 88 78 78Z")
private val SNORES = listOf(path("M26 66h10l-10 12h10"), path("M42 46h8l-8 10h8"), path("M56 30h6l-6 8h6"))

private val SNIFF_TAIL = path("M146 112C162 104 168 88 160 72")
private val SNIFF_BODY = path("M62 128C62 108 84 100 108 100C132 100 152 106 152 128C152 142 132 144 106 144C82 144 62 144 62 128Z")
private val SNIFF_NECK = path("M80 104C66 110 58 118 44 122L54 152C68 148 80 142 88 134Z")
private val SNIFF_COLLAR = path("M76 108Q86 122 84 140")
private val SNIFF_MOUTH = path("M19 155Q29 161 43 156")
private val SNIFF_EAR = path("M58 118C72 116 80 132 74 150C70 160 60 158 58 148C56 138 54 128 58 118Z")

private val SIT_TAIL = path("M152 158C170 160 186 152 190 138")
private val SIT_BODY = path("M86 164V122C86 100 104 92 118 96C142 104 156 134 156 164Z")
private val SIT_COLLAR = path("M84 98Q102 110 120 100")
private val SIT_MOUTH = path("M60 90Q70 96 82 90")
private val SIT_EAR = path("M100 56C116 52 126 68 121 88C118 100 106 98 103 88C100 78 96 66 100 56Z")

private val BOX = path("M18 112H88L82 164H24Z")
private val BOX_RIM = path("M18 112H88L86 122H20Z")
private val LEASH = path("M123 93C112 112 94 124 98 146")

private fun DrawScope.line(path: Path, color: Color, width: Float, alpha: Float = 1f) =
    drawPath(path, color, alpha, Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))

private fun DrawScope.box(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Color) =
    drawRoundRect(color, Offset(x, y), Size(width, height), CornerRadius(radius))

private fun DrawScope.oval(x: Float, y: Float, radiusX: Float, radiusY: Float, color: Color) =
    drawOval(color, Offset(x - radiusX, y - radiusY), Size(2 * radiusX, 2 * radiusY))

private fun DrawScope.dot(x: Float, y: Float, radius: Float, color: Color, alpha: Float = 1f) =
    drawCircle(color, radius, Offset(x, y), alpha)

private fun DrawScope.drawFloor(c: DogColors) =
    drawLine(c.floor, Offset(10f, 164f), Offset(230f, 164f), 2.5f, StrokeCap.Round)

private fun DrawScope.drawSofa(c: DogColors) {
    box(44f, 56f, 152f, 74f, 18f, c.furniture)
    box(34f, 140f, 172f, 14f, 5f, c.furnitureDark)
    box(46f, 152f, 10f, 12f, 2f, c.furnitureDark)
    box(184f, 152f, 10f, 12f, 2f, c.furnitureDark)
    box(20f, 92f, 36f, 58f, 15f, c.furnitureShade)
    box(184f, 92f, 36f, 58f, 15f, c.furnitureShade)
    box(44f, 118f, 152f, 28f, 10f, c.furnitureShade)
}

private fun DrawScope.drawAsleep(c: DogColors, seconds: Float) {
    drawFloor(c)
    drawSofa(c)
    line(SLEEP_TAIL, c.fur, 10f)
    // Breathing, the belly stays on the cushion
    scale(1f, 1 + 0.03f * wave(seconds, 4f), Offset(120f, 120f)) {
        drawPath(SLEEP_BODY, c.fur)
        oval(148f, 105f, 23f, 14f, c.furShade)
        box(128f, 110f, 34f, 9f, 4.5f, c.fur)
    }
    line(SLEEP_COLLAR, c.collar, 6f)
    dot(72f, 98f, 22f, c.fur)
    oval(50f, 106f, 18f, 12f, c.snout)
    oval(35f, 101f, 5.5f, 4.5f, c.ink)
    line(SLEEP_EYE, c.ink, 2.6f)
    line(SLEEP_MOUTH, c.ink, 2.2f)
    drawPath(SLEEP_EAR, c.furShade)
    // Each Z brightens in turn, and none disappears, so the frozen art has them too
    SNORES.forEachIndexed { index, snore ->
        line(snore, c.accent, 2.6f, alpha = 0.3f + 0.7f * max(0f, wave(seconds, 3f, index * 1.2f)))
    }
}

private fun DrawScope.drawSniffing(c: DogColors, pose: SniffPose) {
    drawFloor(c)
    drawSofa(c)
    translate(pose.x, -pose.bounce) {
        // Turning is a flip around the middle of the dog
        scale(pose.facing, 1f, Offset(90f, 0f)) {
            drawSniffingDog(c, pose)
        }
    }
}

private fun DrawScope.drawLeg(x: Float, hip: Float, angle: Float, color: Color) =
    rotate(angle, Offset(hip, 130f)) { box(x, 126f, 12f, 38f, 6f, color) }

private fun DrawScope.drawSniffingDog(c: DogColors, pose: SniffPose) {
    drawLeg(122f, 128f, pose.diagonalLegs, c.furShade)
    drawLeg(82f, 88f, -pose.diagonalLegs, c.furShade)
    rotate(pose.tail, Offset(146f, 112f)) { line(SNIFF_TAIL, c.fur, 10f) }
    drawPath(SNIFF_BODY, c.fur)
    drawLeg(136f, 142f, -pose.diagonalLegs, c.fur)
    drawLeg(68f, 74f, pose.diagonalLegs, c.fur)
    rotate(pose.neck, Offset(84f, 118f)) {
        drawPath(SNIFF_NECK, c.fur)
        line(SNIFF_COLLAR, c.collar, 6f)
        rotate(pose.head, Offset(58f, 128f)) {
            dot(52f, 136f, 21f, c.fur)
            rotate(-24f, Offset(33f, 150f)) {
                oval(33f, 150f, 17f, 11.5f, c.snout)
                line(SNIFF_MOUTH, c.ink, 2.2f)
            }
            oval(20f, 153f, 5.5f, 4.5f, c.ink)
            dot(44f, 129f, 3.4f, c.ink)
            dot(12f, 140f, 2.2f, c.accent, pose.scent[0])
            dot(7f, 130f, 1.9f, c.accent, pose.scent[1])
            dot(16f, 124f, 1.6f, c.accent, pose.scent[2])
            rotate(pose.ear, Offset(62f, 120f)) { drawPath(SNIFF_EAR, c.furShade) }
        }
    }
}

/** Codec sitting, facing left, a little to the right of the scene's middle. */
private fun DrawScope.drawSittingDog(c: DogColors, seconds: Float) = translate(44f, 0f) {
    rotate(10 * wave(seconds, 0.6f), Offset(152f, 158f)) { line(SIT_TAIL, c.fur, 10f) }
    drawPath(SIT_BODY, c.fur)
    oval(130f, 145f, 25f, 19f, c.furShade)
    box(112f, 154f, 30f, 10f, 5f, c.fur)
    box(90f, 120f, 13f, 44f, 6.5f, c.fur)
    box(84f, 155f, 20f, 9f, 4.5f, c.fur)
    line(SIT_COLLAR, c.collar, 6f)
    dot(94f, 76f, 24f, c.fur)
    oval(72f, 84f, 18f, 12.5f, c.snout)
    oval(57f, 79f, 5.5f, 4.5f, c.ink)
    dot(84f, 68f, 3.4f, c.ink)
    line(SIT_MOUTH, c.ink, 2.2f)
    drawPath(SIT_EAR, c.furShade)
}

private fun DrawScope.drawToyBox(c: DogColors, seconds: Float) {
    drawFloor(c)
    drawPath(BOX, c.furnitureShade)
    drawPath(BOX_RIM, c.furnitureDark)
    drawSittingDog(c, seconds)
}

private fun DrawScope.drawAtDoor(c: DogColors, seconds: Float) {
    drawFloor(c)
    box(14f, 22f, 76f, 142f, 4f, c.furniture)
    box(24f, 34f, 56f, 50f, 3f, c.furnitureShade)
    box(24f, 94f, 56f, 58f, 3f, c.furnitureShade)
    dot(78f, 94f, 5f, c.furnitureDark)
    drawSittingDog(c, seconds)
    // The leash Codec holds in its mouth
    line(LEASH, c.accent, 4f)
    drawCircle(c.accent, 8f, Offset(100f, 155f), style = Stroke(4f))
}
