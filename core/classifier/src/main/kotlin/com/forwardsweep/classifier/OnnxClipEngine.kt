package com.forwardsweep.classifier

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Zero-shot classifier using a CLIP-style image encoder exported to ONNX (see
 * eval/export_clip.py). Text prompts are embedded offline, so the phone only runs the image
 * tower (Meta PE-Core-T, Apache-2.0, 32 MB fp32, 384 px input).
 *
 * Prompt file format (UTF-8, tab-separated), produced by the export script:
 * ```
 * #size	256
 * #mean	0.0	0.0	0.0
 * #std	1.0	1.0	1.0
 * #scale	100.0
 * GREETING	a good morning greeting card with flowers and text	0.0123,-0.0456,...
 * ```
 * Works with both `onnxruntime` (JVM) and `onnxruntime-android`: same Java API.
 */
class OnnxClipEngine(modelBytes: ByteArray, promptFile: String) : ClipEngine, AutoCloseable {
    private class Prompt(val category: Category, val text: String, val embedding: FloatArray)

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions())
    private val inputName = session.inputNames.first()
    private var size = 224
    private var mean = floatArrayOf(0.481f, 0.458f, 0.408f)
    private var std = floatArrayOf(0.269f, 0.261f, 0.276f)
    private var logitScale = 100.0
    private val prompts: List<Prompt>

    init {
        val list = ArrayList<Prompt>()
        promptFile.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split('\t')
            when (parts[0]) {
                "#size" -> size = parts[1].toInt()
                "#mean" -> mean = FloatArray(3) { parts[it + 1].toFloat() }
                "#std" -> std = FloatArray(3) { parts[it + 1].toFloat() }
                "#scale" -> logitScale = parts[1].toDouble()
                else -> if (!parts[0].startsWith("#")) {
                    list += Prompt(Category.valueOf(parts[0]), parts[1], normalize(parts[2].split(',').map { it.toFloat() }.toFloatArray()))
                }
            }
        }
        require(list.isNotEmpty()) { "no prompts in prompt file" }
        prompts = list
    }

    constructor(model: File, prompts: File) : this(model.readBytes(), prompts.readText())

    override suspend fun analyze(pixels: RgbImage): ClipResult {
        val img = centerCrop(pixels).resized(size, size)
        val plane = size * size
        val data = FloatBuffer.allocate(3 * plane)
        for (c in 0..2) {
            val shift = 16 - 8 * c
            for (i in 0 until plane) data.put((((img.pixels[i] shr shift) and 0xFF) / 255f - mean[c]) / std[c])
        }
        data.rewind()
        val embedding = OnnxTensor.createTensor(env, data, longArrayOf(1, 3, size.toLong(), size.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { out ->
                @Suppress("UNCHECKED_CAST")
                normalize((out[0].value as Array<FloatArray>)[0])
            }
        }
        // Softmax over all prompts, then sum probabilities per category.
        val logits = prompts.map { p -> logitScale * p.embedding.indices.sumOf { (p.embedding[it] * embedding[it]).toDouble() } }
        val max = logits.max()
        val exps = logits.map { exp(it - max) }
        val total = exps.sum()
        val result = HashMap<Category, Double>()
        prompts.forEachIndexed { i, p -> result[p.category] = (result[p.category] ?: 0.0) + exps[i] / total }
        return ClipResult(result, embedding)
    }

    override fun close() = session.close()

    private fun centerCrop(img: RgbImage): RgbImage {
        val side = minOf(img.width, img.height)
        val x0 = (img.width - side) / 2
        val y0 = (img.height - side) / 2
        val out = IntArray(side * side) { i -> img.pixels[(y0 + i / side) * img.width + x0 + i % side] }
        return RgbImage(side, side, out)
    }

    private fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / n }
    }
}
