package com.forwardsweep.classifier

import kotlinx.coroutines.CancellationException

/**
 * Runs every available signal on one image and scores it. Each engine is optional and any
 * engine failure degrades to "signal missing" rather than failing the image.
 */
class ClassificationPipeline(
    private val config: ClassifierConfig = ClassifierConfig(),
    private val ocr: OcrEngine? = null,
    private val clip: ClipEngine? = null,
    private val faceDetector: FaceDetector? = null,
    val personal: PersonalModel = PersonalModel(matchDistance = config.duplicateDistance),
    private val textAnalyzer: TextAnalyzer = TextAnalyzer(),
) {
    private val scorer = Scorer(config, personal)

    val duplicateDistance: Int get() = config.duplicateDistance

    /** Re-decides an already scanned image from its stored [ScoreState] (no pixels needed). */
    fun redecide(id: String, pHash: Long, state: ScoreState, embedding: FloatArray?): Classification =
        scorer.decide(id, pHash, state, embedding)

    suspend fun classify(input: ImageInput): Classification {
        // Videos: three frames. The middle one stands in for "the image" (traits, pHash, faces);
        // CLIP looks at all three; OCR reads the middle and late frames, because Good Morning
        // videos often fade their text in.
        val frames = (input as? VideoInput)?.let { v -> attempt { v.loadFrames(config.decodeMaxSide, VIDEO_POSITIONS) } }
            ?.takeIf { it.isNotEmpty() }
        val pixels = frames?.get(frames.size / 2) ?: input.loadPixels(config.decodeMaxSide)
        val exif = if (frames != null) ExifFeatures.NONE else attempt { input.readExif() } ?: ExifFeatures.NONE
        val traits = ImageTraits.compute(pixels)
        val hash = PHash.compute(pixels)

        val ocrFrames = frames?.drop(frames.size / 2) ?: listOf(pixels)
        val ocrResult = ocr?.let { engine ->
            ocrFrames.mapNotNull { f -> attempt { engine.recognize(input, f) } }.maxByOrNull { it.fullText.length }
        }
        val text = ocrResult?.let(textAnalyzer::analyze)
        // Faces only matter for protection; skip the detector on obvious text-heavy graphics.
        val faces = if ((ocrResult?.textAreaRatio ?: 0.0) > 0.5) 0
        else faceDetector?.let { d -> attempt { d.countFaces(input, pixels) } }
        val clipResult = clip?.let { engine ->
            (frames ?: listOf(pixels)).mapNotNull { f -> attempt { engine.analyze(f) } }.let(::averageClip)
        }

        return scorer.score(Evidence(input.meta, exif, traits, ocrResult, text, clipResult, faces, hash))
    }

    /** Mean probabilities and mean (re-normalised) embedding over several frames. */
    private fun averageClip(results: List<ClipResult>): ClipResult? {
        if (results.isEmpty()) return null
        if (results.size == 1) return results[0]
        val probs = Category.SCORED.associateWith { c -> results.sumOf { it.probabilities[c] ?: 0.0 } / results.size }
        val dim = results[0].embedding.size
        val mean = FloatArray(dim) { d -> results.sumOf { it.embedding[d].toDouble() }.toFloat() }
        val norm = kotlin.math.sqrt(mean.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-6f)
        for (d in 0 until dim) mean[d] /= norm
        return ClipResult(probs, mean)
    }

    companion object {
        val VIDEO_POSITIONS = listOf(0.15, 0.5, 0.85)
    }

    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
