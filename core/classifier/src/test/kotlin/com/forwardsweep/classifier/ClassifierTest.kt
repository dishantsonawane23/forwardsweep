package com.forwardsweep.classifier

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TextAnalyzerTest {
    private val analyzer = TextAnalyzer()
    private fun ocr(vararg lines: String) =
        OcrResult(lines.mapIndexed { i, s -> TextBlock(s, Box(0.1, 0.1 * i, 0.9, 0.1 * i + 0.08)) })

    @Test fun `matches english greetings with ocr noise`() {
        assertTrue(analyzer.analyze(ocr("G00D M0RNING")).greeting > 0)
        assertTrue(analyzer.analyze(ocr("Good Mornig friends")).greeting > 0)
        assertTrue(analyzer.analyze(ocr("Happy Diwali!")).greeting > 0)
        assertTrue(analyzer.analyze(ocr("goodnight")).greeting > 0)
    }

    @Test fun `matches devanagari greetings`() {
        assertTrue(analyzer.analyze(ocr("सुप्रभात")).greeting > 0)
        assertTrue(analyzer.analyze(ocr("शुभ रात्री")).greeting > 0)
        assertTrue(analyzer.analyze(ocr("शुभ सकाळ")).greeting > 0)
        assertTrue(analyzer.analyze(ocr("दिवाळीच्या हार्दिक शुभेच्छा")).greeting > 0)
    }

    @Test fun `ordinary text is not a greeting`() {
        assertEquals(0.0, analyzer.analyze(ocr("When you finally finish the assignment")).greeting)
        assertEquals(0.0, analyzer.analyze(ocr("TOTAL 450.00", "GST 18%")).greeting)
        assertTrue(analyzer.analyze(ocr("TOTAL ₹450.00", "UPI ref 12345")).document >= 0.5)
    }
}

class PHashTest {
    private fun noiseImage(seed: Int, w: Int = 200, h: Int = 150): RgbImage {
        val r = Random(seed)
        // Smooth blobs so the hash has structure.
        val cx = List(6) { r.nextDouble() * w to r.nextDouble() * h }
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val v = cx.sumOf { (a, b) -> 255.0 / (1 + ((x - a) * (x - a) + (y - b) * (y - b)) / 800.0) }.coerceAtMost(255.0).toInt()
            (0xFF shl 24) or (v shl 16) or ((255 - v) shl 8) or (v / 2)
        }
        return RgbImage(w, h, px)
    }

    @Test fun `resized copy has a near-identical hash, different image does not`() {
        val a = noiseImage(1)
        val resized = a.resized(120, 90)
        val other = noiseImage(2)
        assertTrue(PHash.distance(PHash.compute(a), PHash.compute(resized)) <= 6)
        assertTrue(PHash.distance(PHash.compute(a), PHash.compute(other)) > 12)
    }

    @Test fun `duplicate grouper keeps largest first`() {
        val h1 = PHash.compute(noiseImage(1))
        val h1b = PHash.compute(noiseImage(1).resized(100, 75))
        val h2 = PHash.compute(noiseImage(2))
        val groups = DuplicateGrouper.group(listOf(Triple("small", h1b, 10L), Triple("big", h1, 99L), Triple("other", h2, 50L)), 6)
        assertEquals(listOf(listOf("big", "small")), groups)
    }
}

class ScorerSafetyTest {
    private val config = ClassifierConfig()
    private val flatTraits = ImageTraits(0.8, 0.5, 90.0, 0.2, 0.4, 0.05, 80, 0.6, 0.5)
    private val photoTraits = ImageTraits(0.35, 0.05, 40.0, 0.02, 0.1, 0.03, 900, 0.0, 4.0)
    private fun meta(name: String = "IMG-20240101-WA0001.jpg") = ImageMeta("x", name, 1080, 1080, 200_000)
    private fun greetingOcr() = OcrResult(listOf(TextBlock("Good Morning", Box(0.1, 0.4, 0.9, 0.55))))

    @Test fun `vivid greeting card with keyword is preselected`() {
        val ocr = greetingOcr()
        val c = Scorer(config, PersonalModel()).score(Evidence(meta(), ExifFeatures.NONE, flatTraits, ocr, TextAnalyzer().analyze(ocr), null, 0, 1L))
        assertEquals(Category.GREETING, c.category)
        assertTrue(c.preselected)
    }

