package com.forwardsweep.harness

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.google.gson.JsonParser
import com.forwardsweep.classifier.Box
import com.forwardsweep.classifier.ExifFeatures
import com.forwardsweep.classifier.FaceDetector
import com.forwardsweep.classifier.ImageInput
import com.forwardsweep.classifier.ImageMeta
import com.forwardsweep.classifier.OcrEngine
import com.forwardsweep.classifier.OcrResult
import com.forwardsweep.classifier.RgbImage
import com.forwardsweep.classifier.TextBlock
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** Desktop stand-in for the Android MediaStore-backed input. */
class FileImageInput(val file: File) : ImageInput {
    private val image: BufferedImage by lazy {
        ImageIO.read(file) ?: error("unsupported image format: ${file.name}")
    }

    override val meta: ImageMeta by lazy {
        ImageMeta(
            id = file.path,
            displayName = file.name,
            width = image.width,
            height = image.height,
            sizeBytes = file.length(),
            mimeType = "image/" + file.extension.lowercase(),
        )
    }

    override suspend fun loadPixels(maxSide: Int): RgbImage {
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return RgbImage(image.width, image.height, argb).downscaled(maxSide)
    }

    override suspend fun readExif(): ExifFeatures {
        val md = ImageMetadataReader.readMetadata(file)
        val ifd0 = md.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        val sub = md.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        val gps = md.getFirstDirectoryOfType(GpsDirectory::class.java)
        return ExifFeatures(
            make = ifd0?.getString(ExifIFD0Directory.TAG_MAKE),
            model = ifd0?.getString(ExifIFD0Directory.TAG_MODEL),
            hasExposure = sub?.containsTag(ExifSubIFDDirectory.TAG_EXPOSURE_TIME) == true,
            hasFocalLength = sub?.containsTag(ExifSubIFDDirectory.TAG_FOCAL_LENGTH) == true,
            hasGps = gps?.geoLocation != null,
            hasDateTimeOriginal = sub?.containsTag(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL) == true,
            software = ifd0?.getString(ExifIFD0Directory.TAG_SOFTWARE),
        )
    }
}

/**
 * Reads precomputed signals from `<image>.signals.json`:
 * `{"ocr":[{"text":"Good Morning","box":[l,t,r,b]}], "faces": 2}` (boxes normalized 0..1).
 *
 * Use it to replay ML Kit output captured on a device, or the synthetic set's simulated OCR.
 * Images without a sidecar get no OCR / face signal.
 */
class SidecarSignals : OcrEngine, FaceDetector {
    private fun sidecar(input: ImageInput): File? =
        (input as? FileImageInput)?.file?.let { File(it.path + ".signals.json") }?.takeIf { it.exists() }

    override suspend fun recognize(input: ImageInput, pixels: RgbImage): OcrResult {
        val json = JsonParser.parseString((sidecar(input) ?: return OcrResult.EMPTY).readText()).asJsonObject
        val blocks = json.getAsJsonArray("ocr")?.map { el ->
            val o = el.asJsonObject
            val b = o.getAsJsonArray("box").map { it.asDouble }
            TextBlock(o.get("text").asString, Box(b[0], b[1], b[2], b[3]))
        } ?: emptyList()
        return OcrResult(blocks)
    }

    override suspend fun countFaces(input: ImageInput, pixels: RgbImage): Int {
        val json = JsonParser.parseString((sidecar(input) ?: return 0).readText()).asJsonObject
        return json.get("faces")?.asInt ?: 0
    }
}

/** Real OCR on desktop through the Tesseract CLI (`eng+hin+mar` traineddata recommended). */
class TesseractOcr(private val languages: String = "eng+hin") : OcrEngine {
    override suspend fun recognize(input: ImageInput, pixels: RgbImage): OcrResult {
        val file = (input as FileImageInput).file
        val proc = ProcessBuilder("tesseract", file.absolutePath, "stdout", "-l", languages, "--psm", "11", "tsv")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val tsv = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
        proc.waitFor(60, TimeUnit.SECONDS)
        val w = input.meta.width.toDouble(); val h = input.meta.height.toDouble()
        // Group words (level 5) into lines.
        data class Word(val key: String, val text: String, val l: Int, val t: Int, val r: Int, val b: Int)
        val words = tsv.lineSequence().drop(1).mapNotNull { line ->
            val c = line.split('\t')
            if (c.size < 12 || c[0] != "5" || c[11].isBlank() || (c[10].toDoubleOrNull() ?: 0.0) < 40) null
            else Word("${c[2]}-${c[3]}-${c[4]}", c[11], c[6].toInt(), c[7].toInt(), c[6].toInt() + c[8].toInt(), c[7].toInt() + c[9].toInt())
        }.toList()
        return OcrResult(words.groupBy { it.key }.values.map { ws ->
            TextBlock(ws.joinToString(" ") { it.text },
                Box(ws.minOf { it.l } / w, ws.minOf { it.t } / h, ws.maxOf { it.r } / w, ws.maxOf { it.b } / h))
        })
    }

    companion object {
        fun isAvailable(): Boolean = runCatching {
            ProcessBuilder("tesseract", "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
        }.getOrDefault(false)
    }
}
