package com.forwardsweep.classifier

/**
 * One image to classify. Android implements this on top of a MediaStore URI; the harness on
 * top of a file. Loading is lazy so the pipeline only decodes what it needs.
 */
interface ImageInput {
    val meta: ImageMeta

    /** Decoded pixels, downsampled so the longest side is at most [maxSide]. */
    suspend fun loadPixels(maxSide: Int): RgbImage

    suspend fun readExif(): ExifFeatures
}

/** ML Kit Text Recognition on Android (Latin + Devanagari recognizers, results merged). */
fun interface OcrEngine {
    suspend fun recognize(input: ImageInput, pixels: RgbImage): OcrResult
}

/** ML Kit Face Detection on Android. Returns the number of faces. */
fun interface FaceDetector {
    suspend fun countFaces(input: ImageInput, pixels: RgbImage): Int
}

/** Zero-shot image classifier (CLIP). */
fun interface ClipEngine {
    suspend fun analyze(pixels: RgbImage): ClipResult
}

/**
 * [probabilities]: one per scored category (sums to ~1). [embedding]: the L2-normalised image
 * embedding, kept per image so the personal model can learn from corrections without decoding
 * images again.
 */
class ClipResult(val probabilities: Map<Category, Double>, val embedding: FloatArray)

/**
 * A video. [loadPixels] returns a representative (middle) frame; [loadFrames] returns frames at
 * the given positions (0..1 of the duration), skipping any that fail to decode.
 */
interface VideoInput : ImageInput {
    suspend fun loadFrames(maxSide: Int, positions: List<Double>): List<RgbImage>
}
