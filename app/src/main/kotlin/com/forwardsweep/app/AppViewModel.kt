package com.forwardsweep.app

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forwardsweep.classifier.Category
import com.forwardsweep.data.DataGraph
import com.forwardsweep.data.db.CategorySummary
import com.forwardsweep.data.db.ScannedImageEntity
import com.forwardsweep.data.db.SelectRow
import com.forwardsweep.data.delete.DeleteRequest
import com.forwardsweep.data.delete.DeleteResult
import com.forwardsweep.data.delete.DeletionManager
import com.forwardsweep.data.delete.LegacyStep
import com.forwardsweep.data.scan.ScanProgress
import com.forwardsweep.data.settings.Settings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class Review(val category: Category) : Screen
    data class Preview(val category: Category, val startUri: String) : Screen
    data object Settings : Screen
}

/**
 * One ViewModel for the whole app: it is small, and selection, scanning and deletion are shared
 * by every screen. Screens read [state] and call the action functions.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph: DataGraph = (app as DataGraph.Provider).graph

    data class Totals(val count: Int = 0, val bytes: Long = 0)

    data class UiState(
        val summary: Map<Category, CategorySummary> = emptyMap(),
        val progress: ScanProgress = ScanProgress.Idle,
        /** URIs currently selected for deletion, across all categories. */
        val selected: Set<String> = emptySet(),
        val selectedTotals: Totals = Totals(),
        val selectedByCategory: Map<Category, Totals> = emptyMap(),
        val settings: Settings = Settings(),
        val loaded: Boolean = false,
    ) {
        val total: Totals get() = Totals(summary.values.sumOf { it.count }, summary.values.sumOf { it.totalBytes })
    }

    // ---- navigation ---------------------------------------------------------------------

    private val _backStack = MutableStateFlow<List<Screen>>(listOf(Screen.Home))
    val backStack: StateFlow<List<Screen>> = _backStack.asStateFlow()

    fun navigate(to: Screen) = _backStack.update { it + to }

    /** Returns false when already at the root, so the system can close the app. */
    fun back(): Boolean {
        if (_backStack.value.size <= 1) return false
        _backStack.update { it.dropLast(1) }
        return true
    }

    /** The image the preview was showing when the user left it, so the grid can scroll to it. */
    val focusedUri = MutableStateFlow<String?>(null)

    // ---- selection ----------------------------------------------------------------------

    /**
     * Explicit user choices. Anything without an entry follows the classifier's pre-selection,
     * so newly scanned greetings and memes arrive selected while the user's picks are kept.
     */
    private val overrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Nothing counts as selected until the saved choices are loaded, so Clean up stays disabled. */
    private val overridesLoaded = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            overrides.value = graph.settings.selectionOverrides.first()
            overridesLoaded.value = true
            // Save after changes settle; a drag fires many updates per second.
            overrides.drop(1).collectLatest { delay(300); graph.settings.saveSelectionOverrides(it) }
        }
    }

    private val selection: Flow<Pair<Set<String>, List<SelectRow>>> =
        combine(graph.images.selectRows, overrides, overridesLoaded) { rows, o, loaded ->
            if (!loaded) return@combine emptySet<String>() to rows
            rows.filter { o[it.uri] ?: (it.preselected && !it.protectedImage) }.mapTo(HashSet()) { it.uri } to rows
        }

    val state: StateFlow<UiState> = combine(
        graph.images.summary, graph.scheduler.progress, selection, graph.settings.settings,
    ) { summary, progress, (selected, rows), settings ->
        val chosen = rows.filter { it.uri in selected }
        UiState(
            summary = summary.associateBy { it.category },
            progress = progress,
            selected = selected,
            selectedTotals = Totals(chosen.size, chosen.sumOf { it.sizeBytes }),
            selectedByCategory = chosen.groupBy { it.effectiveCategory }
                .mapValues { (_, v) -> Totals(v.size, v.sumOf { it.sizeBytes }) },
            settings = settings,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private val rowCache = HashMap<Category, StateFlow<List<ScannedImageEntity>?>>()

    /**
     * Images of one category, cached so going Review → Preview → Review shows the list at once
     * (and the grid keeps its scroll position). Null until the first load.
     */
    fun images(category: Category): StateFlow<List<ScannedImageEntity>?> = rowCache.getOrPut(category) {
        graph.images.byCategory(category).stateIn(viewModelScope, SharingStarted.WhileSubscribed(60_000), null)
    }

    fun isSelected(uri: String) = uri in state.value.selected

    fun setSelected(uris: Collection<String>, value: Boolean) = overrides.update { o -> o + uris.associateWith { value } }

    fun toggle(uri: String) = setSelected(listOf(uri), !isSelected(uri))

    /** Snapshot and restore, used by drag-to-select so shrinking the drag range undoes itself. */
    fun selectionSnapshot(): Map<String, Boolean> = overrides.value

    fun applyDrag(base: Map<String, Boolean>, range: Collection<String>, value: Boolean) {
        overrides.value = base + range.associateWith { value }
    }

    // ---- scanning and corrections -------------------------------------------------------

    private var autoScanned = false

    /** Incremental scan on app start: cheap when nothing is new. */
    fun autoScan() {
        if (autoScanned) return
        autoScanned = true
        scan()
    }

    fun scan() = viewModelScope.launch {
        val settings = graph.settings.current()
        val pending = graph.images.plan(settings.scope, settings.includeVideos).toScan.size
        graph.scheduler.scanNow(pending)
        graph.scheduler.setMonthlyReminder(settings.monthlyReminder)
    }

    fun rescanAll() = viewModelScope.launch {
        graph.images.markAllForRescan()
        scan()
    }

    /** Move images to another category. The new category's default selection then applies. */
    /** Short messages about what ForwardSweep just learned, shown as snackbars. */
    private val _learnEvents = Channel<String>(Channel.BUFFERED)
    val learnEvents = _learnEvents.receiveAsFlow()

    /**
     * Move images to another category. Each move teaches the personal model; look-alikes in the
     * rest of the library are re-sorted right away. The new category's default selection applies.
     */
    fun moveTo(uris: Collection<String>, category: Category) = viewModelScope.launch {
        overrides.update { it - uris.toSet() }
        val resorted = graph.images.correct(uris, category, graph.settings.current())
        val moved = if (uris.size == 1) "Moved to ${category.label}" else "Moved ${uris.size} images to ${category.label}"
        _learnEvents.send(
            if (resorted > 0) "$moved. Learned from it and re-sorted $resorted similar image${if (resorted == 1) "" else "s"}."
            else "$moved. ForwardSweep will remember."
        )
    }

    val learnedCount: StateFlow<Int> = graph.images.learnedCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun resetLearning() = viewModelScope.launch {
        overrides.value = emptyMap()
        graph.images.resetLearning(graph.settings.current())
        _learnEvents.send("Forgot what ForwardSweep learned. Using the default sorting again.")
    }

    fun updateSettings(transform: (Settings) -> Settings) = viewModelScope.launch {
        val before = graph.settings.current()
        graph.settings.update(transform)
        val after = graph.settings.current()
        if (before.monthlyReminder != after.monthlyReminder) graph.scheduler.setMonthlyReminder(after.monthlyReminder)
        if (before.scope != after.scope || before.includeVideos != after.includeVideos) scan()
    }

    // ---- deletion -----------------------------------------------------------------------

    /** IntentSenders the Activity must launch (system delete/trash dialogs). */
    private val consents = Channel<IntentSender>(Channel.BUFFERED)
    val consentRequests = consents.receiveAsFlow()

    private val _deleteResult = MutableStateFlow<DeleteResult?>(null)
    val deleteResult: StateFlow<DeleteResult?> = _deleteResult.asStateFlow()
    fun dismissResult() { _deleteResult.value = null }

    private var pendingSystem: DeleteRequest.SystemConsent? = null
    private var legacyQueue = ArrayDeque<Uri>()
    private var legacyAll = emptyList<Uri>()

    /** Delete (or trash) [uris]. Defaults to everything currently selected. */
    fun delete(uris: Collection<String> = state.value.selected) = viewModelScope.launch {
        val batch = uris.take(DeletionManager.MAX_BATCH).map(Uri::parse)
        if (batch.isEmpty()) return@launch
        when (val req = graph.deletion.request(batch, preferTrash = graph.settings.current().useTrash)) {
            is DeleteRequest.SystemConsent -> {
                pendingSystem = req
                consents.send(req.intentSender)
            }
            is DeleteRequest.PerItem -> {
                legacyAll = req.uris
                legacyQueue = ArrayDeque(req.uris)
                continueLegacy()
            }
            is DeleteRequest.Done -> finished(req.result, batch)
        }
    }

    /** Called with the result of the consent dialog. */
    fun onConsentResult(granted: Boolean) = viewModelScope.launch {
        pendingSystem?.let { req ->
            pendingSystem = null
            // Verify against MediaStore either way: the user may have cancelled.
            val result = graph.deletion.finish(req.uris, trashed = req.trash)
            finished(result, req.uris)
            return@launch
        }
        if (!granted && legacyQueue.isNotEmpty()) legacyQueue.removeFirst() // skip the refused item
        continueLegacy()
    }

    private suspend fun continueLegacy() {
        while (legacyQueue.isNotEmpty()) {
            when (val step = graph.deletion.deleteLegacy(legacyQueue.first())) {
                LegacyStep.Deleted, is LegacyStep.Failed -> legacyQueue.removeFirst()
                is LegacyStep.NeedsConsent -> { consents.send(step.intentSender); return }
            }
        }
        if (legacyAll.isNotEmpty()) {
            val all = legacyAll
            legacyAll = emptyList()
            finished(graph.deletion.finish(all, trashed = false), all)
        }
    }

    private fun finished(result: DeleteResult, uris: List<Uri>) {
        if (result.removed > 0) _deleteResult.value = result
        // Drop overrides for images that no longer exist.
        val gone = uris.mapTo(HashSet()) { it.toString() }
        overrides.update { o -> o - gone }
    }
}
