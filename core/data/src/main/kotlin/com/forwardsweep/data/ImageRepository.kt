package com.forwardsweep.data

import android.content.ContentResolver
import android.content.Context
import android.util.Log
import com.forwardsweep.classifier.Category
import com.forwardsweep.classifier.ClassificationPipeline
import com.forwardsweep.classifier.Classification
import com.forwardsweep.classifier.DuplicateGrouper
import com.forwardsweep.classifier.ClassifierConfig
import com.forwardsweep.classifier.PersonalModel
import com.forwardsweep.classifier.ScoreState
import com.forwardsweep.classifier.Scorer
import com.forwardsweep.data.db.CategorySummary
import com.forwardsweep.data.db.CorrectionEntity
import com.forwardsweep.data.db.ForwardSweepDatabase
import com.forwardsweep.data.db.ScannedImageEntity
import com.forwardsweep.data.db.SelectRow
import com.forwardsweep.data.media.MediaItem
import com.forwardsweep.data.media.MediaStoreSource
import com.forwardsweep.data.media.ScanScope
import com.forwardsweep.data.settings.Settings
import com.forwardsweep.ml.MediaImageInput
import com.forwardsweep.ml.MediaVideoInput
import com.forwardsweep.classifier.MediaType
import com.forwardsweep.ml.PipelineFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Bump when classifier logic changes in a way that should reclassify cached images.
 * 2: CLIP (MobileCLIP2-S0) on device, stored scores + embeddings for learning.
 * 3: CLIP swapped to PE-Core-T (open licence); embeddings change, so everything is re-scored.
 * 4: greetings need 5+ words or a greeting phrase; stores whether a phrase was found.
 */
const val CLASSIFIER_VERSION = 4

