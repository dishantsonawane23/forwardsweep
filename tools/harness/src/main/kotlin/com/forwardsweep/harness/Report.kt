package com.forwardsweep.harness

import com.forwardsweep.classifier.Category
import com.forwardsweep.classifier.Classification
import java.io.File

object Report {
    fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    fun writeCsv(file: File, results: List<Pair<File, Classification>>, root: File) {
        file.bufferedWriter().use { w ->
            w.write("path,truth,category,top,confidence,p_real,p_greeting,p_meme,p_screenshot,preselected,protected,faces,phash,reasons\n")
            for ((f, c) in results) {
                val p = c.probabilities
                w.write(listOf(
                    f.relativeTo(root).invariantSeparatorsPath, Evaluation.labelOf(f, root)?.name ?: "",
                    c.category.name, c.topCategory.name, "%.3f".format(c.confidence),
                    *Category.SCORED.map { "%.3f".format(p[it] ?: 0.0) }.toTypedArray(),
                    c.preselected, c.protected, c.faces, "%016x".format(c.pHash),
                    "\"" + c.reasons.joinToString(" | ").replace("\"", "'") + "\"",
                    "\"" + c.features.entries.joinToString(" ") { (k, v) -> "$k=%.3f".format(v) } + "\"",
                ).joinToString(",") + "\n")
            }
        }
    }

    fun writeHtml(file: File, results: List<Pair<File, Classification>>, groups: List<List<String>>, root: File, signals: List<String>) {
        val dupOf = HashMap<String, Int>()
        groups.forEachIndexed { i, g -> g.drop(1).forEach { dupOf[it] = i + 1 } }
        val sb = StringBuilder()
        sb.append("""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ForwardSweep Report</title><style>
:root{--bg:#fbfaf7;--fg:#1d1c1a;--muted:#6b6862;--card:#fff;--line:#e7e3dc;--accent:#2f6f5e;--warn:#b5462f;--chip:#efece6}
@media (prefers-color-scheme:dark){:root{--bg:#161615;--fg:#ecebe7;--muted:#9a978f;--card:#201f1d;--line:#34322e;--accent:#7cc3ad;--warn:#e98a73;--chip:#2b2a27}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
header{padding:28px 24px 8px;max-width:1300px;margin:auto}h1{font-size:22px;margin:0 0 4px}header p{color:var(--muted);margin:0}
nav{position:sticky;top:0;background:var(--bg);border-bottom:1px solid var(--line);padding:10px 24px;display:flex;gap:8px;flex-wrap:wrap;z-index:2}
nav a{padding:6px 12px;border-radius:999px;background:var(--chip);color:var(--fg);text-decoration:none;font-size:13px}
section{max-width:1300px;margin:auto;padding:16px 24px}h2{font-size:16px;margin:16px 0 10px}h2 span{color:var(--muted);font-weight:400}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(170px,1fr));gap:12px}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;overflow:hidden}
.card.bad{border:2px solid var(--warn)}.card img{width:100%;aspect-ratio:1;object-fit:cover;display:block;background:var(--chip)}
.meta{padding:8px 10px;font-size:12px}.name{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:var(--muted)}
.bars{display:flex;height:5px;border-radius:3px;overflow:hidden;margin:6px 0}.b0{background:#4f8a5b}.b1{background:#d9a03f}.b2{background:#a565c4}.b3{background:#5a87c5}
.badges{display:flex;gap:4px;flex-wrap:wrap}.badge{padding:1px 7px;border-radius:999px;background:var(--chip);font-size:11px}
.badge.sel{background:var(--accent);color:var(--bg)}.badge.bad{background:var(--warn);color:var(--bg)}
details{margin-top:4px}summary{cursor:pointer;color:var(--muted)}details ul{margin:4px 0 0;padding-left:16px}
.legend{display:flex;gap:12px;color:var(--muted);font-size:12px;margin-top:8px}.legend i{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:4px;vertical-align:-1px}
</style></head><body><header><h1>ForwardSweep classification report</h1>""")
        val pre = results.filter { it.second.preselected }
        sb.append("<p>${results.size} images · signals: ${signals.joinToString(", ")} · pre-selected ${pre.size} (${humanSize(pre.sumOf { it.first.length() })}) · ${groups.size} duplicate groups</p>")
        sb.append("""<div class="legend"><span><i class="b0"></i>real</span><span><i class="b1"></i>greeting</span><span><i class="b2"></i>meme</span><span><i class="b3"></i>screenshot</span><span>red border = disagrees with folder label</span></div></header><nav>""")
        for (c in Category.entries) {
            val n = results.count { it.second.category == c }
            if (n > 0) sb.append("<a href=\"#${c.name}\">${c.label} · $n</a>")
        }
        sb.append("</nav>")
        for (c in Category.entries) {
            val items = results.filter { it.second.category == c }
            if (items.isEmpty()) continue
            sb.append("<section id=\"${c.name}\"><h2>${c.label} <span>· ${items.size} · ${humanSize(items.sumOf { it.first.length() })}</span></h2><div class=\"grid\">")
            for ((f, r) in items.sortedByDescending { it.second.confidence }) {
                val truth = Evaluation.labelOf(f, root)
                val bad = truth != null && r.category != Category.UNCERTAIN && truth != r.category
                sb.append("<div class=\"card${if (bad) " bad" else ""}\"><img loading=\"lazy\" src=\"${f.toURI()}\" alt=\"\"><div class=\"meta\">")
                sb.append("<div class=\"name\" title=\"${esc(f.name)}\">${esc(f.relativeTo(root).invariantSeparatorsPath)}</div>")
                sb.append("<div class=\"bars\">")
                Category.SCORED.forEachIndexed { i, k -> sb.append("<div class=\"b$i\" style=\"width:${"%.1f".format((r.probabilities[k] ?: 0.0) * 100)}%\"></div>") }
                sb.append("</div><div class=\"badges\"><span class=\"badge\">${r.topCategory.label.substringBefore(" ")} ${"%.2f".format(r.confidence)}</span>")
                if (r.preselected) sb.append("<span class=\"badge sel\">selected</span>")
                if (r.protected) sb.append("<span class=\"badge\">🛡 faces</span>")
                dupOf[r.id]?.let { sb.append("<span class=\"badge\">dup #$it</span>") }
                if (bad) sb.append("<span class=\"badge bad\">label: ${truth!!.label.substringBefore(" ")}</span>")
                sb.append("</div><details><summary>why</summary><ul>")
                r.reasons.forEach { sb.append("<li>${esc(it)}</li>") }
                sb.append("</ul></details></div></div>")
            }
            sb.append("</div></section>")
        }
        sb.append("</body></html>")
        file.writeText(sb.toString())
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
