package com.forwardsweep.app.ui

import android.content.Context
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import coil.size.Precision
import coil.request.videoFramePercent

private val counterSpec = tween<Float>(durationMillis = 700, easing = FastOutSlowInEasing)

/** A number that counts up or down to its new value. */
@Composable
fun AnimatedCount(value: Int, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified, suffix: String = "") {
    val shown by animateIntAsState(value, tween(700, easing = FastOutSlowInEasing), label = "count")
    Text("%,d".format(shown) + suffix, style = style, color = color, modifier = modifier)
}

/** A file size that animates between values, e.g. "84.6 MB". */
@Composable
fun AnimatedBytes(
    bytes: Long,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    prefix: String = "",
    startFromZero: Boolean = false,
) {
    val ctx = LocalContext.current
    val shown = remember { Animatable(if (startFromZero) 0f else bytes.toFloat()) }
    LaunchedEffect(bytes) { shown.animateTo(bytes.toFloat(), counterSpec) }
    Text(prefix + formatBytes(ctx, shown.value.toLong()), style = style, color = color, modifier = modifier)
}

data class RingSegment(val value: Float, val color: Color)

/**
 * Donut chart of space per category. Segments sweep in on first show and animate when the
 * values change (after a scan or a clean-up).
 */
@Composable
fun RingChart(
    segments: List<RingSegment>,
    modifier: Modifier = Modifier,
    diameter: Dp = 200.dp,
    thickness: Dp = 22.dp,
    description: String,
    center: @Composable () -> Unit,
) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val total = segments.sumOf { it.value.toDouble() }.toFloat()
    // Animate each segment's share so the ring morphs instead of jumping.
    val shares = segments.mapIndexed { i, s ->
        animateFloatAsState(if (total > 0f) s.value / total else 0f, counterSpec, label = "share$i").value
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(diameter).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val stroke = thickness.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            val visible = shares.count { it > 0.001f }
            val gap = if (visible > 1) 4f else 0f
            var start = -90f
            for ((i, share) in shares.withIndex()) {
                val sweep = share * 360f * reveal.value
                if (sweep > gap) {
                    drawArc(segments[i].color, start + gap / 2, sweep - gap, false, topLeft, arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Butt))
                }
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) { center() }
    }
}

/** Grid thumbnail request. The preview uses the same cache key as its placeholder, so the
 *  shared-element transition starts from an image that is already decoded. */
fun thumbnailRequest(context: Context, uri: String, sidePx: Int = 360): ImageRequest =
    ImageRequest.Builder(context)
        .data(Uri.parse(uri))
        .size(sidePx)
        .precision(Precision.INEXACT)
        .memoryCacheKey(thumbKey(uri))
        // Videos: the middle frame (Good Morning clips often start on a black or blank frame).
        .videoFramePercent(0.5)
        .crossfade(true)
        .build()

fun fullRequest(context: Context, uri: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(Uri.parse(uri))
        .size(2048)
        .precision(Precision.INEXACT)
        .placeholderMemoryCacheKey(thumbKey(uri))
        .build()

private fun thumbKey(uri: String) = "thumb:$uri"

/** 75_000 → "1:15". */
fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
