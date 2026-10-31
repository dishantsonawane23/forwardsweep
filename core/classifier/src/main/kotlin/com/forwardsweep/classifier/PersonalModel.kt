package com.forwardsweep.classifier

import kotlin.math.exp

/**
 * Learns from the user's own choices, on the phone. Three layers, from most to least specific:
 *
 *  1. **Same image.** A near-identical copy (pHash Hamming ≤ [matchDistance]) of an image the
 *     user moved gets the user's label outright. Forwarded copies are corrected once.
 *  2. **Look-alikes.** Images whose CLIP embedding is close to a labelled example (same poster
 *     series, same greeting template) are pushed towards that example's label. Only close
 *     matches count, so one correction can't drag unrelated images along.
 *  3. **Personal model.** A small softmax regression on CLIP embeddings, trained as a residual
 *     on top of the default scorer's logits, so it learns *where the default is wrong for this
 *     user* (e.g. "birthday banners of my family are keepers"). Off until [minExamplesForModel]
 *     examples exist, because a handful of points overfits.
 *
 * Examples are corrections (weight 1.0) and confirmations such as deleted greetings/memes
 * (weight 0.5). Training a few hundred examples takes milliseconds, so the app retrains after
 * every correction and re-sorts the library from stored scores without decoding any image.
 *
 * Settings were chosen by simulating a user correcting a hand-labelled sample: they never
 * lowered accuracy, and helped more as corrections accumulated.
 */
class PersonalModel(
    private val matchDistance: Int = 6,
    private val minExamplesForModel: Int = 8,
) {
    class Example(
        val pHash: Long,
        val embedding: FloatArray?,
        /** The default scorer's logits for this image (REAL, GREETING, MEME, SCREENSHOT). */
        val baseLogits: DoubleArray?,
        val label: Category,
        val weight: Double = 1.0,
    )

    class Adjustment(val logits: DoubleArray, val reason: String?)

    private val index = HashIndex<Category>()
    private var examples: List<Example> = emptyList()
    private var weights: Array<DoubleArray>? = null

    val size: Int get() = examples.size

    @Synchronized
    fun train(all: List<Example>) {
        index.clear()
        all.filter { it.weight >= 1.0 }.forEach { index.add(it.pHash, it.label) }
        // Embeddings from a different CLIP model (after a model swap) aren't comparable. Keep the
        // most recent model's size; older examples still work through the pHash memory above.
        val dim = all.lastOrNull { it.embedding != null }?.embedding?.size
        examples = all.filter { it.embedding != null && it.embedding.size == dim && it.label in Category.SCORED }
        weights = if (examples.size >= minExamplesForModel) fitResidual(examples) else null
    }

    /** The label the user gave a near-identical image, if any. */
    @Synchronized
    fun rememberedLabel(pHash: Long): Pair<Category, Int>? = index.query(pHash, matchDistance).firstOrNull()

    /** Extra logits (in [Category.SCORED] order) for an image with this embedding. */
    @Synchronized
    fun adjust(embedding: FloatArray?): Adjustment? {
        if (embedding == null || examples.isEmpty() || examples[0].embedding!!.size != embedding.size) return null
        val out = DoubleArray(K)
        var reason: String? = null

        // Look-alikes: the closest labelled examples, counted only above SIM_LO.
        val sims = examples.map { dot(it.embedding!!, embedding) }
        val nearest = sims.indices.sortedByDescending { sims[it] }.take(NEIGHBOURS)
        val votes = IntArray(K)
        for (j in nearest) {
            val r = ((sims[j] - SIM_LO) / (SIM_HI - SIM_LO)).coerceIn(0.0, 1.0)
            if (r <= 0.0) continue
            val k = Category.SCORED.indexOf(examples[j].label)
            out[k] += KNN_GAIN * r * examples[j].weight
            votes[k]++
        }
        for (k in 0 until K) out[k] = out[k].coerceAtMost(KNN_GAIN)
        val best = votes.indices.maxBy { votes[it] }
        if (votes[best] > 0) {
            reason = "looks like ${plural(votes[best])} you sorted as ${Category.SCORED[best].label}"
        }

        weights?.let { w ->
            for (k in 0 until K) out[k] += dot(w[k], embedding)
        }
        return Adjustment(out, reason)
    }

    /** Softmax regression on embeddings, added to the base logits; no bias term (it overfits). */
    private fun fitResidual(ex: List<Example>): Array<DoubleArray> {
        val dim = ex.first().embedding!!.size
        val w = Array(K) { DoubleArray(dim) }
        val y = ex.map { Category.SCORED.indexOf(it.label) }
        repeat(EPOCHS) {
            val grad = Array(K) { DoubleArray(dim) }
            ex.forEachIndexed { i, e ->
                val emb = e.embedding!!
                val z = DoubleArray(K) { k -> (e.baseLogits?.getOrNull(k) ?: 0.0) + dot(w[k], emb) }
                val max = z.max()
                val p = DoubleArray(K) { exp(z[it] - max) }
                val s = p.sum()
                for (k in 0 until K) {
                    val g = (p[k] / s - if (k == y[i]) 1.0 else 0.0) * e.weight
                    if (g != 0.0) for (d in 0 until dim) grad[k][d] += g * emb[d]
                }
            }
            for (k in 0 until K) for (d in 0 until dim) {
                w[k][d] -= LEARNING_RATE * (grad[k][d] / ex.size + L2 * w[k][d])
            }
        }
        return w
    }

    private fun plural(n: Int) = if (n == 1) "an image" else "$n images"

    companion object {
        private val K = Category.SCORED.size
        const val SIM_LO = 0.60
        const val SIM_HI = 0.85
        const val NEIGHBOURS = 5
        const val KNN_GAIN = 4.0
        const val EPOCHS = 60
        const val LEARNING_RATE = 20.0
        const val L2 = 0.001

        private fun dot(a: FloatArray, b: FloatArray): Double {
            var s = 0.0
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        private fun dot(a: DoubleArray, b: FloatArray): Double {
            var s = 0.0
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
