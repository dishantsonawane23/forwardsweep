package com.forwardsweep.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forwardsweep.app.AppViewModel
import com.forwardsweep.classifier.Category
import com.forwardsweep.data.scan.ScanProgress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: AppViewModel.UiState,
    onOpen: (Category) -> Unit,
    onSettings: () -> Unit,
    onScan: () -> Unit,
    onCleanUp: () -> Unit,
    /** Photo access was granted before videos were supported: offer to add video access. */
    askForVideos: Boolean = false,
    onAllowVideos: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var confirm by remember { mutableStateOf(false) }
    val selected = state.selectedTotals

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("ForwardSweep") },
                actions = {
                    IconButton(onClick = onScan, enabled = state.progress !is ScanProgress.Running) {
                        Icon(Icons.Outlined.Refresh, "Scan again")
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
                },
                scrollBehavior = scroll,
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    Button(
                        onClick = { confirm = true },
                        enabled = selected.count > 0,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Icon(Icons.Outlined.CleaningServices, null)
                        Spacer(Modifier.width(10.dp))
                        if (selected.count == 0) Text("Nothing selected")
                        else {
                            Text("Clean up ")
                            AnimatedCount(selected.count, MaterialTheme.typography.labelLarge)
                            AnimatedBytes(selected.bytes, MaterialTheme.typography.labelLarge, prefix = " · ")
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { ScanStatus(state, onScan) }
            if (askForVideos) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Sort videos too", style = MaterialTheme.typography.titleMedium)
                        Text("Good Morning videos and GIFs often take more space than images. Allow video access to include them.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onAllowVideos) { Text("Allow videos") }
                    }
                }
            }
            if (state.summary.isNotEmpty()) {
                item { SpaceCard(state) }
                // Two cards per row; the last odd card spans the full width.
                val cats = Category.entries.filter { (state.summary[it]?.count ?: 0) > 0 }
                items(cats.chunked(2).size) { row ->
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (c in cats.chunked(2)[row]) {
                            CategoryCard(c, state, onClick = { onOpen(c) }, modifier = Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Blurred WhatsApp previews you never downloaded aren't saved on your phone, so they aren't scanned or counted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirm) {
        val trash = state.settings.useTrash && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
        val junk = listOf(Category.GREETING, Category.MEME).sumOf { state.selectedByCategory[it]?.count ?: 0 }
        val other = selected.count - junk
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Outlined.CleaningServices, null) },
            title = { Text(if (trash) "Move ${plural(selected.count, "image")} to trash?" else "Delete ${plural(selected.count, "image")}?") },
            text = {
                Text(buildString {
                    append("${plural(junk, "greeting or meme", "greetings and memes")}")
                    if (other > 0) append(" and ${plural(other, "other image")} you picked yourself")
                    append(", ${formatBytes(ctx, selected.bytes)} in total. Photos with faces are never included unless you pick them.")
                    if (trash) append("\n\nYou can restore them from the trash for 30 days.")
                })
            },
            confirmButton = { Button(onClick = { confirm = false; onCleanUp() }) { Text(if (trash) "Move to trash" else "Delete") } },
            dismissButton = {
                TextButton(onClick = { confirm = false; onOpen(Category.GREETING) }) { Text("Review first") }
            },
        )
    }
}

@Composable
private fun ScanStatus(state: AppViewModel.UiState, onScan: () -> Unit) {
    val p = state.progress
    val empty = state.summary.isEmpty()
    if (!empty && (p == ScanProgress.Idle || p == ScanProgress.Finished)) return
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp)) {
            when (p) {
                is ScanProgress.Running -> {
                    if (p.total == 0) {
                        Text("Looking for images…", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("Sorting ", style = MaterialTheme.typography.titleMedium)
                            AnimatedCount(p.done, MaterialTheme.typography.titleMedium)
                            Text(" of ${"%,d".format(p.total)}", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Everything happens on your phone. You can leave the app.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ScanProgress.WaitingForCharging -> {
                    Text("Newest images sorted", style = MaterialTheme.typography.titleMedium)
                    Text("Your library is large, so the rest will finish while your phone charges.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Text("No images sorted yet", style = MaterialTheme.typography.titleMedium)
                    Text("ForwardSweep looks through your WhatsApp images and videos and sorts out greetings and memes.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onScan) { Text("Scan now") }
                }
            }
        }
    }
}

@Composable
private fun SpaceCard(state: AppViewModel.UiState) {
    val order = Category.entries
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val junkBytes = (state.summary[Category.GREETING]?.totalBytes ?: 0) + (state.summary[Category.MEME]?.totalBytes ?: 0)
            RingChart(
                segments = order.map { RingSegment((state.summary[it]?.totalBytes ?: 0).toFloat(), it.color()) },
                description = "Space used by category",
            ) {
                AnimatedBytes(state.total.bytes, MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
                AnimatedCount(state.total.count, MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, suffix = " items")
            }
            Spacer(Modifier.height(16.dp))
            AnimatedVisibility(junkBytes > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedBytes(junkBytes, MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(" is greetings and memes", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(category: Category, state: AppViewModel.UiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val summary = state.summary[category]
    val chosen = state.selectedByCategory[category]?.count ?: 0
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Box(
                Modifier.size(40.dp).background(category.color().copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(category.icon, null, tint = category.color()) }
            Spacer(Modifier.height(12.dp))
            Text(category.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedCount(summary?.count ?: 0, MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(6.dp))
                AnimatedBytes(summary?.totalBytes ?: 0, MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            Text(
                if (chosen > 0) "%,d selected".format(chosen) else category.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = if (chosen > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}
