"""Rough Java code metrics: lines, types, methods, method length, cyclomatic complexity.

Usage: python3 jmetrics.py <root-with-java-files> <out.json>
(docs/src/build-code-metrics.py uses it to build docs/code-metrics.html.)
Comments and string/char literals are blanked out before analysis (text blocks included).
Complexity per method = 1 + if, for, while, case labels, catch, &&, ||, ?: (approximate).
"""
import json, os, re, sys

KEYWORDS = {"if", "for", "while", "switch", "catch", "synchronized", "return", "new", "else", "try", "do", "throw", "case"}


def strip(src):
    """Returns (code with comments/literals blanked, per-line flags: has_code, has_comment)."""
    out = []
    i, n = 0, len(src)
    comment_lines = set()
    line = 0
    while i < n:
        c = src[i]
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            comment_lines.add(line)
            out.append(" " * (j - i))
            i = j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            seg = src[i:j]
            for k in range(seg.count("\n") + 1):
                comment_lines.add(line + k)
            out.append(re.sub(r"[^\n]", " ", seg))
            line += seg.count("\n")
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            seg = src[i:j]
            out.append('"' + re.sub(r"[^\n]", " ", seg[1:-1]) + '"')
            line += seg.count("\n")
            i = j
        elif c in "\"'":
            j = i + 1
            while j < n and src[j] != c:
                j += 2 if src[j] == "\\" else 1
            out.append(c + " " * (j - i - 1) + c)
            i = j + 1
        else:
            if c == "\n":
                line += 1
            out.append(c)
            i += 1
    code = "".join(out)
    return code, comment_lines


def complexity(body):
    cc = 1
    cc += len(re.findall(r"\b(if|for|while|catch)\b", body))
    cc += len(re.findall(r"\bcase\b", body))
    cc += body.count("&&") + body.count("||")
    cc += len(re.findall(r"(?<![<,(\s])\s\?\s", body))  # ternary, not wildcard generics
    return cc


def methods(code, src_lines):
    found = []
    for m in re.finditer(r"\{", code):
        start = m.start()
        k = start - 1
        # text since the previous statement/block boundary
        b = max(code.rfind(";", 0, start), code.rfind("{", 0, start), code.rfind("}", 0, start))
        head = code[b + 1:start]
        h = re.sub(r"\s+", " ", head).strip()
        sig = re.search(r"(\w+)\s*\((?:[^()]|\([^()]*\))*\)\s*(?:throws\s+[\w.,\s]+)?$", h)
        if not sig or "->" in h or "=" in h.split("(")[0]:
            continue
        name = sig.group(1)
        if name in KEYWORDS:
            continue
        before = h[: sig.start()].strip()
        if before.endswith("new") or before.endswith("."):
            continue
        if not before and not re.match(r"[A-Z]", name):
            continue  # a call followed by a block? not a declaration
        # match braces
        depth, j = 0, start
        while j < len(code):
            if code[j] == "{":
                depth += 1
            elif code[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        first = code.count("\n", 0, start) + 1
        last = code.count("\n", 0, j) + 1
        body = code[start:j + 1]
        found.append({"name": name, "first": first, "last": last, "lines": last - first + 1,
                      "cc": complexity(body), "start": start, "end": j})
    # drop methods nested in other methods (e.g. in anonymous classes) from the length stats? keep all, mark nesting
    for f in found:
        f["nested"] = any(o is not f and o["start"] < f["start"] and f["end"] <= o["end"] for o in found)
    return found


def analyse(path, rel):
    src = open(path, encoding="utf-8").read()
    code, comment_lines = strip(src)
    lines = src.split("\n")
    if lines and lines[-1] == "":
        lines = lines[:-1]
    code_lines = code.split("\n")
    blank = sum(1 for l in lines if not l.strip())
    code_n = sum(1 for l in code_lines[:len(lines)] if l.strip())
    comment_only = sum(1 for i, l in enumerate(lines) if l.strip() and not code_lines[i].strip())
    types = re.findall(r"\b(class|interface|enum|record)\s+([A-Z]\w*)", code)
    ms = methods(code, lines)
    return {"file": rel, "lines": len(lines), "code": code_n, "comment": comment_only, "blank": blank,
            "types": [f"{k} {n}" for k, n in types], "methods": ms}


def measure(root):
    """Metrics of every .java file under root, sorted by path."""
    result = []
    for d, _, files in os.walk(root):
        for f in sorted(files):
            if f.endswith(".java"):
                p = os.path.join(d, f)
                result.append(analyse(p, os.path.relpath(p, root)))
    result.sort(key=lambda r: r["file"])
    return result


if __name__ == "__main__":
    json.dump(measure(sys.argv[1]), open(sys.argv[2], "w"), indent=1)