    @Test fun `camera exif blocks junk even with greeting text`() {
        val ocr = greetingOcr()
        val exif = ExifFeatures(make = "samsung", model = "SM-G991B", hasExposure = true, hasFocalLength = true)
        val c = Scorer(config, PersonalModel()).score(Evidence(meta("20240101_101010.jpg"), exif, flatTraits, ocr, TextAnalyzer().analyze(ocr), null, 0, 1L))
        assertFalse(c.category.isJunk)
        assertFalse(c.preselected)
    }

    @Test fun `faces on a photo-like image are protected`() {
        val ocr = OcrResult(listOf(TextBlock("Happy Birthday", Box(0.3, 0.05, 0.7, 0.1))))
        val c = Scorer(config, PersonalModel()).score(Evidence(meta(), ExifFeatures.NONE, photoTraits, ocr, TextAnalyzer().analyze(ocr), null, 3, 1L))
        assertFalse(c.preselected)
        assertNotEquals(Category.GREETING, c.category)
    }

    @Test fun `correction memory overrides near duplicates`() {
        val fb = PersonalModel()
        fb.train(listOf(PersonalModel.Example(0b1011L, null, null, Category.REAL_PHOTO)))
        val ocr = greetingOcr()
        val c = Scorer(config, fb).score(Evidence(meta(), ExifFeatures.NONE, flatTraits, ocr, TextAnalyzer().analyze(ocr), null, 0, 0b1111L))
        assertEquals(Category.REAL_PHOTO, c.category)
        assertFalse(c.preselected)
    }

    @Test fun `a photo with a stray word or two is not a greeting`() {
        // e.g. a temple visit with a small sign in the background: CLIP says "devotional greeting".
        val ocr = OcrResult(listOf(TextBlock("Shri Mandir", Box(0.4, 0.1, 0.6, 0.13))))
        val clip = ClipResult(mapOf(Category.GREETING to 0.98, Category.REAL_PHOTO to 0.01, Category.MEME to 0.005, Category.SCREENSHOT to 0.005), FloatArray(4))
        val c = Scorer(config, PersonalModel()).score(Evidence(meta(), ExifFeatures.NONE, photoTraits, ocr, TextAnalyzer().analyze(ocr), clip, 1, 1L))
        assertNotEquals(Category.GREETING, c.category)
        assertFalse(c.preselected)
    }

    @Test fun `vivid image without any text is never a greeting`() {
        val ocr = OcrResult(emptyList())
        val clip = ClipResult(mapOf(Category.GREETING to 0.97, Category.REAL_PHOTO to 0.01, Category.MEME to 0.01, Category.SCREENSHOT to 0.01), FloatArray(4))
        val c = Scorer(config, PersonalModel()).score(Evidence(meta(), ExifFeatures.NONE, flatTraits, ocr, TextAnalyzer().analyze(ocr), clip, 0, 1L))
        assertNotEquals(Category.GREETING, c.category)
        assertFalse(c.preselected)
    }
}

class PosterTextTest {
    private fun analyze(vararg lines: String) =
        TextAnalyzer().analyze(OcrResult(lines.mapIndexed { i, s -> TextBlock(s, Box(0.1, 0.1 * i, 0.9, 0.1 * i + 0.08)) }))

    @Test fun `event poster text is recognised`() {
        val t = analyze("BEYBLADE GYM - JUNE BATTLE", "Entry fee just Rs 250", "14th June, Sunday 12:00 PM", "Register now 9604132505")
        assertTrue(t.poster >= 0.99, "poster=${t.poster} ${t.posterMatches}")
    }

    @Test fun `a greeting banner with only the sender phone number is not a poster`() {
        val t = analyze("Happy Diwali", "Shri A. B. Sharma", "9876500001")
        assertEquals(0.0, t.poster)
    }
}

