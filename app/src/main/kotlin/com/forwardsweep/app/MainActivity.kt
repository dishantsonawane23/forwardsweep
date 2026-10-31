package com.forwardsweep.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.forwardsweep.app.ui.AnimatedBytes
import com.forwardsweep.app.ui.HomeScreen
import com.forwardsweep.app.ui.ForwardSweepTheme
import com.forwardsweep.app.ui.PreviewScreen
import com.forwardsweep.app.ui.ReviewActions
import com.forwardsweep.app.ui.ReviewScreen
import com.forwardsweep.app.ui.SettingsScreen
import com.forwardsweep.app.ui.plural
import com.forwardsweep.classifier.Category

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ForwardSweepTheme { Surface(color = MaterialTheme.colorScheme.background) { App(vm) } } }
    }
}

private enum class Access { NONE, PARTIAL, FULL }

private fun Context.granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

private fun Context.photoAccess(): Access = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_MEDIA_IMAGES) -> Access.FULL
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.FULL
    else -> Access.NONE
}

private fun Context.videoAccess() =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.READ_MEDIA_VIDEO)

private fun Context.notificationsAllowed() =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)

/** Photo and video permissions only. Notifications are asked for later, when the user turns the reminder on. */
private fun photoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun App(vm: AppViewModel) {
    val ctx = LocalContext.current
    var access by remember { mutableStateOf(ctx.photoAccess()) }
    var notifications by remember { mutableStateOf(ctx.notificationsAllowed()) }
    var videos by remember { mutableStateOf(ctx.videoAccess()) }

    // Permissions can change in system settings while the app is in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { access = ctx.photoAccess(); notifications = ctx.notificationsAllowed(); videos = ctx.videoAccess() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { access = ctx.photoAccess() }
    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        videos = ok
        if (ok) vm.scan()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = it }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onConsentResult(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(Unit) { vm.consentRequests.collect { consentLauncher.launch(IntentSenderRequest.Builder(it).build()) } }

    if (access != Access.FULL) {
        PermissionScreen(partial = access == Access.PARTIAL) { photoLauncher.launch(photoPermissions()) }
        return
    }
    LaunchedEffect(Unit) { vm.autoScan() }

    val state by vm.state.collectAsState()
    val stack by vm.backStack.collectAsState()
    val focused by vm.focusedUri.collectAsState()
    val learned by vm.learnedCount.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.learnEvents.collect { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it) } }
    val gridStates = remember { HashMap<Category, LazyStaggeredGridState>() }
    val actions = remember(vm) {
        object : ReviewActions {
            override fun toggle(uri: String) { vm.toggle(uri) }
            override fun setSelected(uris: Collection<String>, value: Boolean) { vm.setSelected(uris, value) }
            override fun selectionSnapshot() = vm.selectionSnapshot()
            override fun applyDrag(base: Map<String, Boolean>, range: Collection<String>, value: Boolean) = vm.applyDrag(base, range, value)
            override fun moveTo(uris: Collection<String>, category: Category) { vm.moveTo(uris, category) }
            override fun delete(uris: Collection<String>) { vm.delete(uris) }
        }
    }
    BackHandler(enabled = stack.size > 1) { vm.back() }

    SharedTransitionLayout {
        AnimatedContent(
            targetState = stack.last(),
            transitionSpec = {
                val previewInvolved = initialState is Screen.Preview || targetState is Screen.Preview
                if (previewInvolved) fadeIn(tween(250)) togetherWith fadeOut(tween(250))
                else {
                    val forward = targetState != Screen.Home
                    (slideInHorizontally(tween(300)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(300))) togetherWith
                        (slideOutHorizontally(tween(300)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(200)))
                }
            },
            label = "screens",
        ) { screen ->
            when (screen) {
                Screen.Home -> HomeScreen(
                    state = state,
                    onOpen = { vm.navigate(Screen.Review(it)) },
                    onSettings = { vm.navigate(Screen.Settings) },
                    onScan = { vm.scan() },
                    onCleanUp = { vm.delete() },
                    askForVideos = !videos && state.settings.includeVideos,
                    onAllowVideos = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) videoLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO) },
                )
                is Screen.Review -> {
                    val rows by vm.images(screen.category).collectAsState()
                    ReviewScreen(
                        category = screen.category,
                        rows = rows,
                        selected = state.selected,
                        gridState = gridStates.getOrPut(screen.category) { LazyStaggeredGridState() },
                        focusedUri = focused,
                        actions = actions,
                        animatedScope = this@AnimatedContent,
                        onBack = { vm.back() },
                        onOpen = { vm.focusedUri.value = it; vm.navigate(Screen.Preview(screen.category, it)) },
                    )
                }
                is Screen.Preview -> {
                    val rows by vm.images(screen.category).collectAsState()
                    PreviewScreen(
                        rows = rows.orEmpty(),
                        startUri = screen.startUri,
                        selected = state.selected,
                        actions = actions,
                        animatedScope = this@AnimatedContent,
                        onPageChanged = { vm.focusedUri.value = it },
                        onClose = { vm.back() },
                    )
                }
                Screen.Settings -> SettingsScreen(
                    settings = state.settings,
                    notificationsAllowed = notifications,
                    onChange = { vm.updateSettings(it) },
                    onReminderOn = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onRescanAll = { vm.rescanAll(); vm.back() },
                    learnedCount = learned,
                    onResetLearning = { vm.resetLearning() },
                    onBack = { vm.back() },
                )
            }
        }
    }

    // Learning messages float above every screen's bottom bar.
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 84.dp), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(snackbar)
    }

    val result by vm.deleteResult.collectAsState()
    result?.let { r ->
        AlertDialog(
            onDismissRequest = vm::dismissResult,
            icon = { Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(if (r.trashed) "Moved to trash" else "Cleaned up") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedBytes(
                        r.bytes, MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary,
                        startFromZero = true,
                    )
                    Text(if (r.trashed) "will be freed" else "freed", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        buildString {
                            append(plural(r.removed, "item"))
                            if (r.removed < r.requested) append(" of ${r.requested}")
                            append(if (r.trashed) " moved to the trash. The space comes back when you empty it, or after 30 days." else " deleted.")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissResult) { Text("Done") } },
        )
    }
}

@Composable
private fun PermissionScreen(partial: Boolean, onAllow: () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Clear out WhatsApp forwards", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                if (partial) "You shared only some photos. ForwardSweep needs all photos and videos to find WhatsApp greetings and memes."
                else "ForwardSweep finds Good Morning images and videos, festival wishes and memes, so you can delete them in one go. " +
                    "Your real photos are never selected automatically.",
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Everything stays on your phone.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAllow) { Text(if (partial) "Allow all photos" else "Allow photo access") }
        }
    }
}
