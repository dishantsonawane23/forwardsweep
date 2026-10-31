"""Find freely licensed photos on Wikimedia Commons and download them with credits.
Keeps only CC0 / public domain / CC BY (no share-alike, no non-commercial)."""
import json, os, re, subprocess, sys, time, urllib.parse, urllib.request

def fetch(url):
    for _ in range(4):
        r = subprocess.run(["curl", "-s", "--retry", "3", "-A", UA["User-Agent"], url], capture_output=True)
        if r.returncode == 0 and r.stdout: return r.stdout
        time.sleep(2)
    raise RuntimeError("fetch failed: " + url)

UA = {"User-Agent": "ForwardSweepAdBuilder/1.0 (open-source app ad; contact via repo)"}
OK = re.compile(r"^(cc0|public domain|pd|cc by \d|cc-by-\d)", re.I)
QUERIES = {
    "marigold": "marigold flowers garland",
    "diya": "diya oil lamp diwali",
    "sunrise": "sunrise sky clouds",
    "chai": "cup of tea masala chai",
    "rose": "red roses bouquet",
    "lotus": "pink lotus flower",
    "rangoli": "rangoli colourful",
    "family": "indian family portrait",
    "friends": "friends group photo smiling india",
    "wedding": "indian wedding couple",
    "food": "indian thali food",
    "mountain": "sahyadri mountains monsoon",
}
out_dir = sys.argv[1]
os.makedirs(out_dir, exist_ok=True)
cpath = os.path.join(out_dir, "credits.json")
credits = json.load(open(cpath, encoding="utf-8")) if os.path.exists(cpath) else []
for key, q in QUERIES.items():
    if os.path.exists(os.path.join(out_dir, f"{key}_1.jpg")): continue
    url = "https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode({
        "action": "query", "generator": "search", "gsrnamespace": 6, "gsrsearch": f"{q} filetype:bitmap",
        "gsrlimit": 30, "prop": "imageinfo", "iiprop": "url|extmetadata|size|mime", "iiurlwidth": 1400, "format": "json"})
    try:
        data = json.loads(fetch(url))
    except Exception as e:
        print(key, "skipped", e); continue
    pages = sorted(data.get("query", {}).get("pages", {}).values(), key=lambda p: p.get("index", 99))
    got = 0
    for p in pages:
        ii = p["imageinfo"][0]; md = ii.get("extmetadata", {})
        lic = md.get("LicenseShortName", {}).get("value", "")
        if not OK.match(lic) or "sa" in lic.lower() or ii.get("mime") != "image/jpeg": continue
        if ii["width"] < 1000 or ii["height"] < 700: continue
        artist = re.sub("<[^>]+>", "", md.get("Artist", {}).get("value", "unknown")).strip()
        name = f"{key}_{got + 1}.jpg"
        open(os.path.join(out_dir, name), "wb").write(fetch(ii["thumburl"])); time.sleep(0.5)
        credits.append(f"| {name} | {p['title'].replace('File:', '')} | {artist[:60]} | {lic} | {ii['descriptionurl']} |")
        got += 1
        if got >= 3: break
    print(key, got, flush=True)
    json.dump(credits, open(cpath, "w", encoding="utf-8"))
open(os.path.join(out_dir, "CREDITS.md"), "w", encoding="utf-8").write(
    "| File | Commons title | Author | Licence | Source |\n|---|---|---|---|---|\n" + "\n".join(credits) + "\n")
