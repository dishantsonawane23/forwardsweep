package com.forwardsweep.classifier

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 64-bit DCT perceptual hash. Robust to the recompression, resizing and slight colour shifts
 * an image picks up when forwarded between WhatsApp groups.
 */
object PHash {
    private const val SIZE = 32
    private const val LOW = 8

    private val dctTable: Array<DoubleArray> = Array(LOW) { u ->
        val c = if (u == 0) sqrt(1.0 / SIZE) else sqrt(2.0 / SIZE)
        DoubleArray(SIZE) { x -> c * cos((2 * x + 1) * u * PI / (2 * SIZE)) }
    }

    fun compute(image: RgbImage): Long {
        val small = image.resized(SIZE, SIZE)
        val gray = DoubleArray(SIZE * SIZE) { i ->
            val p = small.pixels[i]
            0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
        }
        // Separable 2D DCT, keeping only the 8x8 low-frequency block.
        val rows = Array(SIZE) { y -> DoubleArray(LOW) { u -> (0 until SIZE).sumOf { x -> dctTable[u][x] * gray[y * SIZE + x] } } }
        val coeffs = DoubleArray(LOW * LOW)
        for (v in 0 until LOW) for (u in 0 until LOW) {
            coeffs[v * LOW + u] = (0 until SIZE).sumOf { y -> dctTable[v][y] * rows[y][u] }
        }
        // Median of the 63 AC coefficients (skip DC), then threshold.
        val median = coeffs.drop(1).sorted().let { (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        var hash = 0L
        for (i in 0 until 64) if (coeffs[i] > median) hash = hash or (1L shl i)
        return hash
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}

/** BK-tree over Hamming distance: near-duplicate lookup in ~O(log n) instead of O(n). */
class HashIndex<T> {
    private class Node<T>(val hash: Long, val value: T) {
        val children = HashMap<Int, Node<T>>()
    }

    private var root: Node<T>? = null
    var size = 0
        private set

    fun add(hash: Long, value: T) {
        size++
        var node = root ?: run { root = Node(hash, value); return }
        while (true) {
            val d = PHash.distance(hash, node.hash)
            node = node.children[d] ?: run { node.children[d] = Node(hash, value); return }
        }
    }

    fun clear() {
        root = null
        size = 0
    }

    /** All values whose hash is within [maxDistance] of [hash], closest first. */
    fun query(hash: Long, maxDistance: Int): List<Pair<T, Int>> {
        val out = ArrayList<Pair<T, Int>>()
        val stack = ArrayDeque<Node<T>>()
        root?.let { stack.add(it) }
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val d = PHash.distance(hash, node.hash)
            if (d <= maxDistance) out += node.value to d
            for ((k, child) in node.children) if (k in (d - maxDistance)..(d + maxDistance)) stack.add(child)
        }
        return out.sortedBy { it.second }
    }
}

/** Groups near-duplicate images (same greeting forwarded to several groups). */
object DuplicateGrouper {
    /**
     * Returns groups of 2+ ids, each sorted so the first id is the one to keep
     * (the largest file, which is usually the least recompressed copy).
     */
    fun group(items: List<Triple<String, Long, Long>>, maxDistance: Int): List<List<String>> {
        // items: (id, pHash, sizeBytes)
        val parent = IntArray(items.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        val index = HashIndex<Int>()
        items.forEachIndexed { i, (_, hash, _) ->
            for ((j, _) in index.query(hash, maxDistance)) parent[find(i)] = find(j)
            index.add(hash, i)
        }
        return items.indices.groupBy { find(it) }.values
            .filter { it.size > 1 }
            .map { members -> members.sortedByDescending { items[it].third }.map { items[it].first } }
            .sortedByDescending { it.size }
    }
}
