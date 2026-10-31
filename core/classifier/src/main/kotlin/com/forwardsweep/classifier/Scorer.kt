package com.forwardsweep.classifier

import kotlin.math.exp
import kotlin.math.ln

/** Everything the scorer looks at for one image. Optional signals are null when unavailable. */
data class Evidence(
    val meta: ImageMeta,
    val exif: ExifFeatures,
    val traits: ImageTraits,
    val ocr: OcrResult?,
    val text: TextSignals?,
    val clip: ClipResult?,
    val faces: Int?,
    val pHash: Long,
)

/**
 * Turns evidence into a decision. Each signal adds logits to the categories it supports; the
 * logits are softmaxed into probabilities, and safety rules then decide whether the top
 * category is trusted or the image becomes Uncertain.
 */
class Scorer(private val config: ClassifierConfig, private val personal: PersonalModel = PersonalModel()) {

    private class Logits {
        val v = HashMap<Category, Double>().apply { Category.SCORED.forEach { put(it, 0.0) } }
        val reasons = ArrayList<String>()
        fun add(c: Category, amount: Double, reason: String? = null) {
            if (amount == 0.0) return
            v[c] = v.getValue(c) + amount
            if (reason != null && kotlin.math.abs(amount) >= 0.3) {
                reasons += "%s %+.1f %s: %s".format(c.shortName, amount, if (amount > 0) "↑" else "↓", reason)
            }
        }
    }

