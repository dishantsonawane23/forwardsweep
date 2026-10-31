package com.forwardsweep.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** CLIP embeddings are stored as little-endian float32 blobs (512 floats = 2 KB per image). */
internal fun FloatArray.toBytes(): ByteArray =
    ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(this@toBytes) }.array()

internal fun ByteArray.toFloats(): FloatArray =
    FloatArray(size / 4).also { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }

/** "1.2,-0.4,3.0,0.1" → logits in Category.SCORED order; null if malformed. */
internal fun String.toLogits(): DoubleArray? =
    split(',').mapNotNull { it.trim().toDoubleOrNull() }.takeIf { it.size == 4 }?.toDoubleArray()
