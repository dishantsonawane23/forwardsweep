package com.forwardsweep.harness

import com.forwardsweep.classifier.Category
import com.forwardsweep.classifier.Classification
import java.io.File

/**
 * Precision/recall per category on a labeled folder. "Uncertain" is treated as abstaining:
 * it lowers recall (and coverage) but never counts as a wrong label. The metric that matters
 * most is how many real photos would be deleted, which must stay at (almost) zero.
 */
object Evaluation {
    private val LABELS = mapOf(
        "real" to Category.REAL_PHOTO, "real_photo" to Category.REAL_PHOTO, "photo" to Category.REAL_PHOTO,
        "greeting" to Category.GREETING, "greetings" to Category.GREETING,
        "meme" to Category.MEME, "memes" to Category.MEME,
        "screenshot" to Category.SCREENSHOT, "screenshots" to Category.SCREENSHOT, "document" to Category.SCREENSHOT,
    )

    fun labelOf(file: File, root: File): Category? =
        LABELS[file.relativeTo(root).invariantSeparatorsPath.substringBefore('/').lowercase()]

    /** Returns false when the real-photo safety target is violated. */
    fun run(results: List<Pair<File, Classification>>, root: File, maxRealJunk: Double): Boolean {
        val labeled = results.mapNotNull { (f, c) -> labelOf(f, root)?.let { Triple(f, it, c) } }
        if (labeled.isEmpty()) {
            println("No labeled subfolders found (expected real/, greeting/, meme/, screenshot/).")
            return false
        }
        val cols = Category.SCORED + Category.UNCERTAIN

        println("\n-- Confusion matrix (rows = truth, columns = prediction) --")
        println("%-12s".format("") + cols.joinToString("") { "%11s".format(short(it)) } + "%8s".format("n"))
        for (truth in Category.SCORED) {
            val row = labeled.filter { it.second == truth }
            if (row.isEmpty()) continue
            println("%-12s".format(short(truth)) + cols.joinToString("") { p -> "%11d".format(row.count { it.third.category == p }) } + "%8d".format(row.size))
        }

        println("\n-- Per category (Uncertain = abstain) --")
        println("%-12s %10s %10s %8s %10s".format("", "precision", "recall", "F1", "abstained"))
        for (c in Category.SCORED) {
            val actual = labeled.filter { it.second == c }
            val predicted = labeled.filter { it.third.category == c }
            val tp = predicted.count { it.second == c }
            val precision = if (predicted.isEmpty()) Double.NaN else tp.toDouble() / predicted.size
            val recall = if (actual.isEmpty()) Double.NaN else tp.toDouble() / actual.size
            val f1 = if (precision + recall > 0) 2 * precision * recall / (precision + recall) else 0.0
            val abstained = actual.count { it.third.category == Category.UNCERTAIN }
            println("%-12s %10s %10s %8s %10d".format(short(c), pct(precision), pct(recall), pct(f1), abstained))
        }
        val coverage = labeled.count { it.third.category != Category.UNCERTAIN }.toDouble() / labeled.size
        val correct = labeled.count { it.third.category == it.second }.toDouble()
        println("Coverage (not Uncertain): ${pct(coverage)}   accuracy on covered: ${pct(correct / labeled.count { it.third.category != Category.UNCERTAIN })}")

        println("\n-- Safety --")
        val reals = labeled.filter { it.second == Category.REAL_PHOTO }
        val realJunk = reals.filter { it.third.category.isJunk }
        val realPre = reals.filter { it.third.preselected }
        val junk = labeled.filter { it.second.isJunk }
        val junkPre = junk.count { it.third.preselected }
        val keepers = labeled.filter { !it.second.isJunk }
        val keeperPre = keepers.filter { it.third.preselected }
        val realJunkRate = if (reals.isEmpty()) 0.0 else realJunk.size.toDouble() / reals.size
        println("Real photos labelled junk:        ${realJunk.size}/${reals.size} (${pct(realJunkRate)})   target <= ${pct(maxRealJunk)}")
        println("Real photos pre-selected:         ${realPre.size}/${reals.size}")
        println("Any keeper (real+screenshot) pre-selected: ${keeperPre.size}/${keepers.size}")
        println("Junk (greeting+meme) pre-selected: $junkPre/${junk.size} (${pct(junkPre.toDouble() / maxOf(1, junk.size))})")
        println("Real photos protected by faces:   ${reals.count { it.third.protected }}")
        for ((f, _, c) in realJunk + keeperPre.filter { it !in realJunk }) {
            println("  ! ${f.relativeTo(root)} -> ${c.category.label} ${"%.2f".format(c.confidence)}  ${c.reasons.take(5)}")
        }

        val mistakes = labeled.filter { it.third.category != Category.UNCERTAIN && it.third.category != it.second }
        if (mistakes.isNotEmpty()) {
            println("\n-- Misclassified (excluding Uncertain) --")
            for ((f, truth, c) in mistakes) println("  ${f.relativeTo(root)}  truth=${short(truth)} pred=${short(c.category)} ${"%.2f".format(c.confidence)}")
        }

        val ok = realJunkRate <= maxRealJunk && realPre.isEmpty()
        println(if (ok) "\nSAFETY PASS" else "\nSAFETY FAIL")
        return ok
    }

    private fun short(c: Category) = when (c) {
        Category.REAL_PHOTO -> "real"
        Category.GREETING -> "greeting"
        Category.MEME -> "meme"
        Category.SCREENSHOT -> "screenshot"
        Category.UNCERTAIN -> "uncertain"
    }

    private fun pct(v: Double) = if (v.isNaN()) "n/a" else "%.1f%%".format(v * 100)
}
