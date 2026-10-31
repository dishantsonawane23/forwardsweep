package com.forwardsweep.classifier

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cheap pixel statistics computed on a ≤256px copy. They separate camera output (sensor noise,
 * large palette, moderate saturation) from designed graphics (flat fills, gradients, small
 * palette, saturated colours, frames).
 */
data class ImageTraits(
    /** Mean HSV saturation of reasonably bright pixels, 0..1. */
    val meanSaturation: Double,
    /** Fraction of pixels that are both saturated (>0.6) and bright (>0.35). */
    val vividFraction: Double,
    /** Hasler–Süsstrunk colourfulness, roughly 0..150. */
    val colorfulness: Double,
    /** Fraction of neighbour pairs that are (near) identical: flat UI fills. */
    val flatFraction: Double,
    /** Fraction of neighbour pairs that differ by a tiny, non-zero amount: smooth gradients. */
    val smoothFraction: Double,
    /** Fraction of strong edges (text, UI lines, outlines). */
    val edgeFraction: Double,
    /** Number of 4-bit-per-channel colours needed to cover 90% of pixels. */
    val paletteSize90: Int,
    /** 0..1: a uniform frame around the image that contrasts with the interior. */
    val borderScore: Double,
    /** Median absolute Laplacian in non-edge areas: camera sensor noise / texture. */
    val noiseLevel: Double,
) {
    companion object {
        fun compute(source: RgbImage): ImageTraits {
            val img = source.downscaled(256)
            val w = img.width
            val h = img.height
            val px = img.pixels
            val n = px.size

            var satSum = 0.0; var satCount = 0; var vivid = 0
            var rgSum = 0.0; var ybSum = 0.0; var rgSq = 0.0; var ybSq = 0.0
            val gray = IntArray(n)
            val hist = IntArray(4096)
            for (i in 0 until n) {
                val p = px[i]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                val v = mx / 255.0
                val s = if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
                if (v > 0.15) { satSum += s; satCount++ }
                if (s > 0.6 && v > 0.35) vivid++
                val rg = (r - g).toDouble(); val yb = 0.5 * (r + g) - b
                rgSum += rg; ybSum += yb; rgSq += rg * rg; ybSq += yb * yb
                gray[i] = (299 * r + 587 * g + 114 * b) / 1000
                hist[((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)]++
            }
            val rgMean = rgSum / n; val ybMean = ybSum / n
            val rgStd = sqrt(max(0.0, rgSq / n - rgMean * rgMean))
            val ybStd = sqrt(max(0.0, ybSq / n - ybMean * ybMean))
            val colorfulness = sqrt(rgStd * rgStd + ybStd * ybStd) + 0.3 * sqrt(rgMean * rgMean + ybMean * ybMean)

            var flat = 0; var smooth = 0; var edges = 0; var pairs = 0
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (x + 1 < w) {
                    val d = maxChannelDiff(px[i], px[i + 1]); pairs++
                    if (d <= 1) flat++ else if (d <= 4) smooth++
                    if (d > 48) edges++
                }
                if (y + 1 < h) {
                    val d = maxChannelDiff(px[i], px[i + w]); pairs++
                    if (d <= 1) flat++ else if (d <= 4) smooth++
                    if (d > 48) edges++
                }
            }

            // Laplacian magnitude in low-gradient areas ≈ sensor noise.
            val noiseSamples = ArrayList<Int>()
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = abs(gray[i + 1] - gray[i - 1]); val gy = abs(gray[i + w] - gray[i - w])
                if (gx + gy < 24) {
                    noiseSamples += abs(4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w])
                }
            }
            noiseSamples.sort()
            val noise = if (noiseSamples.isEmpty()) 0.0 else noiseSamples[noiseSamples.size / 2].toDouble()

            val sortedHist = hist.sortedArrayDescending()
            var covered = 0; var palette = 0
            while (covered < 0.9 * n && palette < sortedHist.size) { covered += sortedHist[palette]; palette++ }

            return ImageTraits(
                meanSaturation = if (satCount == 0) 0.0 else satSum / satCount,
                vividFraction = vivid.toDouble() / n,
                colorfulness = colorfulness,
                flatFraction = flat.toDouble() / max(1, pairs),
                smoothFraction = smooth.toDouble() / max(1, pairs),
                edgeFraction = edges.toDouble() / max(1, pairs),
                paletteSize90 = palette,
                borderScore = borderScore(img),
                noiseLevel = noise,
            )
        }

        private fun maxChannelDiff(a: Int, b: Int): Int = max(
            abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
            max(abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)), abs((a and 0xFF) - (b and 0xFF))),
        )

        /** Uniform frame (low variance in the outer 4% ring) that differs from the interior mean. */
        private fun borderScore(img: RgbImage): Double {
            val w = img.width; val h = img.height
            val t = max(2, min(w, h) / 25)
            val ring = Stats(); val inner = Stats()
            for (y in 0 until h) for (x in 0 until w) {
                val p = img.pixels[y * w + x]
                val onRing = x < t || y < t || x >= w - t || y >= h - t
                val inInner = x in (3 * t) until (w - 3 * t) && y in (3 * t) until (h - 3 * t)
                if (onRing) ring.add(p) else if (inInner) inner.add(p)
            }
            if (ring.n == 0 || inner.n == 0) return 0.0
            val uniformity = (1.0 - ring.std() / 40.0).coerceIn(0.0, 1.0)
            val contrast = (ring.distanceTo(inner) / 60.0).coerceIn(0.0, 1.0)
            return uniformity * contrast
        }
    }

    private class Stats {
        var n = 0
        private val sum = DoubleArray(3)
        private val sq = DoubleArray(3)
        fun add(p: Int) {
            n++
            val c = intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            for (k in 0..2) { sum[k] += c[k].toDouble(); sq[k] += c[k].toDouble() * c[k] }
        }
        fun mean(k: Int) = sum[k] / n
        fun std(): Double = (0..2).maxOf { k -> sqrt(max(0.0, sq[k] / n - mean(k) * mean(k))) }
        fun distanceTo(o: Stats) = sqrt((0..2).sumOf { k -> (mean(k) - o.mean(k)).let { it * it } })
    }
}
