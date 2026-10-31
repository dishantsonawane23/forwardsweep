"""
ForwardSweep ad: 1080x1920 (9:16), 30 fps, ~38.5 s, timed to the Kokoro voiceover (voiceover.py).

Every frame is drawn with Pillow and piped to ffmpeg. Layout follows the cross-platform safe
band for Reels/Shorts/TikTok: captions and load-bearing visuals stay inside x 180-900,
y 220-1420 (the rest is covered by platform UI). Captions are static, as native captions are.

Assets: greeting cards and memes made from freely licensed photos (greeting_cards.py,
meme_cards.py), emulator screenshots of the app with that demo library, and Windows emoji for
the "your photos" tiles (no identifiable people, so no model releases needed).

    python make_ad.py <project root> <output.mp4>
"""
import math, os, random, subprocess, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

ROOT, OUT = sys.argv[1], sys.argv[2]
W, H, FPS, DURATION = 1080, 1920, 30, 38.5
# Scene starts, timed to the voiceover (audio.py uses the same list).
T = [0.0, 4.8, 10.2, 15.0, 20.9, 26.7, 32.4]
SAFE_X0, SAFE_X1, SAFE_Y0, SAFE_Y1 = 180, 900, 220, 1420

INK = (24, 27, 38)
PRIMARY = (74, 92, 146)
AMBER = (199, 125, 16)
PINK = (194, 65, 111)
GREEN = (46, 125, 50)
PAPER = (247, 246, 251)
WHITE = (255, 255, 255)

FONTS = "C:/Windows/Fonts"
def font(size, bold=True): return ImageFont.truetype(os.path.join(FONTS, "segoeuib.ttf" if bold else "segoeui.ttf"), size)
EMOJI = ImageFont.truetype(os.path.join(FONTS, "seguiemj.ttf"), 109)   # colour emoji render at 109 px

def load(rel): return Image.open(os.path.join(ROOT, rel)).convert("RGB")
AD = "marketing/ad/assets"
CARDS = sorted(f for f in os.listdir(os.path.join(ROOT, AD, "cards")))
greetings = [load(f"{AD}/cards/{f}") for f in CARDS if f.startswith("card_")]
memes = [load(f"{AD}/cards/{f}") for f in CARDS if f.startswith("meme_")]
shots = {k: load(f"{AD}/ui/{k}.png") for k in ["home", "review", "cleaned"]}
STOCK = os.path.join(ROOT, AD, "stock")
memories = [(load(f"{AD}/stock/{f}.jpg"), label) for f, label in
            [("mountain_1", "Monsoon trek"), ("food_1", "Sunday lunch"), ("diya_3", "Diwali at home"),
             ("sunrise_1", "Holiday"), ("wedding_1", "Cousin's wedding")]
            if os.path.exists(os.path.join(STOCK, f + ".jpg"))]

# ── helpers ──────────────────────────────────────────────────────────────────────────────
def clamp(x, a=0.0, b=1.0): return max(a, min(b, x))
def ease_out(x): x = clamp(x); return 1 - (1 - x) ** 3
def ease_in_out(x): x = clamp(x); return 3 * x * x - 2 * x * x * x
def seg(t, start, dur): return clamp((t - start) / dur)

def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.width - 1, img.height - 1], radius, fill=255)
    out = img.convert("RGBA"); out.putalpha(mask); return out

