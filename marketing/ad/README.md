# ForwardSweep promo video

`forwardsweep-ad.mp4`: 38.5 s, 1080×1920 (9:16), 30 fps, H.264 + AAC, loudness -14 LUFS.
For Instagram Reels, YouTube Shorts and WhatsApp Status. Captions are burned in, so it also works
muted. `forwardsweep-ad-keyframes.jpg` shows one frame per scene with the platform-UI areas
shaded red; nothing load-bearing sits there.

| Start | Scene | Caption | Voiceover (Kokoro, voice `af_bella`) |
|---|---|---|---|
| 0 s | Greetings and memes rain down and pile up | My phone after one week in the family WhatsApp group. | This is my phone after one week in the family WhatsApp group. |
| 4.8 s | The pile dims; real memories glow inside it | Somewhere in there are my actual photos and videos. | Somewhere under all those good mornings are my actual photos and videos. |
| 10.2 s | Home screen | ForwardSweep sorts the forwards from the memories. | ForwardSweep sorts the forwards from the memories, right on the phone. |
| 15.0 s | Greetings grid, pre-selected | Good mornings, wishes, memes, forwarded videos. Already picked out. | Good mornings, festival wishes, memes and forwarded videos, already picked out. |
| 20.9 s | Family / Friends / Trips / Selfies / Kids / Food tiles with shields | My own photos and videos stay out of it. | My own photos and videos stay out of it, and I see everything before it goes. |
| 26.7 s | "Moved to trash" result | One tap. 30 days in the trash if I change my mind. | One tap clears it. Changed my mind? It's in the trash for thirty days. |
| 32.4 s | End card | ForwardSweep · Sweep the forwards. Keep the memories. · Free · Open source | ForwardSweep. Free, open source, and nothing leaves your phone. |

Every claim maps to shipped behaviour: only greetings and memes are pre-selected, you review
before deleting, deletion goes to a 30-day trash, and the app has no INTERNET permission.

## Layout rules

* All captions and key visuals stay inside the cross-platform safe band (x 180–900,
  y 220–1420); Reels/Shorts/TikTok UI covers the rest.
* Captions are static (no entrance animation) and auto-size to fit the band.
* The "your photos" scene uses emoji tiles rather than photos of people, so no model releases
  are needed.

## Assets

| Path | What | Made by |
|---|---|---|
| `assets/cards/card_*.jpg` | 12 WhatsApp-style greetings (Marathi, Hindi, English) | `greeting_cards.py`: Commons photo + HTML text rendered by headless Edge (correct Devanagari shaping) |
| `assets/cards/meme_*.jpg` | 4 top/bottom-caption memes | `meme_cards.py` |
| `assets/stock/*.jpg` | 5 "memory" photos with no identifiable faces | Wikimedia Commons (`commons.py`) |
| `assets/ui/*.png` | Home, Greetings grid, clean-up result | Emulator screenshots of the app with the cards, memes and stock photos loaded as a demo WhatsApp library |
| `assets/PHOTO_CREDITS.md` | Author, licence and source of every photo | `commons.py` keeps only CC0, public domain and CC BY |
| `voice-samples/` | The four Kokoro voices that were compared | `voiceover.py` |

**Attribution is required.** Several photos are CC BY. When you post the video, add a credit
line to the description, for example: "Photos: Wikimedia Commons contributors, see
PHOTO_CREDITS.md in the ForwardSweep repository."

## Audio

* **Voiceover:** `voiceover.py` runs [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M)
  (Apache-2.0) locally, voice `af_bella`.
* **Music:** synthesised in `audio.py`, so there's nothing to license: 100 BPM, D major,
  I–V–vi–IV, a detuned pad, Karplus–Strong plucked arpeggios, bass, and kick + shaker after the
  hook. Fades out over the last 0.8 s.
* **Effects:** whooshes on scene changes, pops as the first greetings land, a chime on "moved to
  trash", a shimmer on the end card.
* **Mix (ffmpeg):** voice lines placed on cue, music side-chain ducked under the voice,
  loudness-normalised to -14 LUFS, true peak -1.5 dB.

## Rebuild

Windows with Python 3 (Pillow, numpy, `kokoro`, `soundfile`), ffmpeg on PATH, Microsoft Edge,
and the Segoe UI / Nirmala UI fonts.

```bash
python marketing/ad/voiceover.py build/vo
python marketing/ad/audio.py build/vo build/soundtrack.wav
python marketing/ad/make_ad.py . build/ad_silent.mp4
ffmpeg -i build/ad_silent.mp4 -i build/soundtrack.wav -map 0:v -map 1:a -c:v copy -c:a aac -b:a 192k -shortest marketing/ad/forwardsweep-ad.mp4
```

Scene timing lives in `T` in both `make_ad.py` and `audio.py`, and voice cues in `VO_AT`
(`audio.py`); change them together if you edit the script. To regenerate the greeting cards:
`python marketing/ad/commons.py <stock dir>`, then `greeting_cards.py` and `meme_cards.py`.
