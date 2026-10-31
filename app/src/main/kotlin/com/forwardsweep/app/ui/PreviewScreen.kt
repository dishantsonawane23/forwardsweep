package com.forwardsweep.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.forwardsweep.classifier.Category
import com.forwardsweep.classifier.MediaType
import android.content.Intent
import android.net.Uri
import com.forwardsweep.data.db.ScannedImageEntity

/**
 * Full-screen, swipeable preview. The opened image flies in from its grid cell (shared element)
 * and every page explains why the classifier chose its category.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.PreviewScreen(
    rows: List<ScannedImageEntity>,
    startUri: String,
    selected: Set<String>,
    actions: ReviewActions,
    animatedScope: AnimatedVisibilityScope,
    onPageChanged: (String) -> Unit,
    onClose: () -> Unit,
) {
    if (rows.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    val ctx = LocalContext.current
    val start = remember { rows.indexOfFirst { it.uri == startUri }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = start) { rows.size }
    var chrome by remember { mutableStateOf(true) }

    LaunchedEffect(pager, rows) {
        snapshotFlow { pager.currentPage }.collect { rows.getOrNull(it)?.let { r -> onPageChanged(r.uri) } }
    }

    // The preview is always dark, whatever the system theme, like a photo viewer.
    MaterialTheme(colorScheme = darkColorScheme(primary = MaterialTheme.colorScheme.inversePrimary)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, key = { rows.getOrNull(it)?.uri ?: it }, beyondViewportPageCount = 1) { page ->
                val row = rows[page]
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = fullRequest(ctx, row.uri),
                    contentDescription = row.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .sharedElement(rememberSharedContentState("img:${row.uri}"), animatedScope)
                        .clickable(remember { MutableInteractionSource() }, indication = null) { chrome = !chrome },
                )
                if (row.mediaType == MediaType.VIDEO) {
                    // Play in the phone's own video player: no player code or codecs to ship.
                    FilledIconButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(row.uri), "video/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { ctx.startActivity(intent) }
                        },
                        modifier = Modifier.size(72.dp),
                    ) { Icon(Icons.Rounded.PlayArrow, "Play video", Modifier.size(40.dp)) }
                }
                }
            }

            val row = rows.getOrNull(pager.currentPage) ?: return@Box
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(row.displayName, color = Color.White, style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pager.currentPage + 1} of ${rows.size} · ${formatBytes(ctx, row.sizeBytes)}" +
                            if (row.mediaType == MediaType.VIDEO) " · ${formatDuration(row.durationMs)}" else "",
                            color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                DetailsPanel(row, row.uri in selected, actions)
            }
        }
    }
}

@Composable
private fun DetailsPanel(row: ScannedImageEntity, isSelected: Boolean, actions: ReviewActions) {
    var why by remember(row.uri) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val shown = row.effectiveCategory
    Column(
        Modifier
            .fillMaxWidth()
            // Solid when the reasons are open so the text stays readable over bright images.
            .background(if (why) Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Black.copy(alpha = 0.9f)))
                else Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { why = !why },
                label = {
                    Text(if (row.userCategory != null) "${shown.label} · sorted by you"
                    else if (shown == Category.UNCERTAIN) "Uncertain · looks most like ${row.topCategory.label}"
                    else "${shown.label} · ${(row.confidence * 100).toInt()}% sure")
                },
                leadingIcon = { Icon(shown.icon, null, tint = shown.color(), modifier = Modifier.size(18.dp)) },
                trailingIcon = { Icon(if (why) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, if (why) "Hide reasons" else "Why?") },
                colors = AssistChipDefaults.assistChipColors(labelColor = Color.White),
            )
            if (row.protectedImage) Icon(Icons.Outlined.Shield, "Protected: has faces", tint = Color.White.copy(alpha = 0.8f))
        }
        if (why) Reasons(row)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { actions.toggle(row.uri) }, modifier = Modifier.weight(1f)) {
                Text(if (isSelected) "Selected for deletion" else "Keep · tap to select")
            }
            Box {
                OutlinedButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.DriveFileMove, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Move")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (c in Category.entries) if (c != shown) DropdownMenuItem(
                        text = { Text("Move to ${c.title}") },
                        leadingIcon = { Icon(c.icon, null, tint = c.color()) },
                        onClick = { menu = false; actions.moveTo(listOf(row.uri), c) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Reasons(row: ScannedImageEntity) {
    val probs = row.probabilities.split(",").mapNotNull { it.toDoubleOrNull() }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Category.SCORED.forEachIndexed { i, c ->
            val p = probs.getOrNull(i) ?: return@forEachIndexed
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.label, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(150.dp))
                LinearProgressIndicator(
                    progress = { p.toFloat() },
                    color = c.color(),
                    trackColor = Color.White.copy(alpha = 0.15f),
                    modifier = Modifier.weight(1f).height(6.dp),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
                Text("%3d%%".format((p * 100).toInt()), color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp).width(40.dp))
            }
        }
        val reasons = row.reasons.lines().filter { it.isNotBlank() }.take(6)
        if (reasons.isNotEmpty()) Column(
            Modifier.background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(12.dp).fillMaxWidth(),
        ) {
            reasons.forEach { Text("• ${plainReason(it)}", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall) }
        }
        if (row.protectedImage) Text(
            "Faces found and it may be a real photo, so it's never selected automatically.",
            color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall,
        )
        Text("Wrong? Use Move. ForwardSweep learns from it, and fixes its copies too.",
            color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
    }
}

private val REASON = Regex("""^(\w+) ([+-][\d.]+) \S+: (.*?)(?: \([^)]*\))?$""")

/** "greeting +2.6 ↑: greeting text 'good night' (design 0.90)" → "Points to Greeting: greeting text 'good night'". */
internal fun plainReason(raw: String): String {
    val m = REASON.matchEntire(raw.trim()) ?: return raw
    val (key, weight, text) = m.destructured
    val category = Category.SCORED.firstOrNull { it.name.startsWith(key.uppercase()) || it.label.lowercase().startsWith(key.lowercase()) }
        ?: return text
    val toward = weight.startsWith("+")
    return (if (toward) "Points to ${category.label}" else "Less like ${category.label}") + ": " + text
}