class ImageRepository(
    private val context: Context,
    private val db: ForwardSweepDatabase,
    private val media: MediaStoreSource,
    private val resolver: ContentResolver,
    private val pipelines: PipelineFactory,
) {
    private val images = db.images()

    val summary: Flow<List<CategorySummary>> = images.summary()

    fun byCategory(category: Category): Flow<List<ScannedImageEntity>> = images.byCategory(category)

    val selectRows: Flow<List<SelectRow>> = images.selectRows()

    suspend fun markAllForRescan() = images.markAllForRescan()

    data class ScanPlan(val toScan: List<MediaItem>, val removed: List<String>, val totalInLibrary: Int)

    /** Diff MediaStore against the cache: only new, modified or outdated images are scanned. */
    suspend fun plan(scope: ScanScope, includeVideos: Boolean = true): ScanPlan = withContext(Dispatchers.IO) {
        val items = media.query(scope, includeVideos)
        val cached = images.stamps().associateBy { it.uri }
        val present = items.mapTo(HashSet()) { it.uri.toString() }
        val toScan = items.filter { item ->
            val stamp = cached[item.uri.toString()]
            stamp == null || stamp.dateModified != item.dateModified || stamp.classifierVersion != CLASSIFIER_VERSION
        }
        ScanPlan(toScan, cached.keys.filter { it !in present }, items.size)
    }

    /**
     * Scans up to [maxItems] pending images in batches, writing each batch to Room so an
     * interrupted scan resumes where it stopped. Returns the number of images classified.
     */
    suspend fun scan(
        settings: Settings,
        maxItems: Int = Int.MAX_VALUE,
        isStopped: () -> Boolean = { false },
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        val plan = plan(settings.scope, settings.includeVideos)
        if (plan.removed.isNotEmpty()) plan.removed.chunked(900).forEach { images.delete(it) }
        val work = plan.toScan.take(maxItems)
        val pipeline = pipeline(settings)
        var done = 0
        onProgress(0, work.size)
        for (batch in work.chunked(BATCH_SIZE)) {
            if (isStopped()) break
            // A rescan (file edited, classifier upgraded) must not undo the user's own corrections.
            val previous = images.getAll(batch.map { it.uri.toString() }).associateBy { it.uri }
            val rows = batch.mapNotNull { item ->
                val row = classify(pipeline, item) ?: return@mapNotNull null
                val userCategory = previous[row.uri]?.userCategory ?: return@mapNotNull row
                row.copy(userCategory = userCategory, effectiveCategory = userCategory, preselected = userCategory.isJunk)
            }
            images.upsert(rows)
            done += batch.size
            onProgress(done, work.size)
        }
        refreshDuplicates(pipeline.duplicateDistance)
        return done
    }

    private suspend fun pipeline(settings: Settings): ClassificationPipeline {
        val config = settings.classifierConfig()
        return pipelines.create(config, personalModel(config))
    }

    /** The personal model, trained on every correction and confirmation the user has made. */
    private suspend fun personalModel(config: ClassifierConfig): PersonalModel = withContext(Dispatchers.Default) {
        PersonalModel(matchDistance = config.duplicateDistance).apply {
            train(db.corrections().all().sortedBy { it.correctedAt }.map { c ->
                PersonalModel.Example(c.pHash, c.embedding?.toFloats(), c.baseLogits?.toLogits(), c.toCategory, c.weight)
            })
        }
    }

    private suspend fun classify(pipeline: ClassificationPipeline, item: MediaItem): ScannedImageEntity? {
        val input = if (item.mediaType == MediaType.VIDEO) MediaVideoInput(context, item.uri, item.toMeta())
        else MediaImageInput(resolver, item.uri, item.toMeta())
        return try {
            pipeline.classify(input).toEntity(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Corrupt or unsupported file: skip it rather than failing the whole scan.
            Log.w(TAG, "skipping ${item.uri}: ${e.message}")
            null
        } finally {
            (input as? MediaImageInput)?.release()
            (input as? MediaVideoInput)?.release()
        }
    }

    private suspend fun refreshDuplicates(maxDistance: Int) = withContext(Dispatchers.Default) {
        val rows = images.hashes()
        val groups = DuplicateGrouper.group(rows.map { Triple(it.uri, it.pHash, it.sizeBytes) }, maxDistance)
        images.replaceDuplicateGroups(groups)
    }

    /**
     * The user moved images to another category. Each becomes a training example; the images
     * and their near-duplicates take the new label at once, and the rest of the library is
     * re-sorted with what was learned (see [relearn]).
     */
    suspend fun correct(uris: Collection<String>, to: Category, settings: Settings): Int {
        val now = System.currentTimeMillis()
        for (uri in uris) {
            val row = images.get(uri) ?: continue
            if (row.effectiveCategory == to) continue
            db.corrections().insert(CorrectionEntity(uri, row.pHash, row.category, to, now, 1.0, row.baseLogits, row.embedding))
            val targets = row.duplicateGroup?.let { images.duplicateMembers(it) } ?: listOf(uri)
            images.setUserCategory(targets, to, preselected = to.isJunk)
        }
        return relearn(settings)
    }

    /**
     * Deleted greetings and memes are implicit confirmations: weaker examples (0.5) that keep
     * teaching the model what this user considers junk. Call before the rows are forgotten.
     */
    suspend fun recordDeleted(uris: List<String>) {
        val corrected = db.corrections().all().filter { it.weight >= 1.0 }.mapTo(HashSet()) { it.uri }
        val now = System.currentTimeMillis()
        rows(uris).filter { it.effectiveCategory.isJunk && it.uri !in corrected && it.embedding != null }.forEach { row ->
            db.corrections().insert(CorrectionEntity(row.uri, row.pHash, row.category, row.effectiveCategory, now, 0.5, row.baseLogits, row.embedding))
        }
    }

    /**
     * Retrains the personal model and re-sorts every image the user hasn't labelled, from the
     * stored scores (no image is decoded, so this takes well under a second). Returns how many
     * images changed category.
     */
    suspend fun relearn(settings: Settings): Int = withContext(Dispatchers.Default) {
        val config = settings.classifierConfig()
        val scorer = Scorer(config, personalModel(config))
        val changed = images.redecidable().mapNotNull { row ->
            val state = row.scoreState() ?: return@mapNotNull null
            val c = scorer.decide(row.uri, row.pHash, state, row.embedding?.toFloats())
            if (c.category == row.category && c.preselected == row.preselected && c.protected == row.protectedImage) return@mapNotNull null
            row.copy(
                category = c.category,
                effectiveCategory = c.category,
                topCategory = c.topCategory,
                confidence = c.confidence,
                probabilities = c.probabilitiesString(),
                reasons = c.reasons.joinToString("\n"),
                protectedImage = c.protected,
                preselected = c.preselected,
            )
        }
        changed.chunked(200).forEach { images.upsert(it) }
        changed.size
    }

    /** Number of images the user has moved (what the personal model learned from). */
    val learnedCount: Flow<Int> = db.corrections().countFlow()

    /** Forget everything learned from the user and re-sort with the defaults. */
    suspend fun resetLearning(settings: Settings) {
        db.corrections().clear()
        images.clearUserCategories()
        relearn(settings)
    }

    suspend fun preselectedUris(): List<String> = images.preselectedUris()

    suspend fun countNewSince(category: Category, since: Long) = images.countNewSince(category, since)

    suspend fun rows(uris: List<String>): List<ScannedImageEntity> = uris.chunked(900).flatMap { images.getAll(it) }

    suspend fun forget(uris: List<String>) = uris.chunked(900).forEach { images.delete(it) }

    private fun Classification.toEntity(item: MediaItem) = ScannedImageEntity(
        uri = item.uri.toString(),
        mediaId = item.id,
        displayName = item.displayName,
        relativePath = item.relativePath,
        source = item.source,
        sizeBytes = item.sizeBytes,
        width = item.width,
        height = item.height,
        dateTaken = item.dateTaken,
        dateModified = item.dateModified,
        pHash = pHash,
        category = category,
        topCategory = topCategory,
        confidence = confidence,
        probabilities = probabilitiesString(),
        reasons = reasons.joinToString("\n"),
        protectedImage = protected,
        preselected = preselected,
        faces = faces,
        effectiveCategory = category,
        scannedAt = System.currentTimeMillis(),
        classifierVersion = CLASSIFIER_VERSION,
        baseLogits = state?.baseLogits?.joinToString(",") { "%.4f".format(java.util.Locale.ROOT, it) },
        baseReasons = state?.baseReasons?.joinToString("\n").orEmpty(),
        exifEvidence = state?.exifEvidence ?: 0.0,
        ocrRan = state?.ocrRan ?: false,
        words = state?.words ?: 0,
        greetingKeyword = state?.greetingKeyword ?: false,
        embedding = embedding?.toBytes(),
        mediaType = item.mediaType,
        durationMs = item.durationMs,
    )

    private fun Classification.probabilitiesString() =
        Category.SCORED.joinToString(",") { "%.4f".format(java.util.Locale.ROOT, probabilities[it] ?: 0.0) }

    private fun ScannedImageEntity.scoreState(): ScoreState? = baseLogits?.toLogits()?.let { logits ->
        ScoreState(logits, baseReasons.lines().filter { it.isNotBlank() }, exifEvidence, faces, ocrRan, words, greetingKeyword)
    }

    companion object {
        private const val TAG = "ImageRepository"
        /** Small, so a stopped scan (thermal, Doze) loses only a few seconds of work. */
        const val BATCH_SIZE = 10
    }
}