    fun score(e: Evidence): Classification {
        val w = config.weights
        val l = Logits()
        val t = e.traits
        val text = e.text
        val ocr = e.ocr

        // ── Priors: with no evidence at all, lean towards "keep". ─────────────────────────────
        l.add(Category.REAL_PHOTO, w.priorReal)
        l.add(Category.MEME, w.priorMeme)

        // ── EXIF ───────────────────────────────────────────────────────────────────────────
        val cam = e.exif.cameraEvidence
        if (cam > 0) l.add(Category.REAL_PHOTO, w.exif * cam, "camera EXIF ${e.exif.cameraName ?: ""}".trim())
        if (e.exif.software?.contains("screenshot", ignoreCase = true) == true) {
            l.add(Category.SCREENSHOT, w.screenshotFilename, "EXIF software says screenshot")
        }

        // ── File name and geometry ─────────────────────────────────────────────────────────
        val name = e.meta.displayName.lowercase()
        val path = e.meta.relativePath?.lowercase().orEmpty()
        if (SCREENSHOT_NAME.containsMatchIn(name) || path.contains("screenshot")) {
            l.add(Category.SCREENSHOT, w.screenshotFilename, "screenshot file name/folder")
        }
        if (CAMERA_NAME.containsMatchIn(name) && !path.contains("whatsapp")) {
            l.add(Category.REAL_PHOTO, 1.0, "camera-style file name")
        }
        val (shortSide, longSide) = minOf(e.meta.width, e.meta.height) to maxOf(e.meta.width, e.meta.height)
        if (shortSide > 0) {
            val ratio = longSide.toDouble() / shortSide
            if (e.meta.height > e.meta.width && ratio in 1.95..2.4) {
                val exactWidth = shortSide in PHONE_WIDTHS
                l.add(Category.SCREENSHOT, if (exactWidth) 1.5 else 0.6, "phone-screen aspect ratio")
                l.add(Category.REAL_PHOTO, -0.4)
            }
        }

        // ── Video-only evidence ────────────────────────────────────────────────────────────
        val video = e.meta.mediaType == MediaType.VIDEO
        if (video) {
            // WhatsApp stores GIFs as short MP4s in their own folder: reactions and memes.
            if (path.contains("animated gif")) l.add(Category.MEME, 2.5, "WhatsApp GIF")
            if (SCREEN_RECORDING_NAME.containsMatchIn(name) || path.contains("screen record")) {
                l.add(Category.SCREENSHOT, w.screenshotFilename, "screen recording")
            }
            // Forwarded greetings and memes are short; long clips are mostly people's own videos.
            if (e.meta.durationMs > 90_000) l.add(Category.REAL_PHOTO, 1.0, "long video (%d s)".format(e.meta.durationMs / 1000))
        }

        // ── Pixel traits ───────────────────────────────────────────────────────────────────
        // Video compression smooths away sensor noise, so it says nothing for video frames.
        val noiseTerm = if (video) 0.0 else ((t.noiseLevel - 2.0) / 3.0).coerceIn(-1.0, 1.0)
        l.add(Category.REAL_PHOTO, w.sensorNoise * noiseTerm, "sensor noise %.1f".format(t.noiseLevel))
        val flat = ramp(t.flatFraction, 0.30, 0.70)
        val vivid = ramp(t.vividFraction, 0.20, 0.55)
        val clean = 1.0 - ramp(t.noiseLevel, 0.5, 3.0)
        // How "designed" the image looks (vs. captured by a camera). Scales keyword evidence below.
        val design = maxOf(vivid, 0.7 * flat, t.borderScore) * (0.5 + 0.5 * clean)

        l.add(Category.REAL_PHOTO, -w.flatness * 0.8 * flat, "flat fills %.0f%%".format(t.flatFraction * 100))
        val uiFlat = ramp(t.flatFraction, 0.60, 0.85) * (1.0 - vivid) * (if (t.colorfulness < 70) 1.0 else 0.3)
        l.add(Category.SCREENSHOT, w.flatness * uiFlat, "UI-like flat areas")
        if (t.paletteSize90 < 8) {
            l.add(Category.SCREENSHOT, 1.0, "tiny palette (${t.paletteSize90} colours)")
            l.add(Category.REAL_PHOTO, -1.0)
        }
        l.add(Category.GREETING, w.vivid * vivid, "vivid colours %.0f%%".format(t.vividFraction * 100))
        if (t.meanSaturation > 0.55) l.add(Category.GREETING, 0.5, "high saturation")
        if (t.colorfulness > 100) l.add(Category.GREETING, 0.4)
        if (t.meanSaturation < 0.12) l.add(Category.GREETING, -0.8, "desaturated")
        l.add(Category.GREETING, w.border * t.borderScore, "decorative frame")
        // QR codes / barcodes: high-contrast, monochrome, very small palette.
        if (t.edgeFraction > 0.12 && t.paletteSize90 < 12 && t.meanSaturation < 0.15) {
            l.add(Category.SCREENSHOT, 2.0, "QR/barcode-like pattern")
        }

        // ── OCR text ───────────────────────────────────────────────────────────────────────
        if (ocr != null && text != null && text.wordCount > 0) {
            val area = ocr.textAreaRatio
            if (text.greeting > 0) {
                // A greeting phrase on a camera-looking image is often a sign, cake or banner in a
                // real photo, so the keyword counts for less unless the pixels look designed.
                l.add(Category.GREETING, w.greetingKeyword * text.greeting * (0.35 + 0.65 * design),
                    "greeting text '${text.greetingMatches.first()}' (design %.2f)".format(design))
                l.add(Category.MEME, -1.0)
                l.add(Category.SCREENSHOT, -0.8)
            }
            l.add(Category.MEME, w.memePhrase * text.memePhrasing, "meme phrasing")
            l.add(Category.SCREENSHOT, w.documentText * text.document, "document words ${text.documentMatches.take(3)}")
            l.add(Category.SCREENSHOT, w.screenUiText * text.screenUi, "phone UI text")
            // Event posters and adverts look festive (CLIP calls many of them greetings), but
            // their text gives them away: phone + date + time + price + "register"/"venue".
            if (text.poster > 0) {
                l.add(Category.SCREENSHOT, 2.5 * text.poster, "event/advert details ${text.posterMatches.take(3)}")
                l.add(Category.GREETING, -2.0 * text.poster)
            }
            // Many lines of text suggests a screen, unless the image looks designed: Marathi/Hindi
            // greetings routinely carry long blessings and a "शुभेच्छुक" signature block.
            if (ocr.lineCount >= 8) l.add(Category.SCREENSHOT, 1.0 * (1 - design), "${ocr.lineCount} text lines")
            if (text.wordCount > 45) l.add(Category.SCREENSHOT, 0.8 * (1 - design), "dense text")

            // Caption over a picture: some text, mostly in top/bottom bands, no greeting/document words.
            val captionLike = text.greeting == 0.0 && text.document < 0.5 && text.screenUi < 0.5 &&
                area in 0.03..0.55 && text.wordCount in 2..45
            if (captionLike) {
                l.add(Category.MEME, w.memeCaption * (0.4 + 0.6 * ocr.captionBandRatio), "caption text, ${"%.0f".format(ocr.captionBandRatio * 100)}% in top/bottom bands")
            }
            // Lots of text is unusual for a camera photo (but signs and menus exist: keep it mild).
            if (area > 0.04) l.add(Category.REAL_PHOTO, -(area / 0.25).coerceAtMost(1.0) * 1.5, "text covers %.0f%%".format(area * 100))
        } else if (ocr != null) {
            // OCR ran and found nothing: memes are captioned by definition, greetings nearly always
            // carry a wish. CLIP alone can mistake photos of home pujas and temple visits for
            // devotional greetings; the missing text is what tells them apart.
            l.add(Category.MEME, -0.8, "no text found")
            l.add(Category.GREETING, -2.0)
        }

        // ── Faces ──────────────────────────────────────────────────────────────────────────
        val faces = e.faces ?: 0
        if (faces > 0 && (text?.wordCount ?: 0) < 2) {
            l.add(Category.REAL_PHOTO, 0.8 + 0.2 * minOf(faces, 3), "$faces face(s), no caption")
        }

        // ── CLIP zero-shot ─────────────────────────────────────────────────────────────────
        e.clip?.let { clip ->
            for (c in Category.SCORED) {
                val p = clip.probabilities[c] ?: 0.0
                l.add(c, w.clip * ln(p + 0.02) - w.clip * ln(0.27), "CLIP p=%.2f".format(p))
            }
        }

        val state = ScoreState(
            baseLogits = DoubleArray(Category.SCORED.size) { l.v.getValue(Category.SCORED[it]) },
            baseReasons = l.reasons,
            exifEvidence = e.exif.cameraEvidence,
            faces = faces,
            ocrRan = ocr != null,
            words = text?.wordCount ?: 0,
        )
        return decide(e.meta.id, e.pHash, state, e.clip?.embedding, features(e))
    }

