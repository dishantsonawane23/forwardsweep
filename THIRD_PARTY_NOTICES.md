# Third-party notices

ForwardSweep's own code is licensed under GPL-3.0 (see [LICENSE](LICENSE)). It bundles or uses
the following, each under its own licence.

## Bundled model

**Meta Perception Encoder, PE-Core-T-16-384** (image tower, exported to ONNX),
`app/src/main/assets/models/clip_image.onnx`, and text-prompt embeddings computed with the same
model, `clip_prompts.tsv`.
Copyright (c) Meta Platforms, Inc. and affiliates. Licensed under the Apache License 2.0.
Source: https://huggingface.co/facebook/PE-Core-T16-384 ·
https://github.com/facebookresearch/perception_models
Modifications: exported to ONNX (fp32) via open_clip; only the image tower is included.

## Libraries (linked at build time)

| Component | Licence |
|---|---|
| ONNX Runtime (`com.microsoft.onnxruntime`) | MIT |
| Google ML Kit Text Recognition (Latin, Devanagari) and Face Detection | [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) (proprietary, free to use) |
| AndroidX (Compose, Room, WorkManager, DataStore, Lifecycle, Activity) | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 |
| Coil (`io.coil-kt`) | Apache-2.0 |
| metadata-extractor (desktop harness only) | Apache-2.0 |
| open_clip (export script only, not bundled) | MIT |

## Promo video (`marketing/ad/`)

* **Photos** in `marketing/ad/assets/` (and the greeting cards and memes made from them) come
  from Wikimedia Commons under CC0, public domain, or CC BY. Authors, licences and sources are
  listed in [marketing/ad/assets/PHOTO_CREDITS.md](marketing/ad/assets/PHOTO_CREDITS.md). Credit
  them when you publish the video.
* **Voiceover:** generated locally with [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M)
  (Apache-2.0), voice `af_bella`.
* **Music and sound effects:** synthesised by `marketing/ad/audio.py`; no third-party audio.
* **Emoji** in the video are rendered with Microsoft's Segoe UI Emoji font on Windows; the font
  itself is not distributed.

## Trademarks

WhatsApp is a trademark of WhatsApp LLC. Android is a trademark of Google LLC. ForwardSweep is an
independent project and is not affiliated with or endorsed by WhatsApp, Meta or Google.
