package com.forwardsweep.app.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.forwardsweep.classifier.Category
import com.forwardsweep.classifier.MediaType
import com.forwardsweep.data.db.ScannedImageEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the Review screen needs from the view model. */
interface ReviewActions {
    fun toggle(uri: String)
    fun setSelected(uris: Collection<String>, value: Boolean)
    fun selectionSnapshot(): Map<String, Boolean>
    fun applyDrag(base: Map<String, Boolean>, range: Collection<String>, value: Boolean)
    fun moveTo(uris: Collection<String>, category: Category)
    fun delete(uris: Collection<String>)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.ReviewScreen(
    category: Category,
    rows: List<ScannedImageEntity>?,
    selected: Set<String>,
    gridState: LazyStaggeredGridState,
    focusedUri: String?,
    actions: ReviewActions,
    animatedScope: AnimatedVisibilityScope,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val items = rows.orEmpty()
    val inCategory = remember(items, selected) { items.filter { it.uri in selected } }
    val bytes = inCategory.sumOf { it.sizeBytes }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var moveMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Coming back from the preview: make sure the image the user ended on is visible.
    LaunchedEffect(focusedUri, items.isNotEmpty()) {
        val i = items.indexOfFirst { it.uri == focusedUri }
        if (i >= 0 && gridState.layoutInfo.visibleItemsInfo.none { it.index == i }) gridState.scrollToItem(i)
    }

    fun toggle(row: ScannedImageEntity) {
        val selecting = row.uri !in selected
        actions.toggle(row.uri)
        if (selecting && row.protectedImage) scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar("This looks like a real photo or video with people. Selected because you picked it.")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(category.title)
                        val videos = items.count { it.mediaType == MediaType.VIDEO }
                        Text(if (videos == 0) plural(items.size, "image") else "${plural(items.size - videos, "image")} · ${plural(videos, "video")}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    val all = items.isNotEmpty() && inCategory.size == items.size
                    IconButton(onClick = { actions.setSelected(items.map { it.uri }, !all) }, enabled = items.isNotEmpty()) {
                        Icon(if (all) Icons.Outlined.Deselect else Icons.Outlined.SelectAll, if (all) "Clear selection" else "Select all")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // Sticky selection bar: always visible so the selection count is never hidden.
            Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        AnimatedCount(inCategory.size, MaterialTheme.typography.titleMedium, suffix = " selected")
                        AnimatedBytes(bytes, MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        OutlinedButton(onClick = { moveMenu = true }, enabled = inCategory.isNotEmpty()) {
                            Icon(Icons.Outlined.DriveFileMove, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Move")
                        }
                        DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                            for (c in Category.entries) if (c != category) DropdownMenuItem(
                                text = { Text("Move to ${c.title}") },
                                leadingIcon = { Icon(c.icon, null, tint = c.color()) },
                                onClick = {
                                    moveMenu = false
                                    actions.moveTo(inCategory.map { it.uri }, c)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            // Android 11+ shows its own system confirmation; older versions get ours.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) actions.delete(inCategory.map { it.uri })
                            else confirmDelete = true
                        },
                        enabled = inCategory.isNotEmpty(),
                    ) {
                        Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete")
                    }
                }
            }
        },
    ) { padding ->
        if (rows != null && items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Nothing here", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        SelectableGrid(
            items = items,
            selected = selected,
            gridState = gridState,
            contentPadding = padding,
            actions = actions,
            onTap = { row, onCheck -> if (onCheck) toggle(row) else onOpen(row.uri) },
            cell = { row, isSelected ->
                Thumbnail(row, isSelected, Modifier.sharedElement(rememberSharedContentState("img:${row.uri}"), animatedScope))
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${plural(inCategory.size, "image")}?") },
            text = { Text("${formatBytes(ctx, bytes)} will be freed. This can't be undone on this Android version.") },
            confirmButton = { Button(onClick = { confirmDelete = false; actions.delete(inCategory.map { it.uri }) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Staggered grid with Photos-style selection: tap the circle to toggle, tap the image to open
 * it, long-press and drag to select (or deselect) a range. The grid auto-scrolls while dragging
 * near its top or bottom edge.
 */
@Composable
private fun SelectableGrid(
    items: List<ScannedImageEntity>,
    selected: Set<String>,
    gridState: LazyStaggeredGridState,
    contentPadding: PaddingValues,
    actions: ReviewActions,
    onTap: (row: ScannedImageEntity, onCheck: Boolean) -> Unit,
    cell: @Composable (ScannedImageEntity, Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edge = with(density) { 72.dp.toPx() }
    val checkArea = with(density) { 44.dp.toPx() }
    val currentItems by rememberUpdatedState(items)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnTap by rememberUpdatedState(onTap)

    class Drag(val start: Int, val value: Boolean, val base: Map<String, Boolean>) { var last = start }
    var drag by remember { mutableStateOf<Drag?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var viewportHeight by remember { mutableStateOf(0) }

    fun itemAt(pos: Offset): Int? {
        val info = gridState.layoutInfo
        // Item offsets are measured from the content start, which sits above the top padding.
        val y = pos.y + info.viewportStartOffset
        return info.visibleItemsInfo.firstOrNull {
            pos.x >= it.offset.x && pos.x < it.offset.x + it.size.width && y >= it.offset.y && y < it.offset.y + it.size.height
        }?.index
    }

    fun extend(pos: Offset) {
        val d = drag ?: return
        val i = itemAt(pos) ?: return
        if (i == d.last) return
        d.last = i
        val range = (minOf(d.start, i)..maxOf(d.start, i)).mapNotNull { currentItems.getOrNull(it)?.uri }
        actions.applyDrag(d.base, range, d.value)
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    // Auto-scroll while the finger rests near an edge during a drag.
    LaunchedEffect(drag != null) {
        while (isActive && drag != null) {
            val speed = when {
                pointer.y < edge -> -(edge - pointer.y) / 3f
                pointer.y > viewportHeight - edge -> (pointer.y - (viewportHeight - edge)) / 3f
                else -> 0f
            }
            if (speed != 0f) { gridState.scrollBy(speed); extend(pointer) }
            delay(16)
        }
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(120.dp),
        state = gridState,
        contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 2.dp, bottom = contentPadding.calculateBottomPadding() + 2.dp),
        verticalItemSpacing = 3.dp,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 3.dp)
            .pointerInput(Unit) {
                viewportHeight = size.height
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var cancelled = false
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation().also { if (it == null) cancelled = true }
                    }
                    if (cancelled) return@awaitEachGesture // the grid scrolled
                    if (up != null) {
                        val i = itemAt(up.position) ?: return@awaitEachGesture
                        val row = currentItems.getOrNull(i) ?: return@awaitEachGesture
                        val info = gridState.layoutInfo.visibleItemsInfo.first { it.index == i }
                        val local = Offset(up.position.x - info.offset.x, up.position.y + gridState.layoutInfo.viewportStartOffset - info.offset.y)
                        currentOnTap(row, local.x < checkArea && local.y < checkArea)
                        return@awaitEachGesture
                    }
                    // Long press: start a drag selection. Consume in the Initial pass so the
                    // grid's own scrolling doesn't fight the finger.
                    val start = itemAt(down.position) ?: return@awaitEachGesture
                    val uri = currentItems.getOrNull(start)?.uri ?: return@awaitEachGesture
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val d = Drag(start, value = uri !in currentSelected, base = actions.selectionSnapshot())
                    actions.applyDrag(d.base, listOf(uri), d.value)
                    drag = d
                    pointer = down.position
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            pointer = change.position
                            extend(change.position)
                        }
                    } finally {
                        drag = null
                    }
                }
            },
    ) {
        itemsIndexed(items, key = { _, row -> row.uri }) { _, row ->
            val isSelected = row.uri in selected
            Box(Modifier.semantics {
                this.selected = isSelected
                contentDescription = row.displayName + if (row.protectedImage) ", has faces" else ""
                onClick("Open") { onTap(row, false); true }
                onLongClick(if (isSelected) "Deselect" else "Select") { onTap(row, true); true }
            }) { cell(row, isSelected) }
        }
    }
}

@Composable
private fun Thumbnail(row: ScannedImageEntity, selected: Boolean, imageModifier: Modifier) {
    val ctx = LocalContext.current
    val ratio = if (row.width > 0 && row.height > 0) (row.width.toFloat() / row.height).coerceIn(0.6f, 1.6f) else 1f
    // Selected cells shrink slightly with rounded corners, like the system gallery.
    val inset by animateDpAsState(if (selected) 10.dp else 0.dp, label = "inset")
    val corner by animateDpAsState(if (selected) 14.dp else 4.dp, label = "corner")
    val checkScale by animateFloatAsState(if (selected) 1f else 0.9f, label = "check")
    val tint = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        AsyncImage(
            model = thumbnailRequest(ctx, row.uri),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = imageModifier.fillMaxSize().padding(inset).clip(RoundedCornerShape(corner)),
        )
        Icon(
            if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) tint else Color.White,
            modifier = Modifier
                .padding(6.dp)
                .size(24.dp * checkScale)
                .background(if (selected) MaterialTheme.colorScheme.surface else Color.Black.copy(alpha = 0.25f), CircleShape),
        )
        if (row.mediaType == MediaType.VIDEO) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(start = 2.dp, end = 6.dp, top = 1.dp, bottom = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, "Video", tint = Color.White, modifier = Modifier.size(16.dp))
                Text(formatDuration(row.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (row.protectedImage) Badge(Icons.Outlined.Shield, "Protected: has faces")
            else if (row.faces > 0) Badge(Icons.Outlined.Face, "Has faces")
            if (row.duplicateGroup != null) Badge(Icons.Outlined.ContentCopy, "Has duplicates")
            if (row.userCategory != null) Badge(Icons.Outlined.CheckCircle, "Sorted by you")
        }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String) {
    Icon(
        icon, description, tint = Color.White,
        modifier = Modifier
            .size(22.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .border(0.5.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            .padding(4.dp),
    )
}
