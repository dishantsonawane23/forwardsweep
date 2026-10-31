package com.forwardsweep.harness

import com.forwardsweep.classifier.Category
import com.forwardsweep.classifier.ClassificationPipeline
import com.forwardsweep.classifier.Classification
import com.forwardsweep.classifier.ClassifierConfig
import com.forwardsweep.classifier.DuplicateGrouper
import com.forwardsweep.classifier.OcrEngine
import com.forwardsweep.classifier.OnnxClipEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """
ForwardSweep harness — run the on-device classifier on a folder of images.

  forwardsweep classify <dir> [options]   classify every image, write report.html + results.csv
  forwardsweep eval <labeled-dir> [opts]  dir has subfolders real/ greeting/ meme/ screenshot/;
                                        prints precision/recall and safety metrics

Options:
  --ocr sidecar|tesseract|none   OCR source (default: sidecar = <img>.signals.json)
  --clip <model.onnx> <prompts.tsv>   enable CLIP zero-shot (see eval/export_clip.py)
  --threshold <0..1>             minimum confidence (default 0.75)
  --out <dir>                    output folder (default: eval/out)
  --max-real-junk <rate>         eval: fail (exit 2) if real→junk rate exceeds this (default 0.005)
"""

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "bmp", "gif")

fun main(args: Array<String>) {
    if (args.size < 2) { println(USAGE); exitProcess(1) }
    val opts = parseOptions(args.drop(2))
    val config = ClassifierConfig(minConfidence = opts["threshold"]?.first()?.toDouble() ?: 0.75)
    val sidecar = SidecarSignals()
    val ocr: OcrEngine? = when (opts["ocr"]?.first() ?: "sidecar") {
        "none" -> null
        "tesseract" -> if (TesseractOcr.isAvailable()) TesseractOcr() else error("tesseract not on PATH")
        else -> sidecar
    }
    val clip = opts["clip"]?.let { OnnxClipEngine(File(it[0]), File(it[1])) }
    val pipeline = ClassificationPipeline(config, ocr = ocr, clip = clip, faceDetector = sidecar)
    val out = File(opts["out"]?.first() ?: "eval/out").apply { mkdirs() }
    val root = File(args[1])
    require(root.isDirectory) { "not a directory: $root" }

    val signals = listOfNotNull("EXIF", "pixel traits", "pHash", ocr?.let { "OCR(${opts["ocr"]?.first() ?: "sidecar"})" }, "faces(sidecar)", clip?.let { "CLIP" })
    println("Signals: ${signals.joinToString(", ")}   threshold=${config.minConfidence}")

    val files = root.walkTopDown().filter { it.isFile && it.extension.lowercase() in IMAGE_EXT }.sorted().toList()
    val started = System.nanoTime()
    val results = runBlocking(Dispatchers.Default) {
        files.map { f -> async { f to pipeline.classify(FileImageInput(f)) } }.awaitAll()
    }
    val ms = (System.nanoTime() - started) / 1e6
    println("Classified ${results.size} images in %.1f s (%.0f ms/image, ${Runtime.getRuntime().availableProcessors()} threads)".format(ms / 1000, ms / maxOf(1, results.size)))

    val groups = DuplicateGrouper.group(results.map { (f, c) -> Triple(c.id, c.pHash, f.length()) }, config.duplicateDistance)
    Report.writeCsv(File(out, "results.csv"), results, root)
    Report.writeHtml(File(out, "report.html"), results, groups, root, signals)
    printSummary(results, groups)

    when (args[0]) {
        "classify" -> Unit
        "eval" -> {
            val ok = Evaluation.run(results, root, maxRealJunk = opts["max-real-junk"]?.first()?.toDouble() ?: 0.005)
            println("\nReport: ${File(out, "report.html").absolutePath}")
            if (!ok) exitProcess(2)
            return
        }
        else -> { println(USAGE); exitProcess(1) }
    }
    println("\nReport: ${File(out, "report.html").absolutePath}")
}

private fun printSummary(results: List<Pair<File, Classification>>, groups: List<List<String>>) {
    println()
    println("%-24s %6s %10s %12s".format("Category", "Count", "Size", "Preselected"))
    for (c in Category.entries) {
        val items = results.filter { it.second.category == c }
        if (items.isEmpty()) continue
        println("%-24s %6d %10s %12d".format(c.label, items.size, Report.humanSize(items.sumOf { it.first.length() }), items.count { it.second.preselected }))
    }
    val pre = results.filter { it.second.preselected }
    println("Pre-selected for deletion: ${pre.size} images, ${Report.humanSize(pre.sumOf { it.first.length() })}")
    println("Protected (faces): ${results.count { it.second.protected }}")
    println("Duplicate groups: ${groups.size} (${groups.sumOf { it.size - 1 }} redundant copies)")
}

private fun parseOptions(rest: List<String>): Map<String, List<String>> {
    val out = HashMap<String, List<String>>()
    var i = 0
    while (i < rest.size) {
        val key = rest[i].removePrefix("--")
        val arity = if (key == "clip") 2 else 1
        out[key] = rest.subList(i + 1, minOf(rest.size, i + 1 + arity))
        i += 1 + arity
    }
    return out
}
