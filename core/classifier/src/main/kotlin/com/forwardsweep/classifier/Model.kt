package com.forwardsweep.classifier

/** Output categories. Only [isJunk] categories may ever be pre-selected for deletion. */
enum class Category(val label: String, val isJunk: Boolean) {
    REAL_PHOTO("Real photo or video", isJunk = false),
    GREETING("Greeting", isJunk = true),
    MEME("Meme", isJunk = true),
    SCREENSHOT("Screenshot / Document", isJunk = false),
    UNCERTAIN("Uncertain", isJunk = false);

    companion object {
        /** Categories the scorer assigns probabilities to (Uncertain is a decision, not a class). */
        val SCORED = listOf(REAL_PHOTO, GREETING, MEME, SCREENSHOT)
    }
}

/** Metadata available cheaply from MediaStore (or the file system in the harness). */
data class ImageMeta(
    val id: String,
    val displayName: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val mimeType: String? = null,
    /** e.g. "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/" */
    val relativePath: String? = null,
    val mediaType: MediaType = MediaType.IMAGE,
    /** Video length; 0 for images. */
    val durationMs: Long = 0,
)

enum class MediaType { IMAGE, VIDEO }

/** Platform-neutral ARGB pixel buffer. Android adapters fill it from a downsampled Bitmap. */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "pixel buffer size mismatch" }
    }

    /** Area-average downscale so the longest side is at most [maxSide]. */
    fun downscaled(maxSide: Int): RgbImage {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return this
        val scale = maxSide.toDouble() / longest
        val w = maxOf(1, (width * scale).toInt())
        val h = maxOf(1, (height * scale).toInt())
        return resized(w, h)
    }

    fun resized(w: Int, h: Int): RgbImage {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = y * height / h
            val y1 = maxOf(y0 + 1, (y + 1) * height / h)
            for (x in 0 until w) {
                val x0 = x * width / w
                val x1 = maxOf(x0 + 1, (x + 1) * width / w)
                var r = 0L; var g = 0L; var b = 0L
                for (yy in y0 until y1) {
                    val row = yy * width
                    for (xx in x0 until x1) {
                        val p = pixels[row + xx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                }
                val n = (y1 - y0) * (x1 - x0)
                out[y * w + x] = (0xFF shl 24) or
                    ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
            }
        }
        return RgbImage(w, h, out)
    }
}

/** Normalized (0..1) bounding box. */
data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val area: Double get() = (right - left).coerceAtLeast(0.0) * (bottom - top).coerceAtLeast(0.0)
    val centerY: Double get() = (top + bottom) / 2
}

data class TextBlock(val text: String, val box: Box)

data class OcrResult(val blocks: List<TextBlock>) {
    val fullText: String by lazy { blocks.joinToString("\n") { it.text } }
    val lineCount: Int get() = blocks.size

    /** Fraction of the image covered by text boxes (approximate; overlaps are rare in OCR output). */
    val textAreaRatio: Double by lazy { blocks.sumOf { it.box.area }.coerceAtMost(1.0) }

    /** Fraction of text area sitting in the top or bottom 28% of the image, the classic meme layout. */
    val captionBandRatio: Double by lazy {
        val total = blocks.sumOf { it.box.area }
        if (total <= 0) 0.0
        else blocks.filter { it.box.centerY < 0.28 || it.box.centerY > 0.72 }.sumOf { it.box.area } / total
    }

    companion object {
        val EMPTY = OcrResult(emptyList())
    }
}

data class ExifFeatures(
    val make: String? = null,
    val model: String? = null,
    val hasExposure: Boolean = false,
    val hasFocalLength: Boolean = false,
    val hasGps: Boolean = false,
    val hasDateTimeOriginal: Boolean = false,
    val software: String? = null,
) {
    /**
     * 0..1 evidence that a camera produced this file. Absence (0.0) is *not* evidence of junk:
     * WhatsApp strips EXIF from every image it sends.
     */
    val cameraEvidence: Double
        get() {
            var e = 0.0
            if (!make.isNullOrBlank() || !model.isNullOrBlank()) e += 0.45
            if (hasExposure) e += 0.2
            if (hasFocalLength) e += 0.15
            if (hasGps) e += 0.1
            if (hasDateTimeOriginal) e += 0.1
            return e.coerceAtMost(1.0)
        }

    val cameraName: String? get() = listOfNotNull(make, model).joinToString(" ").ifBlank { null }

    companion object {
        val NONE = ExifFeatures()
    }
}

data class Classification(
    val id: String,
    val category: Category,
    /** Probability of the top scored category (before the Uncertain decision). */
    val confidence: Double,
    val probabilities: Map<Category, Double>,
    val reasons: List<String>,
    /** Never pre-selected and shown with a shield badge (real photo with faces). */
    val protected: Boolean,
    val preselected: Boolean,
    val pHash: Long,
    val faces: Int,
    /** The category the scores pointed to, even if the final decision is Uncertain. */
    val topCategory: Category,
    /** Raw feature values, for debugging, tuning and the "why" sheet in the preview UI. */
    val features: Map<String, Double> = emptyMap(),
    /** Everything needed to re-decide this image later (after the user teaches the model). */
    val state: ScoreState? = null,
    /** CLIP image embedding, when the model is available. */
    val embedding: FloatArray? = null,
)

/**
 * The default scorer's output for one image, before personalisation and safety rules. Stored
 * per image so the library can be re-sorted after each correction without decoding images.
 */
data class ScoreState(
    /** Logits in [Category.SCORED] order. */
    val baseLogits: DoubleArray,
    val baseReasons: List<String>,
    val exifEvidence: Double,
    val faces: Int,
    /** True when text recognition ran on this image (so [words] = 0 really means no text). */
    val ocrRan: Boolean,
    val words: Int,
    /** A greeting phrase (Good Morning, शुभ सकाळ, …) was recognised in the text. */
    val greetingKeyword: Boolean = false,
)
