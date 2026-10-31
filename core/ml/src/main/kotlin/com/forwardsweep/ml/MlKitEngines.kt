package com.forwardsweep.ml

import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.forwardsweep.classifier.Box
import com.forwardsweep.classifier.FaceDetector
import com.forwardsweep.classifier.ImageInput
import com.forwardsweep.classifier.OcrEngine
import com.forwardsweep.classifier.OcrResult
import com.forwardsweep.classifier.RgbImage
import com.forwardsweep.classifier.TextBlock
import kotlinx.coroutines.tasks.await

private fun ImageInput.inputImage(pixels: RgbImage): InputImage =
    InputImage.fromBitmap((this as? MediaImageInput)?.bitmap ?: pixels.toBitmap(), 0)

/**
 * ML Kit text recognition. Runs the Latin and Devanagari recognizers and merges their lines:
 * greetings often mix "Good Morning" with "सुप्रभात", and each model only reads its own script well.
 */
class MlKitOcrEngine(private val devanagari: Boolean = true) : OcrEngine, AutoCloseable {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val deva = if (devanagari) TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) else null

    override suspend fun recognize(input: ImageInput, pixels: RgbImage): OcrResult {
        val image = input.inputImage(pixels)
        val w = image.width.toDouble()
        val h = image.height.toDouble()
        val latinLines = lines(latin.process(image).await(), w, h)
        val devaLines = deva?.let { lines(it.process(image).await(), w, h) }.orEmpty()
        return OcrResult(merge(latinLines, devaLines))
    }

    private fun lines(text: Text, w: Double, h: Double): List<TextBlock> =
        text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val r: Rect = line.boundingBox ?: return@mapNotNull null
            TextBlock(line.text, Box(r.left / w, r.top / h, r.right / w, r.bottom / h))
        }

    /** Keep every Latin line; add Devanagari lines that don't overlap one (or that add Devanagari text). */
    private fun merge(a: List<TextBlock>, b: List<TextBlock>): List<TextBlock> {
        val out = a.toMutableList()
        for (line in b) {
            val overlapping = out.indexOfFirst { iou(it.box, line.box) > 0.5 }
            when {
                overlapping < 0 -> out += line
                line.text.any { it in 'ऀ'..'ॿ' } -> out[overlapping] = line
            }
        }
        return out
    }

    private fun iou(a: Box, b: Box): Double {
        val ix = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0.0)
        val iy = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0.0)
        val inter = ix * iy
        val union = a.area + b.area - inter
        return if (union <= 0) 0.0 else inter / union
    }

    override fun close() {
        latin.close()
        deva?.close()
    }
}

class MlKitFaceDetector : FaceDetector, AutoCloseable {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.08f)
            .build(),
    )

    override suspend fun countFaces(input: ImageInput, pixels: RgbImage): Int =
        detector.process(input.inputImage(pixels)).await().size

    override fun close() = detector.close()
}
