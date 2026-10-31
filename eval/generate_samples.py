"""
Generate a small *synthetic* labeled test set for smoke-testing the ForwardSweep pipeline.

    python eval/generate_samples.py [--out eval/sample-set] [--seed 7]

Creates  real/  greeting/  meme/  screenshot/  with JPEG/PNG images plus a
`<image>.signals.json` sidecar per image that simulates what ML Kit would return on the phone
(OCR text boxes, face count). OCR is deliberately imperfect: ~12% of images lose all text and
~10% get a character error, so the pixel/EXIF signals have to carry some of the load.

The set includes hard cases on purpose:
  * real photos of a "GOOD MORNING CAFE" sign and a birthday banner with faces
  * vivid flower close-ups without EXIF (look like greeting backgrounds)
  * greetings on photo backgrounds, memes with no text, forwarded screenshots
  * re-encoded duplicate greetings (pHash grouping)

Synthetic images are NOT a substitute for real WhatsApp data. Put real images into the same
folder structure (eval/testset/real, ...) to measure what matters. See README "Evaluation".
"""
import argparse
import json
import math
import random
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont, TiffImagePlugin

FONTS = "C:/Windows/Fonts/"
LATIN_FONTS = ["georgiab.ttf", "arialbd.ttf", "impact.ttf", "timesbi.ttf", "georgiaz.ttf"]
DEVANAGARI_FONT = "Nirmala.ttc"

GREETINGS = [
    ("Good Morning", "latin"), ("GOOD MORNING", "latin"), ("Good Night", "latin"),
    ("Sweet Dreams", "latin"), ("Have a Nice Day", "latin"), ("Happy Diwali", "latin"),
    ("Happy Sunday", "latin"), ("Suprabhat", "latin"), ("Shubh Ratri", "latin"),
    ("Happy Navratri", "latin"), ("Happy Holi", "latin"), ("Good Evening", "latin"),
    ("सुप्रभात", "deva"), ("शुभ प्रभात", "deva"), ("शुभ रात्रि", "deva"), ("शुभ सकाळ", "deva"),
    ("दिवाळीच्या हार्दिक शुभेच्छा", "deva"), ("जय श्री कृष्ण", "deva"),
]
QUOTES = [
    "Every morning is a new beginning", "Smile and let the world wonder why",
    "Life is beautiful, enjoy every moment", "Stay blessed with your family",
    "Keep smiling always", "May God bless you", "आपका दिन मंगलमय हो", "हमेशा खुश रहो",
]
MEME_CAPTIONS = [
    ("WHEN YOU SEE THE EXAM PAPER", "AND REALIZE YOU STUDIED THE WRONG CHAPTER"),
    ("ME TRYING TO WAKE UP AT 6 AM", ""), ("NOBODY:", "ABSOLUTELY NOBODY:"),
    ("WHEN THE WIFI GOES DOWN", "FOR 5 MINUTES"), ("MONDAY BE LIKE", ""),
    ("WHEN MOM SAYS", "WE HAVE FOOD AT HOME"), ("POV: YOU OPENED THE FAMILY GROUP", ""),
    ("ONE DOES NOT SIMPLY", "LEAVE THE GROUP CHAT"), ("ME AFTER EATING ONE SALAD", "I AM BASICALLY AN ATHLETE"),
    ("EXPECTATION VS REALITY", ""), ("WHEN THE TEACHER SAYS", "THIS WILL BE ON THE TEST"),
    ("BOSS: CAN YOU WORK SATURDAY", "ME:"),
]
CHAT_LINES = [
    "Hi, are you coming today?", "Yes, will reach by 6", "Ok see you", "Did you send the file?",
    "Sent it on email", "Thanks!", "Call me when free", "Meeting at 4 pm", "Where are you?",
    "On the way", "Happy to help", "Let me check and tell you",
]
CAMERAS = [("samsung", "SM-A525F"), ("Xiaomi", "Redmi Note 10"), ("OnePlus", "IN2011"),
           ("Google", "Pixel 6a"), ("vivo", "V2111"), ("Apple", "iPhone 12")]


def font(name, size):
    return ImageFont.truetype(FONTS + name, size)


# ── Pixel generators ────────────────────────────────────────────────────────────────────────

