"""Classic top/bottom-caption memes on freely licensed photos, rendered by headless Edge.

    python meme_cards.py <stock dir> <out dir>
"""
import os, subprocess, sys

STOCK, OUT = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
os.makedirs(OUT, exist_ok=True)

MEMES = [
    ("chai_1.jpg", "Me after one sip of chai", "Ready to fight the whole world"),
    ("food_1.jpg", "Mom: we have food at home", "The food at home:"),
    ("mountain_1.jpg", "Planned a trek for 6 AM", "Woke up at 11"),
    ("wedding_1.jpg", "Relatives at every wedding", "So, when is your turn?"),
]

def html(photo, top, bottom):
    cap = ("font:900 78px Impact,'Arial Black',sans-serif;color:#fff;text-transform:uppercase;text-align:center;"
           "-webkit-text-stroke:4px #000;paint-order:stroke fill;letter-spacing:1px;line-height:1.05;padding:0 40px")
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>
html,body{{margin:0;width:1080px;height:1080px;overflow:hidden;background:#000}}
.m{{position:relative;width:1080px;height:1080px;background:url('file:///{os.path.join(STOCK, photo).replace(os.sep, '/')}') center/cover;
  display:flex;flex-direction:column;justify-content:space-between;padding:36px 0;box-sizing:border-box}}
.c{{{cap}}}
</style></head><body><div class="m"><div class="c">{top}</div><div class="c">{bottom}</div></div></body></html>"""

for i, (photo, top, bottom) in enumerate(MEMES):
    page = os.path.join(OUT, f"meme_{i + 1:02d}.html")
    open(page, "w", encoding="utf-8").write(html(photo, top, bottom))
    png = os.path.join(OUT, f"meme_{i + 1:02d}.png")
    for attempt in range(4):
        subprocess.run([EDGE, "--headless", "--disable-gpu", "--hide-scrollbars", "--no-first-run",
                        f"--user-data-dir={os.path.join(OUT, '_edge')}_{i}_{attempt}", "--allow-file-access-from-files",
                        f"--screenshot={png}", "--window-size=1080,1080", "--virtual-time-budget=3000",
                        "file:///" + page.replace(os.sep, "/")], capture_output=True, timeout=60)
        if os.path.exists(png) and os.path.getsize(png) > 10_000: break
    print(png, flush=True)
