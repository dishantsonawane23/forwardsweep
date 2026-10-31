package com.forwardsweep.ml

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.forwardsweep.classifier.ExifFeatures
import com.forwardsweep.classifier.ImageInput
import com.forwardsweep.classifier.ImageMeta
import com.forwardsweep.classifier.RgbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A MediaStore image. Decodes once, downsampled at decode time (never a full-resolution
 * bitmap), and keeps the bitmap so ML Kit can use it without another copy.
 */
class MediaImageInput(
    private val resolver: ContentResolver,
    val uri: Uri,
    override val meta: ImageMeta,
) : ImageInput {
    @Volatile
    var bitmap: Bitmap? = null
        private set

    override suspend fun loadPixels(maxSide: Int): RgbImage = withContext(Dispatchers.IO) {
        val bmp = bitmap ?: decodeSampled(resolver, uri, maxSide).also { bitmap = it }
        bmp.toRgbImage()
    }

    override suspend fun readExif(): ExifFeatures = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            ExifFeatures(
                make = exif.getAttribute(ExifInterface.TAG_MAKE),
                model = exif.getAttribute(ExifInterface.TAG_MODEL),
                hasExposure = exif.hasAttribute(ExifInterface.TAG_EXPOSURE_TIME),
                hasFocalLength = exif.hasAttribute(ExifInterface.TAG_FOCAL_LENGTH),
                // GPS is redacted by MediaStore unless ACCESS_MEDIA_LOCATION is granted. We don't
                // ask for it: make/model/exposure are enough, and location isn't needed.
                hasGps = exif.latLong != null,
                hasDateTimeOriginal = exif.hasAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                software = exif.getAttribute(ExifInterface.TAG_SOFTWARE),
            )
        } ?: ExifFeatures.NONE
    }

    /** Free the decoded bitmap once the image is classified. */
    fun release() {
        bitmap?.recycle()
        bitmap = null
    }
}

fun Bitmap.toRgbImage(): RgbImage {
    val px = IntArray(width * height)
    getPixels(px, 0, width, 0, 0, width, height)
    return RgbImage(width, height, px)
}

fun RgbImage.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

/** Decode with the longest side ≤ [maxSide], honouring EXIF orientation, as a software bitmap. */
fun decodeSampled(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxSide) {
                val scale = maxSide.toDouble() / longest
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: error("cannot decode $uri")
    val rotation = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
    val oriented = if (rotation == 0) decoded else
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    val longest = maxOf(oriented.width, oriented.height)
    if (longest <= maxSide) return oriented
    val scale = maxSide.toFloat() / longest
    return Bitmap.createScaledBitmap(oriented, (oriented.width * scale).toInt().coerceAtLeast(1), (oriented.height * scale).toInt().coerceAtLeast(1), true)
}
