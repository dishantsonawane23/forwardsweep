package com.forwardsweep.app.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.forwardsweep.data.media.ScanScope
import com.forwardsweep.data.settings.Settings
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    notificationsAllowed: Boolean,
    onChange: ((Settings) -> Settings) -> Unit,
    onReminderOn: () -> Unit,
    onRescanAll: () -> Unit,
    learnedCount: Int,
    onResetLearning: () -> Unit,
    onBack: () -> Unit,
) {
    var resetDialog by remember { mutableStateOf(false) }
    var rescanDialog by remember { mutableStateOf(false) }
    // Slider value is local while dragging and saved on release.
    var confidence by remember(settings.minConfidence) { mutableFloatStateOf(settings.minConfidence.toFloat()) }
    val trashSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item { Header("What to scan") }
            item {
                Column {
                    for ((scope, title, detail) in listOf(
                        Triple(ScanScope.WHATSAPP_ONLY, "WhatsApp images", "WhatsApp and WhatsApp Business"),
                        Triple(ScanScope.ALL_IMAGES, "All images", "Your whole gallery, including camera photos"),
                    )) {
                        ListItem(
                            headlineContent = { Text(title) },
                            supportingContent = { Text(detail) },
                            leadingContent = { RadioButton(selected = settings.scope == scope, onClick = null) },
                            modifier = Modifier.selectable(settings.scope == scope, role = Role.RadioButton) {
                                onChange { it.copy(scope = scope) }
                            },
                        )
                    }
                }
            }
            item {
                SwitchItem(
                    "Videos and GIFs",
                    "Also sort WhatsApp videos and GIFs. Good Morning videos are often the biggest files.",
                    settings.includeVideos,
                ) { v -> onChange { it.copy(includeVideos = v) } }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { Header("Safety") }
            item {
                SwitchItem(
                    "Protect photos with faces",
                    "Images with people that might be real photos are never selected automatically.",
                    settings.protectFaces,
                ) { v -> onChange { it.copy(protectFaces = v) } }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Sorting confidence: ${(confidence * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Images the classifier is less sure about go to Uncertain. Higher is safer; lower sorts more.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = confidence,
                        onValueChange = { confidence = it },
                        onValueChangeFinished = { onChange { s -> s.copy(minConfidence = confidence.toDouble()) } },
                        valueRange = 0.6f..0.95f,
                        steps = 6,
                    )
                    Text("Applies to images scanned from now on. Use Re-sort everything to apply it to all.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (trashSupported) item {
                SwitchItem(
                    "Move to trash instead of deleting",
                    if (settings.useTrash) "You can restore images for 30 days. Space is freed when the trash is emptied."
                    else "Images are deleted permanently and space is freed right away.",
                    settings.useTrash,
                ) { v -> onChange { it.copy(useTrash = v) } }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { Header("Reminders") }
            item {
                SwitchItem(
                    "Monthly clean-up reminder",
                    if (settings.monthlyReminder && !notificationsAllowed) "Allow notifications to get the reminder."
                    else "Sorts new images while charging and lets you know when many greetings pile up.",
                    settings.monthlyReminder && notificationsAllowed,
                ) { v ->
                    onChange { it.copy(monthlyReminder = v) }
                    if (v && !notificationsAllowed) onReminderOn()
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { Header("Learning") }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        if (learnedCount == 0) "Move an image to another category and ForwardSweep learns from it. Similar images are re-sorted straight away."
                        else "Learned from ${plural(learnedCount, "image")} you moved. Similar images are re-sorted straight away, and deleted greetings and memes count as confirmations.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (learnedCount > 0) OutlinedButton(onClick = { resetDialog = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Forget what it learned") }
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { Header("Library") }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Images you moved yourself keep your choice.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { rescanDialog = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Re-sort everything") }
                }
            }
            item {
                Text(
                    "Everything runs on your phone. ForwardSweep has no internet permission, and no image ever leaves the device.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (resetDialog) AlertDialog(
        onDismissRequest = { resetDialog = false },
        title = { Text("Forget what ForwardSweep learned?") },
        text = { Text("Your moves are undone and every image goes back to the default sorting.") },
        confirmButton = { Button(onClick = { resetDialog = false; onResetLearning() }) { Text("Forget") } },
        dismissButton = { TextButton(onClick = { resetDialog = false }) { Text("Cancel") } },
    )

    if (rescanDialog) AlertDialog(
        onDismissRequest = { rescanDialog = false },
        title = { Text("Re-sort everything?") },
        text = { Text("Every image is checked again with your current settings. This can take a while on a large library.") },
        confirmButton = { Button(onClick = { rescanDialog = false; onRescanAll() }) { Text("Re-sort") } },
        dismissButton = { TextButton(onClick = { rescanDialog = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Header(text: String) = Text(
    text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
)

@Composable
private fun SwitchItem(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) = ListItem(
    headlineContent = { Text(title) },
    supportingContent = { Text(detail) },
    trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange),
)
