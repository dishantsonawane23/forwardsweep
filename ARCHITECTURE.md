# ForwardSweep — Architecture

ForwardSweep scans the phone's images, sorts each one into **Real Photo / Greeting / Meme /
Screenshot·Document / Uncertain**, and lets the user bulk-delete junk safely. Everything runs
on-device; no image or derived data ever leaves the phone.

## Guiding rule: asymmetric risk

Deleting one real family photo is far worse than leaving 50 greetings behind. Every design
choice follows from that:

* Only **Greeting** and **Meme** are ever pre-selected for deletion.
* A junk label needs **high confidence (≥ 0.75 by default)** *and* a low Real-Photo
  probability (the *real-photo veto*, default < 0.10). Anything else becomes **Uncertain**.
* Strong camera EXIF caps the junk probability, so a real photo of a "Good Morning Café" sign
  stays a real photo.
* Images with detected faces and a non-trivial Real-Photo probability are **protected**:
  they are never pre-selected and the user must deliberately pick them.
* Deletion goes to the **system trash** (`createTrashRequest`, 30-day recovery) whenever the
  OS supports it.

## Modules

```
ForwardSweep/
├── core/classifier      Pure Kotlin/JVM. Signals, scoring, pHash, duplicate groups,
│                         feedback learning, ONNX CLIP engine. No Android dependency, so the
│                         exact same code runs in unit tests and the desktop harness.
├── tools/harness        JVM CLI. Runs the pipeline on a folder of images, writes an HTML
│                         report, and evaluates precision/recall on a labeled test set.
├── eval/                Test-set generator, evaluation data, CLIP export script.
│
├── core/ml              Android library. Adapters that implement the classifier's
│                         interfaces: ML Kit Text (Latin + Devanagari), ML Kit Face,
│                         ONNX Runtime Mobile for CLIP, ExifInterface, Bitmap → RgbImage.
├── core/data            Android library. Room cache, MediaStore repository,
│                         WorkManager scan worker, deletion (trash / delete requests).
└── app                  Compose UI (Material 3, dynamic color), ViewModels. DI is a small manual graph
                          (`DataGraph`); the app is too small to need Hilt.
                          Screens: Home, Review, Preview, Settings.
```

Dependency direction: `app → core/data → core/ml → core/classifier`. `core/classifier`
depends on nothing Android-specific; Android code plugs in through small interfaces.

## Classification pipeline

```
           ┌──────────────┐
 image ───►│ ImageInput   │  meta (name, size, dims, mime) + pixels (≤1024px) + EXIF
           └──────┬───────┘
                  │
   ┌──────────────┼──────────────────┬──────────────┬──────────────┐
   ▼              ▼                  ▼              ▼              ▼
 EXIF          ImageTraits         OCR            CLIP          Faces
 camera        saturation,         text boxes →   zero-shot     count
 evidence      colorfulness,       TextAnalyzer   prompts →
               flatness, palette,  (greeting kw,  P(category)
               border, noise       meme, doc, UI)
   │              │                  │              │              │
   └──────────────┴────────┬─────────┴──────────────┴──────────────┘
                           ▼
                ┌─────────────────────┐
                │ Scorer              │  weighted evidence → logits per category
                │                     │  + feedback biases → softmax
                │                     │  + safety rules (EXIF cap, real veto,
                │                     │    face protection, threshold → Uncertain)
                └─────────┬───────────┘
                          ▼
                  Classification  {category, confidence, probabilities,
                                   reasons[], protected, preselected, pHash}
                          │
                          ▼
                DuplicateGrouper (pHash BK-tree, Hamming ≤ 6)
```

