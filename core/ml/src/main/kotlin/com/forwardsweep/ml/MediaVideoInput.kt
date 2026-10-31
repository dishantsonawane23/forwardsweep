package com.forwardsweep.ml

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.forwardsweep.classifier.ExifFeatures
import com.forwardsweep.classifier.ImageMeta
import com.forwardsweep.classifier.RgbImage
import com.forwardsweep.classifier.VideoInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A MediaStore video. Frames come from [MediaMetadataRetriever] at sync frames near the
 * requested positions, scaled down at decode time on Android 8.1+ (never a full-size frame).
 */
class MediaVideoInput(
    private val context: Context,
    val uri: Uri,
    override val meta: ImageMeta,
) : VideoInput {
    private var frames: List<RgbImage>? = null

    override suspend fun loadFrames(maxSide: Int, positions: List<Double>): List<RgbImage> = withContext(Dispatchers.IO) {
        frames?.let { return@withContext it }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: meta.durationMs) * 1000
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: meta.width
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: meta.height
            val scale = if (w > 0 && h > 0) minOf(1.0, maxSide.toDouble() / maxOf(w, h)) else 1.0
            positions.mapNotNull { p ->
                val t = (durationUs * p).toLong().coerceAtLeast(0)
                val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && w > 0 && h > 0) {
                    retriever.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                } else {
                    retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { downscale(it, maxSide) }
                }
                bmp?.let { b -> b.toRgbImage().also { b.recycle() } }
            }.also { frames = it }
        } finally {
            retriever.release()
        }
    }

    override suspend fun loadPixels(maxSide: Int): RgbImage {
        val f = loadFrames(maxSide, MIDDLE)
        return f.getOrNull(f.size / 2) ?: error("no decodable frame in $uri")
    }

    /** Videos have no EXIF, and WhatsApp strips container metadata from forwards anyway. */
    override suspend fun readExif(): ExifFeatures = ExifFeatures.NONE

    fun release() {
        frames = null
    }

    private fun downscale(b: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= maxSide) return b
        val s = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
            .also { if (it !== b) b.recycle() }
    }

    private companion object {
        val MIDDLE = listOf(0.5)
    }
}