def shadowed(canvas, img, xy, radius=24, blur=30, alpha=110):
    sh = Image.new("RGBA", (img.width + blur * 4, img.height + blur * 4), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle([blur * 2, blur * 2 + 12, blur * 2 + img.width, blur * 2 + img.height + 12], radius, fill=(0, 0, 0, alpha))
    canvas.alpha_composite(sh.filter(ImageFilter.GaussianBlur(blur)), (int(xy[0]) - blur * 2, int(xy[1]) - blur * 2))
    canvas.alpha_composite(img, (int(xy[0]), int(xy[1])))

_phones = {}
def phone(name, height):
    key = (name, height)
    if key not in _phones:
        shot = shots[name]; s = shot.resize((int(shot.width * height / shot.height), height), Image.LANCZOS)
        bezel = 16
        frame = Image.new("RGBA", (s.width + bezel * 2, s.height + bezel * 2), (0, 0, 0, 0))
        ImageDraw.Draw(frame).rounded_rectangle([0, 0, frame.width - 1, frame.height - 1], 58, fill=(18, 18, 22, 255))
        frame.alpha_composite(rounded(s, 44), (bezel, bezel))
        _phones[key] = frame
    return _phones[key]

def wrap(d, text, f, width):
    lines, cur = [], ""
    for word in text.split():
        trial = (cur + " " + word).strip()
        if d.textlength(trial, font=f) <= width or not cur: cur = trial
        else: lines.append(cur); cur = word
    return lines + [cur] if cur else lines

def caption(c, text, highlight=None, y=SAFE_Y0 + 10, max_h=300, start=64, box=True):
    """Static caption inside the safe band, auto-sized (start ~64 px, shrink in 2 px steps)."""
    d = ImageDraw.Draw(c); width = SAFE_X1 - SAFE_X0 - 56
    size = start
    while True:
        f = font(size); lines = wrap(d, text, f, width); lh = size * 1.18
        if len(lines) * lh <= max_h or size <= 30: break
        size -= 2
    h = len(lines) * lh + 44
    if box: d.rounded_rectangle([SAFE_X0, y, SAFE_X1, y + h], 36, fill=WHITE + (240,))
    for i, line in enumerate(lines):
        x = (W - d.textlength(line, font=f)) / 2; yy = y + 20 + i * lh
        if highlight and highlight in line:
            pre, post = line.split(highlight, 1)
            d.text((x, yy), pre, font=f, fill=INK); x += d.textlength(pre, font=f)
            d.text((x, yy), highlight, font=f, fill=PRIMARY); x += d.textlength(highlight, font=f)
            d.text((x, yy), post, font=f, fill=INK)
        else:
            d.text((x, yy), line, font=f, fill=INK)
    return y + h

def pill(c, label, cx, cy, fill, size=36, alpha=1.0):
    d = ImageDraw.Draw(c); f = font(size); w = d.textlength(label, font=f) + 56; a = int(255 * clamp(alpha))
    d.rounded_rectangle([cx - w / 2, cy - 34, cx + w / 2, cy + 34], 34, fill=fill + (a,))
    d.text((cx - w / 2 + 28, cy - size * 0.68), label, font=f, fill=WHITE + (a,))

def shield(d, cx, cy, r, colour=PRIMARY):
    pts = [(cx, cy - r), (cx + r * .85, cy - r * .62), (cx + r * .78, cy + r * .25), (cx, cy + r), (cx - r * .78, cy + r * .25), (cx - r * .85, cy - r * .62)]
    d.polygon(pts, fill=colour + (255,))
    d.line([(cx - r * .38, cy + r * .02), (cx - r * .08, cy + r * .32), (cx + r * .42, cy - r * .28)], fill=WHITE + (255,), width=max(3, int(r * .16)), joint="curve")

# ── scene 1-2: forwards pile up, then memories glow inside the pile ───────────────────────
random.seed(3)
TILE = 290
pool = greetings + memes
tiles = []
for i in range(34):
    img = rounded(ImageOps.fit(pool[(i * 7) % len(pool)], (TILE, TILE), Image.LANCZOS), 26)
    col, row = i % 4, i // 4
    tiles.append(dict(img=img.rotate(random.uniform(-9, 9), expand=True, resample=Image.BICUBIC),
                      x=-30 + col * 285 + random.uniform(-20, 20), y=H - 380 - row * 215 + random.uniform(-25, 25),
                      t0=0.1 + i * 0.12))
MEM_POS = [(300, 700), (760, 820), (330, 1060), (770, 1240), (500, 1330)]

def scene_pile(c, t):
    for tl in tiles:
        p = ease_out(seg(t, tl["t0"], 0.5))
        if p <= 0: continue
        y = -TILE * 1.4 + (tl["y"] + TILE * 1.4) * p
        c.alpha_composite(tl["img"], (int(tl["x"]), int(y)))
    if t >= T[1]:
        # Dim the forwards, then lift the real memories out of the pile one by one.
        dim = ease_in_out(seg(t, T[1], 0.6)) * 0.55
        c.alpha_composite(Image.new("RGBA", (W, H), (20, 20, 30, int(255 * dim))))
        for k, (img, label) in enumerate(memories):
            p = ease_out(seg(t, T[1] + 0.4 + k * 0.45, 0.5))
            if p <= 0: continue
            size = int(230 + 30 * p)
            tile = rounded(ImageOps.fit(img, (size, size), Image.LANCZOS), 26)
            glow = Image.new("RGBA", (size + 60, size + 60), (0, 0, 0, 0))
            ImageDraw.Draw(glow).rounded_rectangle([20, 20, size + 40, size + 40], 40, fill=(255, 214, 90, int(200 * p)))
            cx, cy = MEM_POS[k]
            c.alpha_composite(glow.filter(ImageFilter.GaussianBlur(14)), (cx - 30 - size // 2, cy - 30 - size // 2))
            c.alpha_composite(tile, (cx - size // 2, cy - size // 2))
            pill(c, label, cx, cy + size // 2 + 4, INK, size=30, alpha=p)
        caption(c, "Somewhere in there are my actual photos and videos.", highlight="photos and videos.")
    else:
        caption(c, "My phone after one week in the family WhatsApp group.", highlight="WhatsApp group.")

def scene_app(c, t, shot, text, start, highlight=None, from_side=1):
    p = ease_out(seg(t, start, 0.7))
    ph = phone(shot, 1180)
    shadowed(c, ph, ((W - ph.width) / 2 + from_side * (1 - p) * W, 560 + (1 - p) * 60), radius=58)
    caption(c, text, highlight=highlight)

TILES = [("\U0001F46A", "Family"), ("\U0001F46B", "Friends"), ("\U0001F3D4", "Trips"),
         ("\U0001F933", "Selfies"), ("\U0001F476", "Kids"), ("\U0001F35B", "Food")]

def scene_yours(c, t, start):
    d = ImageDraw.Draw(c)
    cw, ch, gap = 220, 230, 30
    x0 = (W - (3 * cw + 2 * gap)) / 2; y0 = 560
    for k, (emo, label) in enumerate(TILES):
        p = ease_out(seg(t, start + 0.15 * k, 0.45))
        if p <= 0: continue
        x = x0 + (k % 3) * (cw + gap); y = y0 + (k // 3) * (ch + gap) + (1 - p) * 40
        card = Image.new("RGBA", (cw, ch), (0, 0, 0, 0)); cd = ImageDraw.Draw(card)
        cd.rounded_rectangle([0, 0, cw - 1, ch - 1], 34, fill=WHITE + (255,), outline=(220, 222, 235, 255), width=3)
        cd.text(((cw - 109) / 2, 22), emo, font=EMOJI, embedded_color=True)
        f = font(36); cd.text(((cw - cd.textlength(label, font=f)) / 2, 158), label, font=f, fill=INK)
        shield(cd, cw - 36, 36, 26, GREEN)
        card.putalpha(card.getchannel("A").point(lambda v: int(v * p)))
        c.alpha_composite(card, (int(x), int(y)))
    a = ease_out(seg(t, start + 1.4, 0.4))
    if a > 0:
        f = font(40, bold=False); line = "I review everything before it goes."
        d.text(((W - d.textlength(line, font=f)) / 2, 1110), line, font=f, fill=INK + (int(255 * a),))
        pill(c, "Only forwards get picked", W // 2, 1250, PRIMARY, size=36, alpha=a)
    caption(c, "My own photos and videos stay out of it.", highlight="stay out of it.")

def scene_end(c, t, start):
    a = ease_out(seg(t, start, 0.6)); d = ImageDraw.Draw(c)
    cx, cy, r = W // 2, 470, 120
    d.rounded_rectangle([cx - r, cy - r, cx + r, cy + r], 56, fill=PRIMARY + (int(255 * a),))
    sweep = 300 * ease_in_out(seg(t, start + 0.2, 1.0))
    d.arc([cx - 72, cy - 72, cx + 72, cy + 72], -90, -90 + sweep, fill=WHITE + (int(255 * a),), width=22)
    d.ellipse([cx - 18, cy - 18, cx + 18, cy + 18], fill=AMBER + (int(255 * a),))
    name = "ForwardSweep"; size = 104
    while d.textlength(name, font=font(size)) > SAFE_X1 - SAFE_X0 - 20: size -= 2
    f = font(size)
    d.text(((W - d.textlength(name, font=f)) / 2, 640), name, font=f, fill=INK + (int(255 * a),))
    a2 = ease_out(seg(t, start + 0.5, 0.5)); f2 = font(52, bold=False)
    for i, line in enumerate(["Sweep the forwards.", "Keep the memories."]):
        d.text(((W - d.textlength(line, font=f2)) / 2, 800 + i * 66), line, font=f2, fill=PRIMARY + (int(255 * a2),))
    a3 = ease_out(seg(t, start + 1.0, 0.5))
    pill(c, "Free \u00b7 Open source", W // 2, 1040, INK, alpha=a3)
    pill(c, "Works offline \u00b7 Nothing leaves your phone", W // 2, 1140, PRIMARY, size=32, alpha=a3)
    pill(c, "Images \u00b7 Videos \u00b7 GIFs", W // 2, 1240, AMBER, alpha=a3)

def background(t):
    bg = Image.new("RGBA", (W, H), PAPER + (255,)); d = ImageDraw.Draw(bg)
    k = 0.5 + 0.5 * math.sin(t / DURATION * math.pi)
    for y in range(0, H, 8):
        f = y / H
        d.rectangle([0, y, W, y + 8], fill=tuple(int(PAPER[i] * (1 - f * 0.10) + (AMBER[i] if i == 0 else PRIMARY[i]) * f * 0.06 * k) for i in range(3)) + (255,))
    return bg

def frame(t):
    c = background(t)
    if t < T[2]:
        scene_pile(c, t)
        w = ease_in_out(seg(t, T[2] - 0.35, 0.35))
        if w > 0: ImageDraw.Draw(c).rectangle([0, 0, W, H * w], fill=PAPER + (255,))
    elif t < T[3]:
        scene_app(c, t, "home", "ForwardSweep sorts the forwards from the memories.", T[2], highlight="ForwardSweep")
    elif t < T[4]:
        scene_app(c, t, "review", "Good mornings, wishes, memes, forwarded videos. Already picked out.", T[3], highlight="Already picked out.")
    elif t < T[5]:
        scene_yours(c, t, T[4])
    elif t < T[6]:
        scene_app(c, t, "cleaned", "One tap. 30 days in the trash if I change my mind.", T[5], highlight="One tap.", from_side=-1)
    else:
        scene_end(c, t, T[6])
    fade = min(seg(t, 0, 0.3), 1 - seg(t, DURATION - 0.6, 0.6))
    if fade < 1: c.alpha_composite(Image.new("RGBA", (W, H), (0, 0, 0, int(255 * (1 - fade)))))
    return c.convert("RGB")

def safe_zone_overlay(img):
    """Review aid: shades what platform UI covers."""
    shade = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(shade)
    for box in [(0, 0, W, SAFE_Y0), (0, SAFE_Y1, W, H), (0, 0, SAFE_X0, H), (SAFE_X1, 0, W, H)]:
        d.rectangle(box, fill=(255, 0, 0, 60))
    o = img.convert("RGBA"); o.alpha_composite(shade); return o.convert("RGB")

if __name__ == "__main__":
    n = int(DURATION * FPS)
    proc = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                             "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart", OUT], stdin=subprocess.PIPE)
    for i in range(n):
        proc.stdin.write(frame(i / FPS).tobytes())
        if i % 150 == 0: print(f"frame {i}/{n}", flush=True)
    proc.stdin.close(); proc.wait()
    keys = [2.5, 8.5, 13.0, 18.5, 24.5, 30.0, 36.0]
    sheet = Image.new("RGB", (len(keys) * 270, 480), WHITE)
    for k, t in enumerate(keys):
        sheet.paste(safe_zone_overlay(frame(t)).resize((270, 480), Image.LANCZOS), (k * 270, 0))
    sheet.save(os.path.splitext(OUT)[0] + "-keyframes.jpg", quality=88)
    print("done", OUT)
