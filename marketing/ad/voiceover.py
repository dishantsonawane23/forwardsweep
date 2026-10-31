"""Voiceover for the ad with Kokoro (open-weight TTS, Apache-2.0), voice af_bella, run locally.

    python voiceover.py <out dir>
"""
import os, sys
import numpy as np, soundfile as sf
from kokoro import KPipeline

LINES = {
    "01": "This is my phone after one week in the family WhatsApp group.",
    "02": "Somewhere under all those good mornings are my actual photos and videos.",
    "03": "ForwardSweep sorts the forwards from the memories, right on the phone.",
    "04": "Good mornings, festival wishes, memes and forwarded videos, already picked out.",
    "05": "My own photos and videos stay out of it, and I see everything before it goes.",
    "06": "One tap clears it. Changed my mind? It's in the trash for thirty days.",
    "07": "ForwardSweep. Free, open source, and nothing leaves your phone.",
}
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
tts = KPipeline(lang_code="a")
for k, text in LINES.items():
    audio = np.concatenate([a for _, _, a in tts(text, voice="af_bella", speed=1.0)])
    sf.write(os.path.join(out, f"vo_{k}.wav"), audio, 24000)
    print(k, round(len(audio) / 24000, 2))