    /**
     * Personalisation + safety rules on top of a stored [ScoreState]. Cheap (no pixels), so the
     * app calls it for the whole library after every correction.
     */
    fun decide(
        id: String,
        pHash: Long,
        state: ScoreState,
        embedding: FloatArray?,
        features: Map<String, Double> = emptyMap(),
    ): Classification {
        val logits = state.baseLogits.copyOf()
        val reasons = state.baseReasons.toMutableList()
        personal.adjust(embedding)?.let { adj ->
            for (k in logits.indices) logits[k] += adj.logits[k]
            adj.reason?.let { reasons.add(0, it) }
        }
        val probs = softmax(Category.SCORED.indices.associate { Category.SCORED[it] to logits[it] })
        val top = probs.maxBy { it.value }
        var category = top.key
        val pReal = probs.getValue(Category.REAL_PHOTO)
        val faces = state.faces

        fun build(category: Category, confidence: Double, protectedImage: Boolean) = Classification(
            id = id,
            category = category,
            confidence = confidence,
            probabilities = probs,
            reasons = reasons,
            protected = protectedImage,
            preselected = category.isJunk && !protectedImage,
            pHash = pHash,
            faces = faces,
            topCategory = top.key,
            features = features,
            state = state,
            embedding = embedding,
        )

        // Correction memory wins over everything: the user has seen (a copy of) this image.
        personal.rememberedLabel(pHash)?.let { (label, dist) ->
            reasons.add(0, "you labelled a near-identical image as ${label.label} (distance $dist)")
            return build(label, 1.0, protectedImage = false)
        }

        if (top.value < config.minConfidence) {
            reasons += "top score %.2f below threshold %.2f".format(top.value, config.minConfidence)
            category = Category.UNCERTAIN
        }
        if (category.isJunk && pReal >= config.realPhotoVeto) {
            reasons += "real-photo veto: P(real)=%.2f".format(pReal)
            category = Category.UNCERTAIN
        }
        if (category.isJunk && state.exifEvidence >= config.strongExif) {
            reasons += "strong camera EXIF blocks junk label"
            category = Category.UNCERTAIN
        }
        if (category == Category.GREETING && state.ocrRan && state.words < MIN_GREETING_WORDS) {
            reasons += "no readable text, so not treated as a greeting"
            category = Category.UNCERTAIN
        }
        val protectedImage = config.protectFaces && faces > 0 &&
            (category == Category.REAL_PHOTO || pReal >= config.faceProtectMinReal)
        if (protectedImage && category.isJunk) {
            reasons += "faces + plausible real photo → protected"
            category = Category.UNCERTAIN
        }
        return build(category, top.value, protectedImage)
    }

    private fun features(e: Evidence): Map<String, Double> = buildMap {
        val t = e.traits
        put("exif", e.exif.cameraEvidence)
        put("saturation", t.meanSaturation); put("vivid", t.vividFraction); put("colorfulness", t.colorfulness)
        put("flat", t.flatFraction); put("smooth", t.smoothFraction); put("edges", t.edgeFraction)
        put("palette90", t.paletteSize90.toDouble()); put("border", t.borderScore); put("noise", t.noiseLevel)
        e.ocr?.let { put("textArea", it.textAreaRatio); put("captionBand", it.captionBandRatio); put("lines", it.lineCount.toDouble()) }
        e.text?.let { put("words", it.wordCount.toDouble()); put("greetingKw", it.greeting) }
        e.faces?.let { put("faces", it.toDouble()) }
    }

    companion object {
        private val SCREENSHOT_NAME = Regex("""screenshot|screen_?shot|^scr_|^screen-""")
        private val CAMERA_NAME = Regex("""^(img|vid|pxl|dsc|dcim|photo)_?\d{8}""")
        private val SCREEN_RECORDING_NAME = Regex("""screen[_ -]?record|screenrecord|^record_""")
        private const val MIN_GREETING_WORDS = 2
        private val PHONE_WIDTHS = setOf(720, 750, 828, 1080, 1125, 1170, 1179, 1242, 1284, 1290, 1440)

        /** 0 below [lo], 1 above [hi], linear between. */
        fun ramp(x: Double, lo: Double, hi: Double): Double = ((x - lo) / (hi - lo)).coerceIn(0.0, 1.0)

        fun softmax(logits: Map<Category, Double>): Map<Category, Double> {
            val max = logits.values.max()
            val exps = logits.mapValues { exp(it.value - max) }
            val sum = exps.values.sum()
            return exps.mapValues { it.value / sum }
        }
    }
}

private val Category.shortName: String
    get() = when (this) {
        Category.REAL_PHOTO -> "real"
        Category.GREETING -> "greeting"
        Category.MEME -> "meme"
        Category.SCREENSHOT -> "screen"
        Category.UNCERTAIN -> "uncertain"
    }
