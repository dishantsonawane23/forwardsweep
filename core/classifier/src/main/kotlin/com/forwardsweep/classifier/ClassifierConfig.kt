package com.forwardsweep.classifier

/** All tunable thresholds. Exposed in Settings → Advanced and persisted with DataStore. */
data class ClassifierConfig(
    /** Below this top-category probability the image is Uncertain. */
    val minConfidence: Double = 0.75,
    /** A junk label also requires P(Real Photo) below this. */
    val realPhotoVeto: Double = 0.10,
    /** Camera EXIF evidence at or above this never yields a junk label. */
    val strongExif: Double = 0.6,
    /** Protect images with faces when P(Real Photo) is at least this. */
    val faceProtectMinReal: Double = 0.15,
    val protectFaces: Boolean = true,
    /** pHash Hamming distance for "same image" (duplicates and correction memory). */
    val duplicateDistance: Int = 6,
    /** Longest side the pipeline decodes at; OCR needs ~1000px for small text. */
    val decodeMaxSide: Int = 1024,
    val weights: Weights = Weights(),
) {
    /** Evidence weights in logit units. Tuned on eval/ — see README "Tuning". */
    data class Weights(
        val exif: Double = 4.0,
        val greetingKeyword: Double = 3.5,
        val memeCaption: Double = 2.4,
        val memePhrase: Double = 2.5,
        val documentText: Double = 2.5,
        val screenUiText: Double = 2.0,
        val screenshotFilename: Double = 3.0,
        val vivid: Double = 1.6,
        val border: Double = 1.2,
        val flatness: Double = 3.0,
        val sensorNoise: Double = 1.2,
        /** CLIP is the strongest single signal on real WhatsApp libraries. */
        val clip: Double = 3.0,
        val priorReal: Double = 0.4,
        val priorMeme: Double = -0.3,
    )
}
