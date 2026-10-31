"""
WhatsApp-style greeting cards for the ad and the demo library: freely licensed Wikimedia Commons
photos (see stock/CREDITS.md) + Devanagari/English wishes, laid out in HTML and rendered by
headless Edge, which shapes Devanagari correctly (Pillow without raqm doesn't).

    python greeting_cards.py <stock dir> <out dir>
"""
import os, subprocess, sys

STOCK, OUT = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
os.makedirs(OUT, exist_ok=True)

# (photo, size, big line, small line, signature, text colour scheme, vertical position)
CARDS = [
    ("marigold_1.jpg", (1080, 1080), "शुभ सकाळ", "आपला दिवस आनंदी जावो", "~ शुभेच्छा", "gold", "center"),
    ("rose_1.jpg", (1080, 1080), "Good Morning", "Have a blessed day", "", "script", "top"),
    ("chai_1.jpg", (1080, 1080), "सुप्रभात", "चाय के साथ मुस्कुराइए", "", "white", "top"),
    ("lotus_1.jpg", (1080, 1080), "शुभ रात्री", "गोड स्वप्नांच्या शुभेच्छा", "", "pink", "bottom"),
    ("diya_1.jpg", (1080, 1350), "शुभ दीपावली", "Happy Diwali", "दिव्यांच्या प्रकाशात उजळो आपले आयुष्य", "gold", "bottom"),
    ("diya_2.jpg", (1080, 1080), "दिवाळीच्या हार्दिक शुभेच्छा", "", "", "gold", "top"),
    ("rose_3.jpg", (1080, 1350), "वाढदिवसाच्या हार्दिक शुभेच्छा", "Happy Birthday", "", "white", "top"),
    ("lotus_2.jpg", (1080, 1080), "Good Morning", "Every sunrise is a new beginning", "", "script", "bottom"),
    ("marigold_2.jpg", (1080, 1350), "गणेश चतुर्थीच्या", "हार्दिक शुभेच्छा", "गणपती बाप्पा मोरया", "gold", "center"),
    ("mountain_1.jpg", (1080, 1080), "शुभ सकाळ", "पावसाळी सकाळ, ताजं मन", "", "white", "top"),
    ("rose_2.jpg", (1080, 1080), "आभार", "तुम्ही दिलेल्या शुभेच्छा माझ्यासाठी लाख मोलाच्या आहेत", "", "gold", "top"),
    ("lotus_3.jpg", (1080, 1080), "शुभ दिवस", "Stay blessed", "", "pink", "center"),
]

STYLES = {
    "gold": "background:linear-gradient(180deg,#fff6c9 0%,#ffd45a 45%,#f0a020 100%);-webkit-background-clip:text;color:transparent;"
            "filter:drop-shadow(0 4px 0 #7a3a00) drop-shadow(0 0 18px rgba(0,0,0,.55));",
    "white": "color:#fff;text-shadow:0 0 6px #000,0 4px 14px rgba(0,0,0,.8),0 0 40px rgba(255,170,0,.6);",
    "pink": "color:#fff;text-shadow:0 0 8px #c2185b,0 4px 16px rgba(0,0,0,.7),0 0 36px rgba(255,105,180,.9);",
    "script": "color:#fff;font-family:'Segoe Script','Gabriola',cursive;font-weight:700;"
              "text-shadow:0 0 8px #b03060,0 4px 14px rgba(0,0,0,.75);",
}

def html(photo, w, h, big, small, sign, scheme, pos):
    justify = {"top": "flex-start", "center": "center", "bottom": "flex-end"}[pos]
    size = 150 if len(big) <= 9 else 112 if len(big) <= 18 else 92
    if scheme == "script": size = 128
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>
html,body{{margin:0;width:{w}px;height:{h}px;overflow:hidden}}
.card{{position:relative;width:{w}px;height:{h}px;background:url('file:///{os.path.join(STOCK, photo).replace(os.sep, '/')}') center/cover;
  display:flex;flex-direction:column;align-items:center;justify-content:{justify};padding:90px 60px;box-sizing:border-box;text-align:center;
  font-family:'Nirmala UI',sans-serif}}
.card:before{{content:'';position:absolute;inset:0;background:radial-gradient(ellipse at 50% {'25%' if pos == 'top' else '75%' if pos == 'bottom' else '50%'},rgba(0,0,0,.45),rgba(0,0,0,0) 65%)}}
.frame{{position:absolute;inset:22px;border:6px solid rgba(255,214,90,.85);border-radius:28px;box-shadow:inset 0 0 0 3px rgba(255,255,255,.35)}}
.big{{position:relative;font-size:{size}px;font-weight:800;line-height:1.15;{STYLES[scheme]}}}
.small{{position:relative;margin-top:18px;font-size:52px;font-weight:700;color:#fff;text-shadow:0 2px 10px rgba(0,0,0,.9)}}
.sign{{position:relative;margin-top:22px;font-size:40px;color:#ffe9a8;text-shadow:0 2px 8px rgba(0,0,0,.9)}}
.flourish{{position:relative;font-size:46px;color:#ffd45a;text-shadow:0 2px 6px rgba(0,0,0,.8);margin:6px 0}}
</style></head><body><div class="card"><div class="frame"></div>
<div class="flourish">❀ ✦ ❀</div><div class="big">{big}</div>
{f'<div class="small">{small}</div>' if small else ''}{f'<div class="sign">{sign}</div>' if sign else ''}
</div></body></html>"""

profile = os.path.join(OUT, "_edge")
for i, (photo, (w, h), big, small, sign, scheme, pos) in enumerate(CARDS):
    page = os.path.join(OUT, f"card_{i + 1:02d}.html")
    open(page, "w", encoding="utf-8").write(html(photo, w, h, big, small, sign, scheme, pos))
    png = os.path.join(OUT, f"card_{i + 1:02d}.png")
    # Edge can hand the job to an already-running instance and exit before writing, so use a
    # fresh profile per card and retry until the PNG exists.
    for attempt in range(4):
        if os.path.exists(png): os.remove(png)
        subprocess.run([EDGE, "--headless", "--disable-gpu", "--hide-scrollbars", "--no-first-run",
                        f"--user-data-dir={profile}_{i}_{attempt}", "--allow-file-access-from-files", f"--screenshot={png}",
                        f"--window-size={w},{h}", "--virtual-time-budget=3000", "file:///" + page.replace(os.sep, "/")],
                       capture_output=True, timeout=60)
        if os.path.exists(png) and os.path.getsize(png) > 10_000: break
    print(png, os.path.exists(png), flush=True)