def smooth_noise(rng, h, w, scale):
    small = rng.random((max(2, h // scale), max(2, w // scale)))
    img = Image.fromarray((small * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC)
    return np.asarray(img).astype(np.float32) / 255.0


def photo_like(rng, w, h, kind="outdoor", vivid=False):
    """Camera-ish image: natural colour layout, texture, sensor noise, mild blur."""
    y = np.linspace(0, 1, h)[:, None]
    horizon = rng.uniform(0.35, 0.65)
    if kind == "outdoor":
        sky = np.stack([0.45 + 0.2 * y, 0.62 + 0.15 * y, 0.85 - 0.1 * y], -1) * np.ones((h, w, 1))
        ground_col = np.array(random.choice([(0.25, 0.45, 0.2), (0.45, 0.38, 0.28), (0.5, 0.5, 0.48)]))
        ground = ground_col * (0.7 + 0.6 * smooth_noise(rng, h, w, 9)[..., None])
        mask = (y > horizon + 0.05 * (smooth_noise(rng, h, w, 40)[:, :1] - 0.5))
        img = np.where(mask[..., None], ground, sky)
    else:  # indoor / people
        base = np.array(random.choice([(0.62, 0.5, 0.4), (0.55, 0.52, 0.5), (0.7, 0.6, 0.45)]))
        img = base * (0.6 + 0.6 * smooth_noise(rng, h, w, 30)[..., None])
    img = img * (0.85 + 0.3 * smooth_noise(rng, h, w, 5)[..., None])  # texture
    pil = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(pil)
    for _ in range(rng.integers(3, 9)):  # objects
        cx, cy = rng.integers(0, w), rng.integers(int(h * 0.3), h)
        r = int(rng.integers(w // 30, w // 7))
        col = tuple(int(c) for c in rng.integers(40, 200, 3))
        d.ellipse([cx - r, cy - r, cx + r, cy + int(r * 1.4)], fill=col)
    if vivid:  # flower close-up: saturated petals
        for _ in range(rng.integers(4, 9)):
            cx, cy, r = rng.integers(0, w), rng.integers(0, h), int(rng.integers(w // 12, w // 5))
            col = random.choice([(230, 30, 60), (250, 200, 20), (240, 90, 160), (255, 120, 0)])
            for k in range(8):
                a = k * math.pi / 4
                px, py = cx + int(r * 0.6 * math.cos(a)), cy + int(r * 0.6 * math.sin(a))
                d.ellipse([px - r // 2, py - r // 2, px + r // 2, py + r // 2], fill=col)
            d.ellipse([cx - r // 4, cy - r // 4, cx + r // 4, cy + r // 4], fill=(250, 220, 60))
    pil = pil.filter(ImageFilter.GaussianBlur(rng.uniform(0.6, 1.6)))
    arr = np.asarray(pil).astype(np.float32)
    arr += rng.normal(0, rng.uniform(3.5, 8.0), arr.shape)  # sensor noise
    return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))


def draw_people(rng, img, n):
    d = ImageDraw.Draw(img)
    w, h = img.size
    for i in range(n):
        cx = int(w * (i + 1) / (n + 1)) + int(rng.integers(-20, 20))
        r = int(h * rng.uniform(0.07, 0.11))
        cy = int(h * rng.uniform(0.35, 0.5))
        skin = random.choice([(224, 172, 140), (198, 134, 96), (160, 105, 70)])
        d.rectangle([cx - int(r * 1.3), cy + r, cx + int(r * 1.3), h], fill=tuple(int(c) for c in rng.integers(30, 220, 3)))
        d.ellipse([cx - r, cy - r, cx + r, cy + int(r * 1.2)], fill=skin)
        d.ellipse([cx - r, cy - r - 4, cx + r, cy - r // 3], fill=(30, 25, 20))
    return img


def gradient(w, h, c1, c2, radial=False):
    if radial:
        yy, xx = np.mgrid[0:h, 0:w]
        t = np.sqrt(((xx - w / 2) / w) ** 2 + ((yy - h / 2) / h) ** 2) * 1.6
    else:
        t = np.linspace(0, 1, h)[:, None] * np.ones((1, w))
    t = np.clip(t, 0, 1)[..., None]
    arr = np.array(c1) * (1 - t) + np.array(c2) * t
    return Image.fromarray(arr.astype(np.uint8))


# ── Text with simulated OCR ─────────────────────────────────────────────────────────────────

class Canvas:
    def __init__(self, img):
        self.img = img.convert("RGB")
        self.d = ImageDraw.Draw(self.img)
        self.blocks = []

    def text(self, xy, s, fnt, fill, stroke=0, stroke_fill=None, anchor="mm", ocr=True):
        self.d.text(xy, s, font=fnt, fill=fill, stroke_width=stroke, stroke_fill=stroke_fill, anchor=anchor)
        if ocr:
            l, t, r, b = self.d.textbbox(xy, s, font=fnt, anchor=anchor, stroke_width=stroke)
            W, H = self.img.size
            self.blocks.append({"text": s, "box": [max(0, l / W), max(0, t / H), min(1, r / W), min(1, b / H)]})

    def fit_font(self, s, name, max_w, start):
        size = start
        while size > 14:
            f = font(name, size)
            if self.d.textlength(s, font=f) <= max_w:
                return f
            size -= 4
        return font(name, 14)


def corrupt(blocks, rng):
    r = rng.random()
    if r < 0.12:
        return []  # OCR found nothing (stylised font, low contrast)
    if r < 0.22:
        out = []
        for b in blocks:
            s = list(b["text"])
            if len(s) > 4:
                i = int(rng.integers(0, len(s)))
                s[i] = random.choice(["0", "1", "l", "", "rn"])
            out.append({**b, "text": "".join(s)})
        return out
    return blocks


def save(img, path, quality=None, exif=None, blocks=(), faces=0):
    path.parent.mkdir(parents=True, exist_ok=True)
    kw = {}
    if path.suffix == ".jpg":
        kw["quality"] = quality or 82
        if exif is not None:
            kw["exif"] = exif.tobytes()
    img.save(path, **kw)
    sidecar = {"ocr": list(blocks), "faces": faces}
    path.with_name(path.name + ".signals.json").write_text(json.dumps(sidecar, ensure_ascii=False), encoding="utf-8")


def camera_exif(rng):
    ex = Image.Exif()
    make, model = random.choice(CAMERAS)
    ex[0x010F] = make
    ex[0x0110] = model
    sub = ex.get_ifd(0x8769)
    sub[0x829A] = TiffImagePlugin.IFDRational(1, int(rng.choice([60, 120, 250, 500])))
    sub[0x920A] = TiffImagePlugin.IFDRational(int(rng.integers(40, 70)), 10)
    sub[0x9003] = "2024:%02d:%02d 10:%02d:00" % (rng.integers(1, 13), rng.integers(1, 28), rng.integers(0, 60))
    return ex


def wa_name(rng, ext="jpg"):
    return "IMG-2024%02d%02d-WA%04d.%s" % (rng.integers(1, 13), rng.integers(1, 28), rng.integers(0, 9999), ext)


# ── Categories ──────────────────────────────────────────────────────────────────────────────

def make_real(rng, out, n):
    for i in range(n):
        w, h = random.choice([(1000, 750), (750, 1000), (900, 900)])
        kind = random.choice(["outdoor", "outdoor", "indoor", "people", "people", "flowers"])
        faces = 0
        img = photo_like(rng, w, h, "indoor" if kind in ("indoor", "people") else "outdoor", vivid=(kind == "flowers"))
        if kind == "people":
            faces = int(rng.integers(1, 5))
            img = draw_people(rng, img, faces)
        c = Canvas(img)
        if i % 15 == 3:  # hard: shop sign with greeting words
            c.d.rectangle([w * 0.2, h * 0.12, w * 0.8, h * 0.26], fill=(40, 40, 40))
            c.text((w / 2, h * 0.19), "GOOD MORNING CAFE", font("arialbd.ttf", int(w / 22)), (240, 220, 160))
        if i % 15 == 7:  # hard: birthday banner with faces
            faces = max(faces, 3)
            c.img = draw_people(rng, c.img, 3); c.d = ImageDraw.Draw(c.img)
            c.text((w / 2, h * 0.08), "HAPPY BIRTHDAY", font("arialbd.ttf", int(w / 16)), (250, 60, 120))
        arr = np.asarray(c.img).astype(np.float32) + rng.normal(0, 3, (h, w, 3))
        img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
        has_exif = rng.random() < 0.55  # the rest were forwarded through WhatsApp (EXIF stripped)
        name = ("IMG_2024%02d%02d_%06d.jpg" % (rng.integers(1, 13), rng.integers(1, 28), rng.integers(0, 999999))) if has_exif else wa_name(rng)
        save(img, out / "real" / name, quality=int(rng.integers(80, 93)), exif=camera_exif(rng) if has_exif else None,
             blocks=corrupt(c.blocks, rng), faces=faces)


PALETTES = [((255, 200, 40), (255, 80, 20)), ((255, 120, 180), (140, 40, 200)), ((40, 200, 255), (20, 60, 200)),
            ((255, 230, 120), (250, 120, 40)), ((20, 20, 70), (90, 30, 140)), ((120, 230, 120), (20, 140, 90)),
            ((255, 90, 90), (255, 200, 60))]


def make_greeting(rng, out, n):
    paths = []
    for i in range(n):
        w, h = random.choice([(800, 800), (720, 960), (1000, 1000)])
        if rng.random() < 0.3:  # greeting on a sunrise/flower photo background
            img = photo_like(rng, w, h, "outdoor", vivid=True)
        else:
            c1, c2 = random.choice(PALETTES)
            img = gradient(w, h, c1, c2, radial=rng.random() < 0.4)
        c = Canvas(img)
        d = c.d
        for _ in range(rng.integers(2, 7)):  # flowers / suns / hearts
            cx, cy, r = int(rng.integers(0, w)), int(rng.integers(0, h)), int(rng.integers(w // 18, w // 7))
            col = random.choice([(255, 40, 90), (255, 220, 0), (255, 255, 255), (250, 100, 200), (255, 140, 0)])
            for k in range(6):
                a = k * math.pi / 3
                px, py = cx + int(r * 0.7 * math.cos(a)), cy + int(r * 0.7 * math.sin(a))
                d.ellipse([px - r // 2, py - r // 2, px + r // 2, py + r // 2], fill=col)
            d.ellipse([cx - r // 3, cy - r // 3, cx + r // 3, cy + r // 3], fill=(255, 230, 80))
        if rng.random() < 0.5:  # decorative frame
            bw = int(min(w, h) * rng.uniform(0.03, 0.06))
            col = random.choice([(255, 215, 0), (255, 255, 255), (200, 20, 60), (40, 120, 40)])
            d.rectangle([0, 0, w - 1, h - 1], outline=col, width=bw)
        text, script = random.choice(GREETINGS)
        fname = DEVANAGARI_FONT if script == "deva" else random.choice(LATIN_FONTS)
        f = c.fit_font(text, fname, w * 0.85, int(w / 7))
        y = h * rng.uniform(0.2, 0.75)
        c.text((w / 2, y), text, f, random.choice([(255, 255, 255), (255, 240, 0), (200, 0, 60)]), stroke=3, stroke_fill=(60, 20, 0))
        if rng.random() < 0.7:
            q = random.choice(QUOTES)
            fq = c.fit_font(q, DEVANAGARI_FONT if any("\u0900" <= ch <= "\u097f" for ch in q) else "georgiai.ttf", w * 0.85, int(w / 20))
            c.text((w / 2, min(h * 0.92, y + h * 0.15)), q, fq, (255, 255, 255), stroke=2, stroke_fill=(0, 0, 0))
        if rng.random() < 0.4:  # forwarder's watermark
            c.text((w * 0.97, h * 0.97), "~ Ramesh Patil", font("ariali.ttf", 16), (255, 255, 255), anchor="rb")
        p = out / "greeting" / wa_name(rng)
        save(c.img, p, quality=int(rng.integers(65, 85)), blocks=corrupt(c.blocks, rng))
        paths.append(p)
    # Forwarded duplicates: resized + recompressed copies.
    for p in paths[:8]:
        img = Image.open(p)
        img = img.resize((int(img.width * 0.8), int(img.height * 0.8)), Image.BILINEAR)
        q = out / "greeting" / ("dup_" + wa_name(rng))
        img.save(q, quality=60)
        q.with_name(q.name + ".signals.json").write_text(p.with_name(p.name + ".signals.json").read_text(encoding="utf-8"), encoding="utf-8")


def make_meme(rng, out, n):
    for i in range(n):
        w, h = random.choice([(700, 700), (800, 600), (640, 800)])
        faces = 0
        if rng.random() < 0.75:
            img = photo_like(rng, w, h, "indoor", vivid=False)
            if rng.random() < 0.55:
                faces = int(rng.integers(1, 3))
                img = draw_people(rng, img, faces)
        else:  # flat cartoon reaction image
            img = Image.new("RGB", (w, h), tuple(int(c) for c in rng.integers(150, 255, 3)))
            d = ImageDraw.Draw(img)
            d.ellipse([w * 0.25, h * 0.2, w * 0.75, h * 0.8], fill=(255, 220, 80), outline=(0, 0, 0), width=6)
            d.arc([w * 0.35, h * 0.45, w * 0.65, h * 0.7], 0, 180, fill=(0, 0, 0), width=8)
        top, bottom = random.choice(MEME_CAPTIONS)
        style = "none" if rng.random() < 0.1 else random.choice(["impact", "impact", "bar"])
        if style == "bar":  # Twitter/Instagram-style caption bar above the picture
            bar = int(h * 0.22)
            canvas = Image.new("RGB", (w, h + bar), (255, 255, 255))
            canvas.paste(img, (0, bar))
            c = Canvas(canvas)
            cap = (top + " " + bottom).strip().capitalize()
            f = c.fit_font(cap, "arial.ttf", w * 0.92, 34)
            c.text((w * 0.04, bar / 2), cap, f, (10, 10, 10), anchor="lm")
        else:
            c = Canvas(img)
            if style == "impact":
                c.text((w / 2, h * 0.1), top, c.fit_font(top, "impact.ttf", w * 0.94, int(w / 11)), (255, 255, 255), stroke=3, stroke_fill=(0, 0, 0))
                if bottom:
                    c.text((w / 2, h * 0.9), bottom, c.fit_font(bottom, "impact.ttf", w * 0.94, int(w / 11)), (255, 255, 255), stroke=3, stroke_fill=(0, 0, 0))
        save(c.img, out / "meme" / wa_name(rng), quality=int(rng.integers(60, 82)), blocks=corrupt(c.blocks, rng), faces=faces)


def make_screenshot(rng, out, n):
    for i in range(n):
        kind = random.choice(["chat", "chat", "app", "receipt", "qr"])
        if kind in ("chat", "app"):
            w, h = 540, 1170
            dark = rng.random() < 0.4
            bg = (17, 27, 33) if dark else (236, 229, 221)
            c = Canvas(Image.new("RGB", (w, h), bg))
            d = c.d
            fg = (230, 230, 230) if dark else (20, 20, 20)
            c.text((24, 22), "%d:%02d" % (rng.integers(1, 13), rng.integers(0, 60)), font("arial.ttf", 20), fg, anchor="lm")
            c.text((w - 24, 22), "%d%%" % rng.integers(10, 100), font("arial.ttf", 18), fg, anchor="rm")
            d.rectangle([0, 44, w, 130], fill=(0, 128, 105) if not dark else (31, 44, 52))
            c.text((90, 74), random.choice(["Mummy", "Office Group", "Rahul", "Family ❤"]), font("arialbd.ttf", 26), (255, 255, 255), anchor="lm")
            c.text((90, 106), random.choice(["online", "last seen today at 9:41", "typing..."]), font("arial.ttf", 18), (220, 240, 235), anchor="lm")
            y = 160
            if kind == "chat":
                while y < h - 160:
                    me = rng.random() < 0.5
                    line = random.choice(CHAT_LINES)
                    f = font("arial.ttf", 22)
                    tw = d.textlength(line, font=f) + 90
                    x0 = w - tw - 16 if me else 16
                    col = ((0, 92, 75) if dark else (217, 253, 211)) if me else ((32, 44, 51) if dark else (255, 255, 255))
                    d.rounded_rectangle([x0, y, x0 + tw, y + 56], 14, fill=col)
                    c.text((x0 + 14, y + 28), line, f, fg, anchor="lm")
                    c.text((x0 + tw - 10, y + 44), "%d:%02d" % (rng.integers(1, 13), rng.integers(0, 60)), font("arial.ttf", 13), (140, 140, 140), anchor="rm")
                    y += 76
            else:
                for k in range(9):
                    d.rectangle([16, y, w - 16, y + 92], fill=(255, 255, 255) if not dark else (32, 44, 51))
                    d.ellipse([28, y + 16, 88, y + 76], fill=tuple(int(v) for v in rng.integers(80, 220, 3)))
                    c.text((104, y + 32), random.choice(["Settings", "Notifications", "Wi-Fi", "Bluetooth", "Storage", "Battery"]), font("arialbd.ttf", 22), fg, anchor="lm")
                    c.text((104, y + 64), "Tap to change", font("arial.ttf", 17), (120, 120, 120), anchor="lm")
                    y += 104
            d.rounded_rectangle([12, h - 90, w - 12, h - 30], 30, fill=(255, 255, 255) if not dark else (32, 44, 51))
            c.text((40, h - 60), "Type a message", font("arial.ttf", 22), (150, 150, 150), anchor="lm")
            name = ("Screenshot_2024%02d%02d-%06d_WhatsApp.png" % (rng.integers(1, 13), rng.integers(1, 28), rng.integers(0, 999999))) if rng.random() < 0.6 else wa_name(rng)
            p = out / "screenshot" / name
            if p.suffix == ".jpg":
                save(c.img, p, quality=80, blocks=corrupt(c.blocks, rng))
            else:
                save(c.img, p, blocks=corrupt(c.blocks, rng))
        elif kind == "receipt":
            w, h = 600, 1000
            c = Canvas(Image.new("RGB", (w, h), (248, 248, 244)))
            mono = font("consola.ttf", 22)
            lines = ["SHREE GANESH STORES", "GSTIN 27ABCDE1234F1Z5", "Date: 12/03/2024  10:42", "-" * 34]
            total = 0
            for _ in range(rng.integers(5, 12)):
                price = int(rng.integers(20, 400)); total += price
                lines.append("%-22s %8.2f" % (random.choice(["Rice 5kg", "Milk", "Sugar", "Tea 250g", "Oil 1L", "Soap"]), price))
            lines += ["-" * 34, "TOTAL          Rs %8.2f" % total, "Paid by UPI  Ref 4021%06d" % rng.integers(0, 999999), "Thank you visit again"]
            for k, line in enumerate(lines):
                c.text((40, 50 + k * 38), line, mono, (30, 30, 30), anchor="lm")
            arr = np.asarray(c.img).astype(np.float32) + rng.normal(0, 2, (h, w, 3))
            save(Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)), out / "screenshot" / wa_name(rng), quality=85, blocks=corrupt(c.blocks, rng))
        else:  # QR code
            mods = 29
            grid = rng.random((mods, mods)) < 0.5
            for (r0, c0) in [(0, 0), (0, mods - 7), (mods - 7, 0)]:
                grid[r0:r0 + 7, c0:c0 + 7] = True
                grid[r0 + 1:r0 + 6, c0 + 1:c0 + 6] = False
                grid[r0 + 2:r0 + 5, c0 + 2:c0 + 5] = True
            arr = np.where(grid, 0, 255).astype(np.uint8)
            img = Image.fromarray(arr).resize((mods * 20, mods * 20), Image.NEAREST)
            canvas = Image.new("L", (mods * 20 + 160, mods * 20 + 160), 255)
            canvas.paste(img, (80, 80))
            save(canvas.convert("RGB"), out / "screenshot" / wa_name(rng, "png"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="eval/sample-set")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--n", type=int, default=40, help="images per category (greetings get +8 duplicates)")
    a = ap.parse_args()
    random.seed(a.seed)
    rng = np.random.default_rng(a.seed)
    out = Path(a.out)
    make_real(rng, out, int(a.n * 1.5))
    make_greeting(rng, out, a.n)
    make_meme(rng, out, a.n)
    make_screenshot(rng, out, a.n)
    for sub in ["real", "greeting", "meme", "screenshot"]:
        print(sub, len([p for p in (out / sub).iterdir() if not p.name.endswith(".json")]))


if __name__ == "__main__":
    main()