Every signal is **optional**. If the CLIP model isn't downloaded, OCR fails, or the device
is low on memory, the scorer works with what it has; missing evidence never pushes an image
toward junk. Each classification carries human-readable `reasons` ("greeting keyword:
'good morning'", "camera EXIF: Samsung SM-G991B") used for debugging and in the preview UI.

### Signals and why they matter

| Signal | Implementation | Strongest for |
|---|---|---|
| Camera EXIF (make/model, exposure, focal length, GPS, DateTimeOriginal) | `ExifFeatures` | Real Photo. Absence is only weak evidence: WhatsApp strips EXIF on forward |
| Filename (`Screenshot_…`, `IMG-…-WA…`) | `Scorer` | Screenshot; WhatsApp origin |
| Phone-screen aspect ratio | `Scorer` | Screenshot |
| OCR keywords, Latin + Devanagari, fuzzy | `TextAnalyzer` | Greeting (सुप्रभात, शुभ रात्रि, good morning…), Document (total, invoice, UPI…) |
| Text area + caption position (top/bottom bands) | `OcrResult` | Meme |
| Many short text lines + UI words ("last seen", "type a message") | `TextAnalyzer` | Screenshot |
| Saturation, colorfulness, uniform border | `ImageTraits` | Greeting |
| Flatness, small palette, low sensor noise | `ImageTraits` | Screenshot / synthetic graphics vs camera |
| CLIP zero-shot (Meta PE-Core-T image encoder + precomputed prompt embeddings) | `OnnxClipEngine` | All. The best single signal when available |
| Face count | `FaceDetector` (ML Kit) | Protection of real photos |
| 64-bit DCT pHash | `PHash`, `DuplicateGrouper` | Grouping forwarded duplicates; correction memory |

### Learning from corrections

`Scorer` is split in two. `score()` turns evidence into default logits and returns a
`ScoreState` (logits, reasons, EXIF strength, faces, OCR word count). `decide()` adds the
`PersonalModel` and applies the safety rules. Every image's `ScoreState` and CLIP embedding are
stored in Room, so after a correction the library is re-sorted by calling `decide()` on stored
data, without decoding any image.

`PersonalModel` learns from moves (weight 1.0) and deletions of greetings/memes (weight 0.5):

1. **Exact memory.** Near-duplicates (pHash Hamming ≤ 6) of a moved image take its label.
2. **Look-alikes.** The 5 nearest labelled examples by CLIP cosine add up to +4 logits to their
   label, counting only similarity above 0.60 (unrelated images sit around 0.15–0.30).
3. **Residual model.** Softmax regression on the embedding, trained on top of the default
   logits (no bias term), enabled from 8 examples.

Examples live in the `corrections` table with their own copy of the embedding and scores, so
they survive the image being deleted. "Forget what it learned" in Settings clears them.

## Data layer

Room tables:

* `scanned_images(uri PK, mediaId, displayName, sizeBytes, width, height, dateTaken,
  dateModified, phash, category, confidence, probsJson, protected, faces, scannedAt,
  classifierVersion)`
* `corrections(uri PK, phash, fromCategory, toCategory, correctedAt)`

A rescan queries MediaStore for `DATE_MODIFIED > lastScan` or URIs missing from the cache, so
only new images are processed. Bumping `classifierVersion` forces reclassification.

`ScanWorker` (WorkManager, `CoroutineWorker`) processes batches of 50, writes progress via
`setProgress`, and uses `setRequiresCharging(true)` when the backlog is over 2,000 images.

## Deletion

| Android | API | UX |
|---|---|---|
| 11+ (API 30) | `MediaStore.createTrashRequest` (default) or `createDeleteRequest` | One system dialog for the whole batch. Trash is recoverable for 30 days |
| 10 (API 29) | `ContentResolver.delete`, catch `RecoverableSecurityException`, launch its `IntentSender` per item | One dialog per item that the app doesn't own |
| 8–9 (API 26–28) | `ContentResolver.delete` with `WRITE_EXTERNAL_STORAGE` (maxSdk 28) | No dialog |

Permissions: `READ_MEDIA_IMAGES` (33+), `READ_MEDIA_VISUAL_USER_SELECTED` handling for partial
access (34+), `READ_EXTERNAL_STORAGE` (maxSdk 32). `MANAGE_EXTERNAL_STORAGE` is never used.
WhatsApp folders are found via MediaStore `RELATIVE_PATH LIKE
'Android/media/com.whatsapp%/WhatsApp%Images%'` (API 29+) or the `DATA` column (API 26–28).

## UI

One `AppViewModel` exposes a single `StateFlow<UiState>` (summary, scan progress, selection,
settings) plus a small back stack; the app is too small for per-screen ViewModels or a navigation
library. `MainActivity` renders the top of the stack inside `SharedTransitionLayout` +
`AnimatedContent`, which gives the grid → preview shared-element transition.

Selection = classifier pre-selection (Greeting/Meme, not protected) overridden by the user's
explicit picks. The overrides are persisted in DataStore so they survive process death.

Screens: Permission, Home (ring chart, category cards, Clean up), Review (staggered grid,
drag-to-select, sticky bar), Preview (pager, reasons), Settings. Images load through Coil with a
shared memory-cache key, so the preview starts from the already-decoded thumbnail.
