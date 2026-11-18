#!/usr/bin/env python3
"""Builds docs/code-metrics.html: code metrics of the Java tools, with charts.

Run from the project root:   python3 docs/src/build-code-metrics.py

Measures java/ with jmetrics.py (next to this script), fills the page body
(code-metrics.body.html) and writes one self-contained HTML file: the data and
d3 are embedded, so the page works offline. d3 is downloaded from cdnjs; without
network, the copy embedded in the previous docs/code-metrics.html is reused.
"""
import datetime, json, os, re, subprocess, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(ROOT, "docs", "code-metrics.html")
D3_URL = "https://cdnjs.cloudflare.com/ajax/libs/d3/7.9.0/d3.min.js"
D3_MARK = "/* d3 v7.9.0, https://d3js.org, ISC License, embedded so the page works offline */"
TOOLS = {"ConvKaikki2Tab.java": "kaikki-2-tab", "ConvStardict2Tab.java": "stardict-2-tab", "ConvTab2Xdxf.java": "tab-2-xdxf",
         "ConvXdxf2Pbdic.java": "xdxf-2-pbdic", "ConvXdxf2Stardict.java": "xdxf-2-stardict", "CheckPbdic.java": "check-pbdic"}

sys.dont_write_bytecode = True  # no __pycache__ in docs/src
sys.path.insert(0, HERE)
import jmetrics  # noqa: E402

HEAD = '<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width, initial-scale=1">\n<title>pb-dic-converter Code Metrics</title>\n<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;500;600&family=Literata:opsz,wght@7..72,400&display=swap">\n<style>\n  :root {\n    --color-fg: #1d2321; --color-fg-muted: #5a6560; --color-bg: #f6f7f5; --color-panel: #ffffff; --color-border-line: #d5dbd7;\n    --color-ok: #2e7d4f; --color-warn: #b06a00; --color-bad: #b3261e;\n    --font-anthropic-sans: "IBM Plex Sans", system-ui, -apple-system, "Segoe UI", sans-serif;\n    --font-anthropic-serif: "Literata", Georgia, "Times New Roman", serif;\n    --cds-font-size-caption: 12.5px; --cds-font-size-body: 14px; --cds-font-size-heading: 18px; --cds-font-size-title: 30px;\n    --cds-font-weight-medium: 600;\n    --cds-gap-xs: 4px; --cds-gap-sm: 8px; --cds-gap-md: 12px; --cds-gap-lg: 20px; --cds-gap-xl: 36px;\n    --cds-pad-sm: 6px; --cds-pad-md: 10px; --cds-pad-lg: 16px; --cds-radius: 8px;\n    --cds-chart-1: #2f6fb3; --cds-chart-2: #d0782a;\n    --cds-chart-muted: #c9d0cc; --cds-chart-grid: #e3e7e4; --cds-chart-axis: #b7bfba; --cds-chart-status-critical: #b3261e;\n    --cds-text-secondary: #5a6560; --cds-surface-popover: #ffffff; --cds-border: #d5dbd7; --cds-shadow-popover: 0 4px 14px rgba(0,0,0,.12);\n  }\n  @media (prefers-color-scheme: dark) {\n    :root:not([data-theme="light"]) {\n      --color-fg: #e3e8e5; --color-fg-muted: #9ca8a2; --color-bg: #141817; --color-panel: #1c2220; --color-border-line: #333d39;\n      --color-ok: #6cc28e; --color-warn: #e0a24a; --color-bad: #ef7d73;\n      --cds-chart-1: #6ea8e8; --cds-chart-2: #eda15e;\n      --cds-chart-muted: #47524d; --cds-chart-grid: #29312e; --cds-chart-axis: #4b5651; --cds-chart-status-critical: #ef7d73;\n      --cds-text-secondary: #9ca8a2; --cds-surface-popover: #222a27; --cds-border: #3a4541; --cds-shadow-popover: 0 4px 14px rgba(0,0,0,.5);\n      color-scheme: dark;\n    }\n  }\n  body { margin: 0; background: var(--color-bg); color: var(--color-fg); font-family: var(--font-anthropic-sans); font-size: var(--cds-font-size-body); line-height: 1.5; }\n  .cm-page { max-width: 1100px; margin: 0 auto; padding-block: 32px 56px; padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); }\n</style>\n@@D3@@\n<script>\n  // Generated from the measurement of java/ (see "How these numbers are measured").\n  window.CM_DATA = @@DATA@@;\n  // Minimal stand-in for the dashboard API the page code uses: data, calc, draw, colors.\n  (function () {\n    const calcs = {}, draws = [], cache = {};\n    window.dash = {\n      colors: [\'var(--cds-chart-1)\', \'var(--cds-chart-2)\'],\n      calc(id, def) { calcs[id] = def; },\n      draw(name, fn) { draws.push([name, fn]); },\n      data(id) {\n        if (id in CM_DATA) return { status: \'ok\', data: CM_DATA[id] };\n        if (calcs[id]) {\n          if (!(id in cache)) cache[id] = calcs[id].fn(...calcs[id].inputs.map((i) => dash.data(i).data));\n          return { status: \'ok\', data: cache[id] };\n        }\n        return { status: \'missing\', data: [] };\n      },\n      _run() { draws.forEach(([name, fn]) => document.querySelectorAll(\'[data-draw="\' + name + \'"]\').forEach((el) => fn(el))); },\n    };\n  })();\n</script>\n</head>\n<body>\n<div class="cm-page">\n'
TAIL = "\n</div>\n<script>\n  document.querySelectorAll('[data-cm-date]').forEach((el) => { el.textContent = CM_DATA.measured; });\n  dash._run();\n  let cmW = window.innerWidth;\n  window.addEventListener('resize', () => { if (window.innerWidth !== cmW) { cmW = window.innerWidth; dash._run(); } });\n</script>\n</body>\n</html>\n"


