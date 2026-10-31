package com.forwardsweep.ml

import android.content.Context
import android.util.Log
import com.forwardsweep.classifier.ClassificationPipeline
import com.forwardsweep.classifier.ClassifierConfig
import com.forwardsweep.classifier.ClipEngine
import com.forwardsweep.classifier.PersonalModel
import com.forwardsweep.classifier.OnnxClipEngine
import java.io.File

/**
 * Builds the on-device pipeline. The CLIP model is optional: it is looked up in
 * `filesDir/models/` (downloaded) and then in `assets/models/` (bundled); if neither exists
 * the classifier runs on EXIF + OCR + pixel traits only.
 */
class PipelineFactory(private val context: Context) : AutoCloseable {
    private val ocr by lazy { MlKitOcrEngine() }
    private val faces by lazy { MlKitFaceDetector() }
    private val clip: ClipEngine? by lazy { loadClip() }

    val hasClip: Boolean get() = clip != null

    fun create(config: ClassifierConfig, personal: PersonalModel): ClassificationPipeline =
        ClassificationPipeline(config, ocr = ocr, clip = clip, faceDetector = faces, personal = personal)

    private fun loadClip(): ClipEngine? = runCatching {
        val dir = File(context.filesDir, "models")
        val model = File(dir, MODEL).takeIf { it.exists() }?.readBytes()
            ?: context.assets.open("models/$MODEL").use { it.readBytes() }
        val prompts = File(dir, PROMPTS).takeIf { it.exists() }?.readText()
            ?: context.assets.open("models/$PROMPTS").use { it.readBytes().decodeToString() }
        OnnxClipEngine(model, prompts)
    }.onFailure { Log.i(TAG, "CLIP model not available, running without it (${it.javaClass.simpleName})") }.getOrNull()

    override fun close() {
        ocr.close()
        faces.close()
        (clip as? AutoCloseable)?.close()
    }

    companion object {
        private const val TAG = "PipelineFactory"
        /**
         * Meta PE-Core-T (Apache-2.0), fp32, 32 MB. Apple MobileCLIP2 scored slightly higher but its
         * weights are research-only; dynamic int8 quantization of these models collapses accuracy.
         */
        const val MODEL = "clip_image.onnx"
        const val PROMPTS = "clip_prompts.tsv"
    }
}