class PersonalModelTest {
    private fun unit(vararg v: Float): FloatArray {
        val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    private val state = ScoreState(
        baseLogits = doubleArrayOf(0.0, 3.0, 0.0, 0.0), baseReasons = emptyList(),
        exifEvidence = 0.0, faces = 0, ocrRan = true, words = 6,
    )

    @Test fun `a correction moves look-alikes but not unrelated images`() {
        val pm = PersonalModel()
        val scorer = Scorer(ClassifierConfig(), pm)
        val banner = unit(1f, 0.1f, 0f, 0f)
        assertEquals(Category.GREETING, scorer.decide("a", 1L, state, unit(1f, 0.15f, 0f, 0f)).category)

        // The user moves one family birthday banner to Real Photo.
        pm.train(listOf(PersonalModel.Example(0x7FFF_0000L, banner, state.baseLogits, Category.REAL_PHOTO)))
        val lookAlike = scorer.decide("b", 2L, state, unit(1f, 0.15f, 0f, 0f))
        assertNotEquals(Category.GREETING, lookAlike.category)
        assertFalse(lookAlike.preselected)
        assertTrue(lookAlike.reasons.first().startsWith("looks like"))

        val unrelated = scorer.decide("c", 3L, state, unit(0f, 0f, 1f, 0f))
        assertEquals(Category.GREETING, unrelated.category)
    }

    @Test fun `personal model learns a direction from enough examples`() {
        val pm = PersonalModel(minExamplesForModel = 8)
        // Ten corrections: images along axis 0 are memes for this user, even though the default says greeting.
        val ex = (0 until 10).map { i -> PersonalModel.Example(i.toLong() shl 40, unit(1f, 0.05f * i, 0.3f, 0f), state.baseLogits, Category.MEME) } +
            (0 until 10).map { i -> PersonalModel.Example((i + 20).toLong() shl 40, unit(0f, 0.05f * i, 0.3f, 1f), state.baseLogits, Category.GREETING) }
        pm.train(ex)
        val c = Scorer(ClassifierConfig(), pm).decide("x", 99L, state, unit(1f, 0.9f, 0.6f, 0f))
        assertEquals(Category.MEME, c.topCategory)
    }
}

class VideoTest {
    private class FakeVideo(override val meta: ImageMeta, private val frames: List<RgbImage>) : VideoInput {
        var framesAsked = 0
        override suspend fun loadFrames(maxSide: Int, positions: List<Double>): List<RgbImage> { framesAsked = positions.size; return frames }
        override suspend fun loadPixels(maxSide: Int) = frames[frames.size / 2]
        override suspend fun readExif() = ExifFeatures.NONE
    }

    private fun solid(rgb: Int) = RgbImage(64, 64, IntArray(64 * 64) { rgb })
    private fun probs(g: Double) = mapOf(Category.GREETING to g, Category.REAL_PHOTO to (1 - g) / 3, Category.MEME to (1 - g) / 3, Category.SCREENSHOT to (1 - g) / 3)

    @Test fun `clip is averaged over the three frames`() = kotlinx.coroutines.runBlocking {
        val seen = ArrayList<Int>()
        val clip = ClipEngine { px -> seen += px.pixels[0]; ClipResult(probs(if (px.pixels[0] == 0xFF0000) 0.9 else 0.3), FloatArray(4) { 0.5f }) }
        val video = FakeVideo(ImageMeta("v", "VID-20240101-WA0001.mp4", 720, 1280, 1_000_000, mediaType = MediaType.VIDEO, durationMs = 12_000),
            listOf(solid(0x00FF00), solid(0xFF0000), solid(0x0000FF)))
        val c = ClassificationPipeline(clip = clip).classify(video)
        assertEquals(3, video.framesAsked)
        assertEquals(3, seen.size)
        assertTrue(c.embedding != null)
    }

    @Test fun `whatsapp gif folder points to meme`() {
        val meta = ImageMeta("g", "VID-20240101-WA0002.mp4", 480, 480, 300_000,
            relativePath = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Animated Gifs/", mediaType = MediaType.VIDEO, durationMs = 3_000)
        val traits = ImageTraits(0.4, 0.2, 60.0, 0.3, 0.4, 0.05, 200, 0.0, 1.0)
        val c = Scorer(ClassifierConfig(), PersonalModel()).score(Evidence(meta, ExifFeatures.NONE, traits, null, null, null, 0, 5L))
        assertEquals(Category.MEME, c.topCategory)
    }
}