def rows(result):
    files, methods = [], []
    for f in result:
        ms, name = f["methods"], f["file"]
        part = "Shared" if "/" in name else "Tool"
        files.append({"file": name, "tool": TOOLS.get(name, ""), "part": part, "lines": f["lines"], "code": f["code"],
                      "comment": f["comment"], "blank": f["blank"], "types": len(f["types"]), "methods": len(ms),
                      "avg_len": round(sum(m["lines"] for m in ms) / len(ms), 1) if ms else 0,
                      "max_len": max((m["lines"] for m in ms), default=0),
                      "avg_cc": round(sum(m["cc"] for m in ms) / len(ms), 1) if ms else 0,
                      "max_cc": max((m["cc"] for m in ms), default=0)})
        for m in ms:
            methods.append({"id": f"{name}:{m['first']}", "file": name, "part": part, "method": m["name"],
                            "line": m["first"], "lines": m["lines"], "cc": m["cc"]})
    return files, methods


def d3_source():
    try:
        with urllib.request.urlopen(D3_URL, timeout=30) as r:
            return r.read().decode("utf-8")
    except Exception as e:
        if os.path.exists(OUT):
            m = re.search(re.escape(D3_MARK) + r"\n(.*?)\n</script>", open(OUT, encoding="utf-8").read(), re.S)
            if m:
                print(f"d3 not downloaded ({e}); reusing the copy in {os.path.relpath(OUT, ROOT)}", file=sys.stderr)
                return m.group(1)
        sys.exit(f"d3 could not be downloaded ({e}) and no previous page holds a copy")


def main():
    files, methods = rows(jmetrics.measure(os.path.join(ROOT, "java")))
    try:
        commit = subprocess.run(["git", "-C", ROOT, "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
    except OSError:
        commit = ""
    measured = datetime.date.today().strftime("%-d %B %Y") + (f" (commit {commit})" if commit else "")
    data = json.dumps({"files": files, "methods": methods, "measured": measured}, ensure_ascii=False).replace("</", "<\\/")
    d3 = d3_source()
    if "</script" in d3.lower():
        sys.exit("unexpected </script> in d3")
    body = open(os.path.join(HERE, "code-metrics.body.html"), encoding="utf-8").read()
    body = body[body.index("-->") + 4:] if body.startswith("<!--") else body
    page = HEAD.replace("@@D3@@", "<script>" + D3_MARK + "\n" + d3 + "\n</script>").replace("@@DATA@@", data) + body + TAIL
    open(OUT, "w", encoding="utf-8").write(page)
    print(f"{os.path.relpath(OUT, ROOT)}: {len(files)} files, {len(methods)} methods, {sum(f['code'] for f in files)} code lines")


if __name__ == "__main__":
    main()
